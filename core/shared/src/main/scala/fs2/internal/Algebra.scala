package fs2.internal


import cats.data.NonEmptyList
import cats.~>
import cats.effect.{Effect, Sync}
import cats.implicits._
import fs2._
import fs2.async.Promise

import scala.concurrent.ExecutionContext

private[fs2] sealed trait Algebra[F[_],O,R]

private[fs2] object Algebra {

  final case class Output[F[_],O](values: Segment[O,Unit]) extends Algebra[F,O,Unit]
  final case class Run[F[_],O,R](values: Segment[O,R]) extends Algebra[F,O,R]
  final case class Eval[F[_],O,R](value: F[R]) extends Algebra[F,O,R]

  final case class Acquire[F[_],O,R](resource: F[R], release: R => F[Unit]) extends Algebra[F,O,(R,Token)]
  final case class Release[F[_],O](token: Token) extends Algebra[F,O,Unit]
  final case class OpenScope[F[_],O]() extends Algebra[F,O,RunFoldScope[F]]
  final case class CloseScope[F[_],O](toClose: RunFoldScope[F]) extends Algebra[F,O,Unit]
  final case class GetScope[F[_],O]() extends Algebra[F,O,RunFoldScope[F]]

  def output[F[_],O](values: Segment[O,Unit]): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](Output(values))

  def output1[F[_],O](value: O): FreeC[Algebra[F,O,?],Unit] =
    output(Segment.singleton(value))

  def segment[F[_],O,R](values: Segment[O,R]): FreeC[Algebra[F,O,?],R] =
    FreeC.Eval[Algebra[F,O,?],R](Run(values))

  def eval[F[_],O,R](value: F[R]): FreeC[Algebra[F,O,?],R] =
    FreeC.Eval[Algebra[F,O,?],R](Eval(value))

  def acquire[F[_],O,R](resource: F[R], release: R => F[Unit]): FreeC[Algebra[F,O,?],(R,Token)] =
    FreeC.Eval[Algebra[F,O,?],(R,Token)](Acquire(resource, release))

  def release[F[_],O](token: Token): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](Release(token))

  private def openScope[F[_],O]: FreeC[Algebra[F,O,?],RunFoldScope[F]] =
    FreeC.Eval[Algebra[F,O,?],RunFoldScope[F]](OpenScope())

  private def closeScope[F[_],O](toClose: RunFoldScope[F]): FreeC[Algebra[F,O,?],Unit] =
    FreeC.Eval[Algebra[F,O,?],Unit](CloseScope(toClose))

  def scope[F[_],O,R](pull: FreeC[Algebra[F,O,?],R]): FreeC[Algebra[F,O,?],R] =
    openScope flatMap { newScope =>
      FreeC.Bind(pull, (e: Either[Throwable,R]) => e match {
        case Left(e) => closeScope(newScope) flatMap { _ => raiseError(e) }
        case Right(r) => closeScope(newScope) map { _ => r }
      })
    }

  def getScope[F[_],O]: FreeC[Algebra[F,O,?],RunFoldScope[F]] =
    FreeC.Eval[Algebra[F,O,?],RunFoldScope[F]](GetScope())

  def pure[F[_],O,R](r: R): FreeC[Algebra[F,O,?],R] =
    FreeC.Pure[Algebra[F,O,?],R](r)

  def raiseError[F[_],O,R](t: Throwable): FreeC[Algebra[F,O,?],R] =
    FreeC.Fail[Algebra[F,O,?],R](t)

  def suspend[F[_],O,R](f: => FreeC[Algebra[F,O,?],R]): FreeC[Algebra[F,O,?],R] =
    FreeC.suspend(f)

  def uncons[F[_],X,O](s: FreeC[Algebra[F,O,?],Unit], chunkSize: Int = 1024, maxSteps: Long = 10000): FreeC[Algebra[F,X,?],Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] = {
 //   println(s"    UNCNS: ${s.viewL.get}")
    s.viewL.get match {
      case done: FreeC.Pure[Algebra[F,O,?], Unit] => pure(None)
      case failed: FreeC.Fail[Algebra[F,O,?], _] => raiseError(failed.error)
      case bound: FreeC.Bind[Algebra[F,O,?],_,Unit] =>
        val f = bound.f.asInstanceOf[Either[Throwable,Any] => FreeC[Algebra[F,O,?],Unit]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case os: Algebra.Output[F, O] =>
            pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some((os.values, f(Right(())))))
          case os: Algebra.Run[F, O, x] =>
            try {
              def asSegment(c: Catenable[Chunk[O]]): Segment[O,Unit] =
                c.uncons.flatMap { case (h1,t1) => t1.uncons.map(_ => Segment.catenated(c.map(Segment.chunk))).orElse(Some(Segment.chunk(h1))) }.getOrElse(Segment.empty)
              os.values.force.splitAt(chunkSize, Some(maxSteps)) match {
                case Left((r,chunks,rem)) =>
                  pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some(asSegment(chunks) -> f(Right(r))))
                case Right((chunks,tl)) =>
                  pure[F,X,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](Some(asSegment(chunks) -> FreeC.Bind[Algebra[F,O,?],x,Unit](segment(tl), f)))
              }
            } catch { case NonFatal(e) => FreeC.suspend(uncons(f(Left(e)), chunkSize)) }
          case algebra => // Eval, Acquire, Release, OpenScope, CloseScope, GetScope
            FreeC.Bind[Algebra[F,X,?],Any,Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]](
              FreeC.Eval[Algebra[F,X,?],Any](algebra.asInstanceOf[Algebra[F,X,Any]]),
              (x: Either[Throwable,Any]) => uncons[F,X,O](f(x), chunkSize)
            )
        }
      case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }


  /** Left-folds the output of a stream. */
  def runFold[F[_],O,B](stream: FreeC[Algebra[F,O,?],Unit], init: B)(f: (B, O) => B)(implicit F: Sync[F]): F[B] =
    F.delay(RunFoldScope.newRoot).flatMap { scope =>
      runFoldScope[F,O,B](scope, stream, init)(f).attempt.flatMap {
        case Left(t) => scope.close *> F.raiseError(t)
        case Right(b) => scope.close as b
      }
    }

  private[fs2] def runFoldScope[F[_],O,B](scope: RunFoldScope[F], stream: FreeC[Algebra[F,O,?],Unit], init: B)(g: (B, O) => B)(implicit F: Sync[F]): F[B] =
    runFoldLoop[F,O,B](scope, init, g, uncons(stream).viewL)

  private def runFoldLoop[F[_],O,B](
    scope: RunFoldScope[F]
    , acc: B
    , g: (B, O) => B
    , v: FreeC.ViewL[Algebra[F,O,?], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]]
  )(implicit F: Sync[F]): F[B] = {
    def outAcc = {
      acc match {
        case vb: scala.collection.immutable.VectorBuilder[_] => vb.result
        case other => other
      }
    }
    println(s"  :RFL [${scope.id}]: ${v.get} $outAcc")
    v.get match {
      case done: FreeC.Pure[Algebra[F,O,?], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] => done.r match {
        case None => println(s"DONE: ${scope.id}: $outAcc"); F.pure(acc)
        case Some((hd, tl)) =>
          F.suspend {
            try runFoldLoop[F,O,B](scope, hd.fold(acc)(g).force.run, g, uncons(tl).viewL)
            catch { case NonFatal(e) => runFoldLoop[F,O,B](scope, acc, g, uncons(tl.asHandler(e)).viewL) }
          }
      }

      case failed: FreeC.Fail[Algebra[F,O,?], _] =>
        if (failed.error == Interrupted) F.pure(acc)
        else F.raiseError(failed.error)

      case bound: FreeC.Bind[Algebra[F,O,?], _, Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]] =>
        val f = bound.f.asInstanceOf[
          Either[Throwable,Any] => FreeC[Algebra[F,O,?], Option[(Segment[O,Unit], FreeC[Algebra[F,O,?],Unit])]]]
        val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F,O,?],_]].fr
        fx match {
          case wrap: Algebra.Eval[F, O, _] =>
            F.flatMap(F.attempt(wrap.value)) { e => runFoldLoop(scope, acc, g, f(e).viewL) }

          case acquire: Algebra.Acquire[F,_,_] =>
            val acquireResource = acquire.resource
            val resource = Resource.create
            F.flatMap(scope.register(resource)) { mayAcquire =>
              if (mayAcquire) {
                F.flatMap(F.attempt(acquireResource)) {
                  case Right(r) =>
                    val finalizer = F.suspend { acquire.release(r) }
                    F.flatMap(resource.acquired(finalizer)) { result =>
                      runFoldLoop(scope, acc, g, f(result.right.map { _ => (r, resource.id) }).viewL)
                    }

                  case Left(err) =>
                    F.flatMap(scope.releaseResource(resource.id)) { result =>
                      val failedResult: Either[Throwable, Unit] =
                        result.left.toOption.map { err0 =>
                          Left(new CompositeFailure(err, NonEmptyList.of(err0)))
                         }.getOrElse(Left(err))
                      runFoldLoop(scope, acc, g, f(failedResult).viewL)
                    }
                }
              } else {
                F.raiseError(Interrupted) // todo: do we really need to signal this as an exception ?
              }
            }


          case release: Algebra.Release[F,_] =>
            F.flatMap(scope.releaseResource(release.token))  { result =>
              runFoldLoop(scope, acc, g, f(result).viewL)
            }

          case c: Algebra.CloseScope[F,_] =>
            F.flatMap(c.toClose.close) { result =>
              F.flatMap(c.toClose.openAncestor) { scopeAfterClose =>
                runFoldLoop(scopeAfterClose, acc, g, f(result).viewL)
              }
            }

          case o: Algebra.OpenScope[F,_] =>
            F.flatMap(scope.open) { innerScope =>
              runFoldLoop(innerScope, acc, g, f(Right(innerScope)).viewL)
            }

          case e: GetScope[F,_] =>
            F.suspend {
              runFoldLoop(scope, acc, g, f(Right(scope)).viewL)
            }

          case other => sys.error(s"impossible Segment or Output following uncons: $other")
        }
      case e => sys.error("FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
    }
  }

  def translate[F[_],G[_],O,R](fr: FreeC[Algebra[F,O,?],R], u: F ~> G): FreeC[Algebra[G,O,?],R] = {
    def algFtoG[O2]: Algebra[F,O2,?] ~> Algebra[G,O2,?] = new (Algebra[F,O2,?] ~> Algebra[G,O2,?]) { self =>
      def apply[X](in: Algebra[F,O2,X]): Algebra[G,O2,X] = in match {
        case o: Output[F,O2] => Output[G,O2](o.values)
        case Run(values) => Run[G,O2,X](values)
        case Eval(value) => Eval[G,O2,X](u(value))
        case a: Acquire[F,O2,_] => Acquire(u(a.resource), r => u(a.release(r)))
        case r: Release[F,O2] => Release[G,O2](r.token)
        case os: OpenScope[F,O2] => os.asInstanceOf[Algebra[G,O2,X]]
        case cs: CloseScope[F,O2] => cs.asInstanceOf[CloseScope[G,O2]]
        case gs: GetScope[F,O2] => gs.asInstanceOf[Algebra[G,O2,X]]
      }
    }
    fr.translate[Algebra[G,O,?]](algFtoG)
  }


  /**
    * Interrupts supplied algebra in free that defines stream whenever `interruptWith` evaluates.
    *
    * Interruption will cause all cleanups to be evaluated at earliest opportunity.
    *
    * The interruption is implemented as injecting either supplied Throwable or `Interrupted` if not defined.
    * `Interrupted` is special exception that won't cause stream to fail, but instead silently terminate with
    * most recent value accumulated.
    *
    * @param free             Free (stream) that has to be interrupted
    * @param interruptWith    An Promise that whenever evaluated will cause the `free` to interrupt at earliest opportunity.
    *                         If this evaluates to right, this will be replaced with `Interrupted` reason, otherwise
    *                         this yields interruption cause to Left side.
    */
  def interrupt[F[_], O, R](free: FreeC[Algebra[F,O,?],R], interruptWith: Promise[F, Either[Throwable, Unit]])(implicit F: Effect[F], ec: ExecutionContext): FreeC[Algebra[F,O,?],R] = {
    Algebra.getScope[F, O].flatMap { scope =>
      Algebra.eval(async.promise[F, Throwable]).flatMap { interruptPromise =>
        Algebra.eval(async.refOf[F, Either[Throwable, Boolean]](Right(false))).flatMap { interruptRef =>
          // Right(true) //was interrupted and signalled, Right(false) // not yet interrupted

          // runs when interrupt is signalled.
          // this completes only once per whole interrupt
          def runInterrupt: F[Unit] = {
            interruptWith.get flatMap { rsn =>
              val reason = rsn.left.toOption.getOrElse(Interrupted)
              println(s"INTERRUPTED: $rsn, $reason, ${scope.id}")
              interruptRef.modify {
                case Right(false) => Left(reason)
                case other => other
              } flatMap { c =>
                if (c.now == c.previous) F.unit // no change
                else {
                  F.delay {
                    println(s"INTERRUPTING: ${scope.id} $reason")
                  } *>
                    interruptPromise.setSync(reason)
                }
              }
            }
          }


          // This inspects every step of evaluation for interruption posibility
          // Interrupt is injected in FreeC.Bind and if Bind contains Eval(F), the
          // eval is modified such as it races with interruption
          // once interruption is complete this injects failure and resumes to normal operation
          def go(free: FreeC[Algebra[F, O, ?], R]): FreeC[Algebra[F, O, ?], R] = {
            println(s"IG (${scope.id}): ${free.viewL.get} ")
            free.viewL.get match {
              case done: FreeC.Pure[Algebra[F, O, ?], R] => done
              case failed: FreeC.Fail[Algebra[F, O, ?], R] => raiseError(failed.error)
              case bound: FreeC.Bind[Algebra[F, O, ?], _, R] =>
                val f = bound.f.asInstanceOf[Either[Throwable, Any] => FreeC[Algebra[F, O, ?], R]]
                val fx = bound.fx.asInstanceOf[FreeC.Eval[Algebra[F, O, ?], Any]]

                def interruptEval: FreeC.Eval[Algebra[F, O, ?], Any] = fx.fr match {
                  case eval: Algebra.Eval[F, O, Any] =>
                    Algebra.eval[F, O, Any] {
                      interruptPromise.cancellableGet flatMap { case (get, cancel) =>
                        async.race(eval.value, get).flatMap {
                          case Left(x) => cancel as x
                          case Right(err) => F.raiseError(err)
                        }
                      }
                    }.asInstanceOf[FreeC.Eval[Algebra[F, O, ?], Any]]

                 

                  case algebra =>
                    println(s"XXXY ${scope.id} : $algebra")
                    fx
                }

                Algebra.eval(interruptRef.modify {
                  case Left(iRsn) => Right(true)
                  case other => other
                }) flatMap { c => c.previous match {
                  case Right(false) =>
                    // interrupt not yet signalled, insert updated eval (if step was eval) and continue inspection
                    FreeC.Bind[Algebra[F, O, ?], Any, R](
                      interruptEval
                      , r => go(f(r))
                    )
                  case Right(true) =>
                    bound // interrupt already signalled, shall continue with normal evaluation, stop injecting algebra inspection

                  case Left(iRsn) =>
                    FreeC.suspend(f(Left(iRsn))) // signal interruption


                }}


              case e => sys.error("Interrupt: FreeC.ViewL structure must be Pure(a), Fail(e), or Bind(Eval(fx),k), was: " + e)
            }
          }

          Algebra.eval(async.fork(runInterrupt)) flatMap { _ =>
            // tom kae sure the interruption stays in the given scope, we are creating scope of the stream, and
            // when that scope outlives, we always set the interruption to be done,
            // so any future `late` interruption won't take any effect

            go {
              //openScope[F, O] flatMap { interruptScope =>
              acquire[F, O, Unit](F.pure(()), _ =>
                interruptRef.setSync(Right(true)) map { _ =>  println(s"XXXM INTERRUPT FINISHES: ${scope.id}") }
              ) flatMap { case (r, token) =>
                free.flatMap { r =>
                  release(token) map { _ => r }
                  // closeScope[F, O](interruptScope) map { _ => r }
                }
              }
            }
            //go(free)
          }
        }


      }
    }

  }
}
