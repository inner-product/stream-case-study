package stream

sealed trait Stream[A] {
    import Stream._

    def map[B](f: A => B): Stream[B] =
        Map(this, f)

    def foldLeft[B](z: B)(f: (B, A) => B): B =
        this.next match {
            case Some(value) => foldLeft(f(z, value))(f)
            case None => z
        }

    def next: Option[A] =
        this match {
            case Emit(values) => values.nextOption()
            case Map(source, f) => source.next.map(f)
        }

}
object Stream {
    final case class Map[A, B](source: Stream[A], f: A => B) extends Stream[B]
    final case class Emit[A](values: Iterator[A]) extends Stream[A]

    def emit[A](values: Iterator[A]): Stream[A] = Emit(values)
}