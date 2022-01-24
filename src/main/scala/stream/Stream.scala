package stream

sealed trait Stream[A] {
  import Stream._
  // Combinator
  def map[B](f: A => B): Stream[B] =
    Map(this, f)

  // Interpreter
  def foldLeft[B](z: B)(f: (B, A) => B): B =
    next match {
      case Some(value) => foldLeft(f(z, value))(f)
      case None        => z
    }

  private[stream] def next: Option[A]
}
object Stream {
  final case class Map[A, B](source: Stream[A], func: A => B)
      extends Stream[B] {}
  final case class Emit[A](values: Iterator[A]) extends Stream[A] {}

  // Constructor
  def emit[A](values: Iterator[A]): Stream[A] =
    Emit(values)
}
