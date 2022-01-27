package stream

sealed trait Stream[A] {
  import Stream._
  import Response._

  // Combinators

  def filter(pred: A => Boolean): Stream[A] =
    Filter(this, pred)

  def interleave(that: Stream[A]): Stream[A] =
    ???

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
    }

  def toList: List[A] =
    foldLeft(List.empty[A])((lst, elt) => elt :: lst).reverse

}
object Stream {
  final case class Map[A, B](source: Stream[A], f: A => B) extends Stream[B]
  final case class Emit[A](values: Iterator[A]) extends Stream[A]
  final case class Filter[A](source: Stream[A], pred: A => Boolean)
      extends Stream[A]

  /** Creates an infinite Stream that always produces the given value */
  def constant[A](value: A): Stream[A] = ???
  def emit[A](values: Iterator[A]): Stream[A] = Emit(values)
}
