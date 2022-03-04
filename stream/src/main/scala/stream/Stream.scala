package stream

import cats.effect.IO
import cats.effect.unsafe.IORuntime

sealed trait Stream[+A] {
  import Response._
  import Stream._

  // Combinators

  def append[B >: A](that: Stream[B]): Stream[B] =
    Append(this, that)

  def ++[B >: A](that: Stream[B]): Stream[B] =
    this.append(that)

  def filter(pred: A => Boolean): Stream[A] =
    Filter(this, pred)

  def interleave[B >: A](that: Stream[B]): Stream[B] =
    Interleave(this, that)

  def map[B](f: A => B): Stream[B] =
    Map(this, f)

  def merge[B](that: Stream[B]): Stream[Either[A, B]] =
    Merge(this, that)

  def parMapUnordered[B](maxConcurrent: Int)(f: A => B): Stream[B] =
    ParMapUnordered(this, maxConcurrent, f)

  def zip[B](that: Stream[B]): Stream[(A, B)] =
    Zip(this, that)

  // Interpreters

  def foldLeft[B](z: B)(f: (B, A) => B): IO[B] = {
    def loop(ir: Ir[A], result: B): IO[B] =
      ir.next.flatMap(r =>
        r match {
          case Value(value) => loop(ir, f(result, value))
          case Await        => loop(ir, result)
          case Halt         => IO.pure(result)
        }
      )

    Ir.compile(this).flatMap(ir => loop(ir, z))
  }

  def compile: IO[Ir[A]] =
    Ir.compile(this)

  def toList(implicit runtime: IORuntime): List[A] =
    foldLeft(List.empty[A])((lst, elt) => elt :: lst)
      .map(_.reverse)
      .unsafeRunSync()

}
object Stream {
  final case class Append[A](first: Stream[A], second: Stream[A])
      extends Stream[A]
  final case class Constant[A](value: A) extends Stream[A]
  final case class Emit[A](values: Seq[A]) extends Stream[A]
  final case class Filter[A](source: Stream[A], pred: A => Boolean)
      extends Stream[A]
  final case class Interleave[A](left: Stream[A], right: Stream[A])
      extends Stream[A]
  final case class Map[A, B](source: Stream[A], f: A => B) extends Stream[B]
  final case class Merge[A, B](left: Stream[A], right: Stream[B])
      extends Stream[Either[A, B]]
  final case class ParMapUnordered[A, B](
      source: Stream[A],
      maxConcurrent: Int,
      f: A => B
  ) extends Stream[B]
  final case class Range(start: Int, stop: Int, step: Int) extends Stream[Int]
  final case class Zip[A, B](left: Stream[A], right: Stream[B])
      extends Stream[(A, B)]
  case object Never extends Stream[Nothing]
  case object Waiting extends Stream[Nothing]
  case object WaitOnce extends Stream[Nothing]

  /** Creates an infinite Stream that always produces the given value */
  def constant[A](value: A): Stream[A] = Constant(value)

  /** Create a stream that produces values in order from the given sequence */
  def emit[A](values: Seq[A]): Stream[A] = Emit(values)

  /** A Stream that never produces a value and immediately halts. */
  val never: Stream[Nothing] = Never

  /** A Stream that produces a range of Ints from start (inclusive) to end
    * (exclusive)
    */
  def range(start: Int, stop: Int): Stream[Int] =
    if (start <= stop) Range(start, stop, 1)
    else Range(start, stop, -1)

  /** A Stream that is always waiting for a value but never produces one. */
  val waiting: Stream[Nothing] = Waiting

  /** A Stream that waits once and then halts */
  val waitOnce: Stream[Nothing] = WaitOnce
}
