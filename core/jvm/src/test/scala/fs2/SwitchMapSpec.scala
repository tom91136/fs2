package fs2

import cats.effect.IO
import cats.effect.concurrent.{Ref, Semaphore}
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import cats.implicits._
import fs2.TestUtil._

import scala.concurrent.duration._

class SwitchMapSpec extends Fs2Spec with EventuallySupport {

  "switchMap" - {

    "flatMap equivalence when switching never occurs" in forAll { s: PureStream[Int] =>
		println("========")
      runLog(Stream.eval(Semaphore[IO](1)).flatMap { guard =>
        s.get
          .covary[IO]
//          .onFinalize(guard.acquire) // outer terminates, wait for last inner to emit
			.evalTap(_ => guard.acquire) // wait for inner to emit to prevent switching
			.switchMap(x => Stream.emit(x).onFinalize(guard.release))
      }) shouldBe runLog(s.get.covary[IO].flatMap(Stream.emit(_)))
    }

    "inner stream finalizer always runs before switching" in forAll { s: PureStream[Int] =>
      val prog = Stream.eval(Ref[IO].of(true)).flatMap { ref =>
		  println("========")
        s.get.covary[IO].switchMap { i =>
          Stream.eval(ref.get).flatMap { released =>
            if (!released) Stream.raiseError[IO](new Err)
            else
              Stream
                .eval(ref.set(false) *> IO.sleep(20.millis))
                .onFinalize(IO.sleep(100.millis) *> ref.set(true))
          }
        }
      }
      runLog(prog)
    }

    "when primary stream terminates, inner stream continues" in forAll {
      (s1: PureStream[Int], s2: PureStream[Int]) =>
		  println("========")
//		  runLog(Stream(Stream.emit(1)>> Stream.sleep_(1000.millis), Stream.emit(2) >> Stream.sleep_(1000.millis))
//			  .onFinalize(IO{println("Outer Fin")})
//			  .parJoin(10)
//			  .onFinalize(IO{println("Down Fin")}))
		  
        val prog = s1.get
          .covary[IO]
          .switchMap(s => Stream.sleep_[IO](25.millis) ++ s2.get ++ Stream.emit(s))
        val that = s1.get.covary[IO].last.unNoneTerminate.flatMap(s => s2.get ++ Stream.emit(s))
        runLog(prog) shouldBe runLog(that)
    }

    "when inner stream fails, overall stream fails" in forAll { (s: PureStream[Int], f: Failure) =>
      // filter out empty streams as switching will never occur
      if (s.get.toList.nonEmpty) {
        val prog = (Stream.sleep_[IO](25.millis) ++ s.get).switchMap(_ => f.get)
        val throws = f.get.compile.drain.attempt.unsafeRunSync.isLeft
        if (throws) an[Err] should be thrownBy runLog(prog)
        else runLog(prog)
      }
    }

    "when primary stream fails, overall stream fails and inner stream is terminated" in forAll {
      (f: Failure) =>
        var bgDone = false
        val bg = Stream.repeatEval(IO(1)).onFinalize(IO { bgDone = true })
        val prog = (Stream.emit(1) ++ Stream.sleep_[IO](10 millis) ++ f.get).switchMap(_ => bg)
        val throws = f.get.compile.drain.attempt.unsafeRunSync.isLeft
        if (throws) an[Err] should be thrownBy runLog(prog)
        else runLog(prog)
        eventually(Timeout(3 seconds)) { bgDone shouldBe true }
    }

    "when inner stream fails, inner stream finalizer run before the primary one" in forAll {
      (s: PureStream[Int], f: Failure) =>
        if (s.get.toList.nonEmpty && f.get.compile.drain.attempt.unsafeRunSync.isLeft) {
			println("========" + s +" e=" + f)
          val prog = Stream.eval(Ref[IO].of(false)).flatMap { verdict =>
            Stream.eval(Ref[IO].of(false)).flatMap { innerReleased =>
              (Stream.sleep_[IO](25.millis) ++ s.get)
                .onFinalize(innerReleased.get.flatMap(inner => verdict.set(inner)))
                .switchMap(_ => f.get.onFinalize(innerReleased.set(true)))
                .attempt
                .drain ++
                Stream.eval(verdict.get.flatMap(if (_) IO.raiseError(new Err) else IO(())))
            }
          }
          an[Err] should be thrownBy runLog(prog)
        }
    }

    "when primary stream fails, inner stream finalizer run before the primary one" in forAll {
      (f: Failure) =>
		  if (f.get.compile.drain.attempt.unsafeRunSync.isLeft) {
			  println("========") 
			  val prog = Stream.eval(Ref[IO].of(false)).flatMap { verdict =>
				  Stream.eval(Ref[IO].of(false)).flatMap { innerReleased =>
					  (Stream.emit(1) ++ Stream.sleep_[IO](10 millis) ++ f.get)
						  .onFinalize(innerReleased.get.flatMap(inner => verdict.set(inner)))
						  .switchMap(_ => Stream.repeatEval(IO(1)).onFinalize(innerReleased.set(true)))
						  .attempt
						  .drain ++
					  Stream.eval(verdict.get.flatMap(if (_) IO.raiseError(new Err) else IO(())))
				  }
			  }
			  an[Err] should be thrownBy runLog(prog)
		  }
    }

  }
}
