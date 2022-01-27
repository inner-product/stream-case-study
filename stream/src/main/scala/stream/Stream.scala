package stream
import stream.Response.Await
import stream.Response.Halt
import stream.Response.Value

sealed trait Stream[A] {
  import Stream._

  // Combinators

  def filter(pred: A => Boolean): Stream[A] =
    Filter(this, pred)

  def interleave(that: Stream[A]): Stream[A] =
    Interleave(this, that)

  def map[B](f: A => B): Stream[B] =
    Map(this, f)

  def merge[B](that: Stream[B]): Stream[Either[A, B]] =
    Merge(this, that)

  def zip[B](that: Stream[B]): Stream[(A, B)] =
    Zip(this, that)

  // Interpreters

  def foldLeft[B](z: B)(f: (B, A) => B): B = {
    def loop(ir: Ir[A]): B =
      ir.next() match {
        case Value(value) => foldLeft(f(z, value))(f)
        case Await        => foldLeft(z)(f)
        case Halt         => z
      }

    loop(Ir.compile(this))
  }

  def compile: Ir[A] =
    Ir.compile(this)

  def toList: List[A] =
    foldLeft(List.empty[A])((lst, elt) => elt :: lst).reverse

}
object Stream {
  final case class Constant[A](value: A) extends Stream[A]
  final case class Emit[A](values: Iterator[A]) extends Stream[A]
  final case class Map[A, B](source: Stream[A], f: A => B) extends Stream[B]
  final case class Filter[A](source: Stream[A], pred: A => Boolean)
      extends Stream[A]
  final case class Interleave[A](left: Stream[A], right: Stream[A])
      extends Stream[A]
  final case class Merge[A, B](left: Stream[A], right: Stream[B])
      extends Stream[Either[A, B]]
  final case class Zip[A, B](left: Stream[A], right: Stream[B])
      extends Stream[(A, B)]

  /** Creates an infinite Stream that always produces the given value */
  def constant[A](value: A): Stream[A] = Constant(value)
  def emit[A](values: Iterator[A]): Stream[A] = Emit(values)

}
