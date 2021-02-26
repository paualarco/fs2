/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import cats.effect._
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration._
import cats.syntax.all._
import cats.effect.std.CyclicBarrier

// scenarios to build broacastThrough on top of Topic
// specifically making sure all pipes receive every element
object Ex {

  def sleepRandom = IO(scala.util.Random.nextInt(1000).millis).flatMap(IO.sleep)

  def res(name: String) =
    Resource.make(
      sleepRandom >> IO.println(s"opening $name")
    )(_ => IO.println(s"closing $name"))
  .as {
        Stream.eval(IO.println(s"starting $name"))  ++ Stream.eval(sleepRandom) ++ Stream.eval(IO.println(s"$name executed"))
      }

  // problem:
  //  you want all the subscriptions opened before any subscriber starts,
  //  but close them timely

  def a =
    Stream
      .range(0, 6)
      .covary[IO]
      .foldMap(i => Stream.resource(res(i.toString)))
      .flatMap(
        _.parJoinUnbounded
      )
      .compile
      .drain
      .unsafeRunSync()

  def b = (Stream.resource(res("a")) ++ Stream
    .resource(res("b"))).parJoinUnbounded.compile.drain.unsafeRunSync()

  def c = (Stream.resource(res("a")) ++ Stream
    .resource(res("b"))).flatten.compile.drain.unsafeRunSync()

  def d = Stream.resource {
    (0 until 2).toList.traverse(i => res(i.toString))
  }.flatMap(Stream.emits)
    .parJoinUnbounded
    .compile.drain.unsafeRunSync()

  // Not always true
  // scala> Ex.b
  // opening a
  // opening b
  // a executed
  // closing a
  // b executed
  // closing b

  // scala> Ex.c
  // opening a
  // a executed
  // closing a
  // opening b
  // b executed
  // closing b

  // scala> Ex.d
  // opening 0
  // opening 1
  // 1 executed
  // 0 executed
  // closing 1
  // closing 0

  def e =
    Stream
      .resource(res("thingy"))
      .repeat
      .take(5)
      .parJoinUnbounded
      .compile
      .drain
      .unsafeRunSync()

  // works in the happy path
  // but interruption here is super tricky.
  // interrupting the whole op is fine
  // but when one of the subscriber gets interrupted,
  // if the interruption happens very early, the count changes
  // leading to potential deadlock
  def f =
    Stream.eval(CyclicBarrier[IO](5)).flatMap { barrier =>
      Stream.range(0, 5).map { i =>
        Stream
          .resource(res(i.toString))
          .evalTap(_ => barrier.await) // works, but what about interruption
          .flatten // in the general case, the consumer stream
      }.parJoinUnbounded
    }.compile.drain.unsafeRunSync()

  // works, should work properly with interruption
    def fplus =
    Stream.eval(CyclicBarrier[IO](6)).flatMap { barrier =>
      Stream.range(0, 6).map { i =>
        Stream
          .resource(res(i.toString))
          .flatMap { sub =>
            def pipe[A]: Pipe[IO, A, A] = x => x

            // crucial that awaiting on the barrier
            // is not passed to the pipe, so that the pipe cannot
            // interrupt it
            Stream.eval(barrier.await) ++ sub.through(pipe)
          }
      }.parJoinUnbounded
    }.compile.drain.unsafeRunSync()

  // works, internal interruption (the pipe interrupts)
  def fplusI =
    Stream.eval(IO.ref(0)).flatMap { count =>
      Stream.eval(CyclicBarrier[IO](6)).flatMap { barrier =>
        Stream.range(0, 6).map { i =>
          Stream
            .resource(res(i.toString))
            .flatMap { sub =>
              def pipe[A]: Pipe[IO, A, A] =  {x =>
                Stream.eval(count.updateAndGet(_ + 1)).flatMap { c =>
                  // simulates a pipe that interrupts one of the subs immediately
                  if (c == 2) x.interruptAfter(30.millis)
                  else x
                }
              }
              // crucial that awaiting on the barrier
              // is not passed to the pipe, so that the pipe cannot
              // interrupt it
              Stream.eval(barrier.await) ++ sub.through(pipe)
            }
        }.parJoinUnbounded
      }
    } .compile.drain.unsafeRunSync()


  // suffers from the same bug as aplusII.
  // Not a big deal for `broadcastThrough`, but needs investigation
  def fplusII =
    Stream.eval(CyclicBarrier[IO](6)).flatMap { barrier =>
      Stream.range(0, 6).map { i =>
        Stream
          .resource(res(i.toString))
          .flatMap { sub =>
            def pipe[A]: Pipe[IO, A, A] = x => x

            // crucial that awaiting on the barrier
            // is not passed to the pipe, so that the pipe cannot
            // interrupt it
            Stream.eval(barrier.await) ++ sub.through(pipe)
          }
      }.parJoinUnbounded
    }.interruptAfter(30.millis).compile.drain.unsafeRunSync()




  // works, and should behave properly with interruption too (conceptually)
  def aplus =
    Stream.eval(IO.deferred[Unit]).flatMap { wait =>
      Stream
        .range(0, 6)
        .covary[IO]
        .foldMap(i => Stream.resource(res(i.toString)))
        .flatMap(
          _
            .append(Stream.exec(wait.complete(()).void))
            .map(x => Stream.exec(wait.get) ++ x)
            .parJoinUnbounded
        )
    }.compile
     .drain
     .unsafeRunSync()

  // scala> Ex.aplus
  // opening 0
  // opening 1
  // opening 2
  // opening 3
  // opening 4
  // opening 5
  // 3 executed
  // closing 3
  // 1 executed
  // closing 1
  // 5 executed
  // closing 5
  // 0 executed
  // closing 0
  // 2 executed
  // closing 2
  // 4 executed
  // closing 4


  // works, tests the scenario where one of the pipes interrupts the subscription
  def aplusI =
    Stream.eval(IO.ref(0)).flatMap { count =>
      Stream.eval(IO.deferred[Unit]).flatMap { wait =>
        Stream
          .range(0, 6)
          .covary[IO]
          .foldMap(i => Stream.resource(res(i.toString)))
          .flatMap(
            _
              .append(Stream.exec(wait.complete(()).void))
              .map { x =>
                val y = Stream.exec(wait.get) ++ x
                Stream.eval(count.updateAndGet(_ + 1)).flatMap { c =>
                  // simulates a pipe that interrupts one of the subs immediately
                  if (c == 2) y.interruptAfter(30.millis)
                  else y
                }
            }.parJoinUnbounded
          )
      }
    }
      .compile
      .drain
      .unsafeRunSync()

  // external interruption, testing shutdown
  // leaks a single resource (independent bug I think)
  def aplusII =
    Stream.eval(IO.deferred[Unit]).flatMap { wait =>
      Stream
        .range(0, 6)
        .covary[IO]
        .foldMap(i => Stream.resource(res(i.toString)))
        .flatMap(
          _
            .append(Stream.exec(wait.complete(()).void))
            .map(x => Stream.exec(wait.get) ++ x)
            .parJoinUnbounded
        )
    }.interruptAfter(1.second)
      .compile
      .drain
      .unsafeRunSync()


  def ex = (
    Stream.eval(IO.sleep(2.seconds).onCancel(IO.println("canceled"))) ++ Stream.repeatEval(IO.println("hello")).metered(500.millis).interruptAfter(1.second)
  ).compile.drain.unsafeRunSync()

  def ex2 = (
    Stream.eval(IO.sleep(2.seconds).onCancel(IO.println("canceled"))) ++ Stream.repeatEval(IO.println("hello")).metered(500.millis)
  ).interruptAfter(1.second).compile.drain.unsafeRunSync()




  // as a separate problem, this doesn't work (never terminates)
  def p1 = Stream.range(0, 3).covary[IO].mapAsyncUnordered(100) { i =>
    if (i == 2) IO.canceled.onCancel(IO.println("cancelled"))
    else IO.sleep(500.millis) >> IO.println(i)
  }.compile.drain.unsafeRunSync()
  // cancelled
  // 0
  // 1
  // hangs

  // as a separate problem, this doens't work (leaks a single resource)
  def p2 = {
    def logEventually(s: String) =
      IO(scala.util.Random.nextInt(1000).millis)
        .flatMap(IO.sleep) >> IO.println(s)
    

    def res(name: String) =
      Resource.make(
        logEventually(s"opening $name")
      )(_ => IO.println(s"closing $name"))
        .as {
          Stream.eval(logEventually(s"$name executed"))
        }

    (Stream.resource(res("a")) ++ Stream.resource(res("b")))
      .parJoinUnbounded
      .interruptAfter(200.millis)
      .compile
      .drain
  }.unsafeRunSync()

  // scala> Ex.p2
  // opening a

  // scala> Ex.p2
  // opening a

  // scala> Ex.p2
  // opening a

  // scala> Ex.p2
  // opening a


  def p3 = {
    def logEventually(s: String) =
      // IO(scala.util.Random.nextInt(1000).millis)
      //   .flatMap(IO.sleep) >>
      IO.sleep(1.second) >> IO.println(s)
    

    def res(name: String) =
      Resource.make(
        logEventually(s"opening $name")
      )(_ => IO.println(s"closing $name"))
        .as {
          Stream.eval(logEventually(s"$name executed"))
        }

//

    Stream.resource(res("a"))
    Stream.bracket(IO.sleep(1.second) >> IO.println("opening"))(_ => IO.println("closing"))
      .as(Stream.eval(IO.println("using")))
      .flatten
      .interruptAfter(200.millis)
      .compile
      .drain
  }.unsafeRunSync()



}
