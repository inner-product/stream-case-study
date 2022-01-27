package stream

/** Response represents the result of pulling once from a stream. */
sealed trait Response[+A] {
  import Response._

  def map[B](f: A => B): Response[B] =
    this match {
      case Value(value) => Value(f(value))
      case Await        => Await
      case Halt         => Halt
    }

  def orElse[AA >: A](that: Response[AA]): Response[AA] =
    this match {
      case v: Value[A] => v
      case _           => that
    }
}
object Response {

  /** The stream produced the given value */
  final case class Value[A](value: A) extends Response[A]

  /** The stream has no value available right now, but it might have a value
    * available in the future.
    */
  case object Await extends Response[Nothing]

  /** The stream will never have a value available; it has stopped. */
  case object Halt extends Response[Nothing]

  def value[A](value: A): Response[A] = Value(value)
  val await: Response[Nothing] = Await
  val halt: Response[Nothing] = Halt
}
