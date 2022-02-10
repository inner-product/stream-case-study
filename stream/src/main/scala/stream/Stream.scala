package stream

import scala.annotation.tailrec

sealed trait Stream[+A] {
  import Response._
  import Stream._

  // Combinators

  def append[B >: A](that: Stream[B]): Stream[B] =
    Append(this, that)

  def ++[B >: A](that: Stream[B]): Stream[B] =
    this.append(that)

  def flatMap[B](f: A => Stream[B]): Stream[B] =
    FlatMap(this, f)

  def filter(pred: A => Boolean): Stream[A] =
    Filter(this, pred)

  def interleave[B >: A](that: Stream[B]): Stream[B] =
    Interleave(this, that)

  def map[B](f: A => B): Stream[B] =
    Map(this, f)

  def merge[B](that: Stream[B]): Stream[Either[A, B]] =
    Merge(this, that)

  def zip[B](that: Stream[B]): Stream[(A, B)] =
    Zip(this, that)

  // Interpreters

  def foldLeft[B](z: B)(f: (B, A) => B): B = {
    @tailrec
    def loop(ir: Ir[A], result: B): B =
      ir.next() match {
        case Value(value) => loop(ir, f(result, value))
        case Await        => loop(ir, result)
        case Halt         => result
      }

    loop(Ir.compile(this), z)
  }

  def compile: Ir[A] =
    Ir.compile(this)

  def toList: List[A] =
    foldLeft(List.empty[A])((lst, elt) => elt :: lst).reverse

}
object Stream {
  final case class Append[A](first: Stream[A], second: Stream[A])
      extends Stream[A]
  final case class Constant[A](value: A) extends Stream[A]
  final case class Emit[A](values: Iterator[A]) extends Stream[A]
  final case class Filter[A](source: Stream[A], pred: A => Boolean)
      extends Stream[A]
  final case class FlatMap[A, B](source: Stream[A], f: A => Stream[B])
      extends Stream[B]
  final case class Interleave[A](left: Stream[A], right: Stream[A])
      extends Stream[A]
  final case class Map[A, B](source: Stream[A], f: A => B) extends Stream[B]
  final case class Merge[A, B](left: Stream[A], right: Stream[B])
      extends Stream[Either[A, B]]
  final case class Range(start: Int, stop: Int, step: Int) extends Stream[Int]
  final case class Zip[A, B](left: Stream[A], right: Stream[B])
      extends Stream[(A, B)]
  case object Never extends Stream[Nothing]
  case object Waiting extends Stream[Nothing]
  case object WaitOnce extends Stream[Nothing]

  /** Creates an infinite Stream that always produces the given value */
  def constant[A](value: A): Stream[A] = Constant(value)

  /** A Stream that never produces a value and immediately halts. */
  val never: Stream[Nothing] = Never

  /** A Stream that is always waiting for a value but never produces one. */
  val waiting: Stream[Nothing] = Waiting

  /** A Stream that waits once and then halts */
  val waitOnce: Stream[Nothing] = WaitOnce

  /** A Stream that produces a range of Ints from start (inclusive) to end
    * (exclusive)
    */
  def range(start: Int, stop: Int): Stream[Int] =
    if (start <= stop) Range(start, stop, 1)
    else Range(start, stop, -1)

  def emit[A](values: Iterator[A]): Stream[A] = Emit(values)
}
