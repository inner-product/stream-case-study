package stream

sealed trait Stream[A] {
  import Stream._

  // Combinators

  def filter(pred: A => Boolean): Stream[A] =
    ???

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
      case Some(value) => foldLeft(f(z, value))(f)
      case None        => z
    }

  def next: Option[A] =
    this match {
      case Emit(values)   => values.nextOption()
      case Map(source, f) => source.next.map(f)
    }

  def toList: List[A] =
    foldLeft(List.empty[A])((lst, elt) => elt :: lst).reverse

}
object Stream {
  final case class Map[A, B](source: Stream[A], f: A => B) extends Stream[B]
  final case class Emit[A](values: Iterator[A]) extends Stream[A]

  /** Creates an infinite Stream that always produces the given value */
  def constant[A](value: A): Stream[A] = ???
  def emit[A](values: Iterator[A]): Stream[A] = Emit(values)
}
