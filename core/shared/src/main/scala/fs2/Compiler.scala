package fs2

import cats.effect.{Effect, Sync}
import fs2.internal.Algebra
import scala.collection.immutable.VectorBuilder

trait Compiler[F[_]] {

  /**
    * Compiles stream `s` in to a value of the target effect type `F` by folding
    * the output values together, starting with the provided `init` and combining the
    * current value with each output value.
    *
    * When this method has returned, the stream has not begun execution -- this method simply
    * compiles the stream down to the target effect type.
    */
  def fold[O, B](s: Stream[F, O], init: B)(f: (B, O) => B): F[B]

  /**
    * Compiles stream `s` in to a value of the target effect type `F` by logging
    * the output values to a `List`.
    *
    * When this method has returned, the stream has not begun execution -- this method simply
    * compiles the stream down to the target effect type.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.range(0,100).take(5).covary[IO].compile.toList.unsafeRunSync
    * res0: List[Int] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def toList[O](s: Stream[F, O]): F[List[O]]

  /**
    * Compiles stream `s` in to a value of the target effect type `F` by logging
    * the output values to a `Vector`.
    *
    * When this method has returned, the stream has not begun execution -- this method simply
    * compiles the stream down to the target effect type.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.range(0,100).take(5).covary[IO].compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(0, 1, 2, 3, 4)
    * }}}
    */
  def toVector[O](s: Stream[F, O]): F[Vector[O]]

}

trait CompilerLowPriorityImplicits {

  implicit def syncCompiler[F[_]](implicit F: Sync[F]): Compiler[F] = new Compiler[F] {
    def fold[O, B](s: Stream[F, O], init: B)(f: (B, O) => B) =
      Algebra.compile(s.get[F, O], None, init)(f)
    def toList[O](s: Stream[F, O]) =
      F.suspend(F.map(fold(s, new collection.mutable.ListBuffer[O])(_ += _))(_.result))

    def toVector[O](s: Stream[F, O]) =
      F.suspend(F.map(fold(s, new VectorBuilder[O])(_ += _))(_.result))

  }

}

object Compiler extends CompilerLowPriorityImplicits {

  implicit def effectCompiler[F[_]](implicit F: Effect[F]): Compiler[F] = new Compiler[F] {
    def fold[O, B](s: Stream[F, O], init: B)(f: (B, O) => B) =
      Algebra.compile(s.get[F, O], Some(F), init)(f)
    def toList[O](s: Stream[F, O]) =
      F.suspend(F.map(fold(s, new collection.mutable.ListBuffer[O])(_ += _))(_.result))

    def toVector[O](s: Stream[F, O]) =
      F.suspend(F.map(fold(s, new VectorBuilder[O])(_ += _))(_.result))

  }

}
