package stream
import stream.Response.Value
import stream.Response.Await
import stream.Response.Halt

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
    ???

  def zip[B](that: Stream[B]): Stream[(A, B)] =
    ???

  // Interpreters

  def foldLeft[B](z: B)(f: (B, A) => B): B =
    this.next match {
      case Value(value) => foldLeft(f(z, value))(f)
      case Await        => foldLeft(z)(f)
      case Halt         => z
    }

  def next: Response[A] =
    this match {
      case Constant(value) => Value(value)
      case Emit(values) =>
        if (values.hasNext) Response.value(values.next())
        else Response.halt
      case Map(source, f) => source.next.map(f)
      case Filter(source, pred) =>
        source.next match {
          case Value(value) =>
            if (pred(value)) Value(value)
            else Response.await
          case Await => Await
          case Halt  => Halt
        }
      case Interleave(left, right) =>
        // We would ideally alternate between left and right but we don't yet
        // know how to add state to our interpreter
        left.next match {
          case Value(value) => Value(value)
          case Await        => right.next
          case Halt         => right.next
        }
    }

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

  /** Creates an infinite Stream that always produces the given value */
  def constant[A](value: A): Stream[A] = Constant(value)
  def emit[A](values: Iterator[A]): Stream[A] = Emit(values)
}
