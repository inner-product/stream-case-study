package stream

sealed trait Stream[A] {

  // Combinators

  def filter(pred: A => Boolean): Stream[A] =
    ???

  def map[B](f: A => B): Stream[B] =
    ???

  // Interpreters

  private def next: Option[A] =
    ???

  def foldLeft[B](z: B)(f: (B, A) => B): B =
    this.next match {
      case Some(value) => foldLeft(f(z, value))(f)
      case None        => z
    }

  def toList: List[A] =
    foldLeft(List.empty[A])((lst, elt) => elt :: lst).reverse

}
object Stream {

  // Constructors

  /** Creates an infinite Stream that always produces the given value */
  def constant[A](value: A): Stream[A] = ???
  def emit[A](values: Iterator[A]): Stream[A] = ???
}
