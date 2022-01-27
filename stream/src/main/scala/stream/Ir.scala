package stream

/** Ir stands for "Intermediate representation". It is stateful representation
  * we compile Ir into.
  */
sealed trait Ir[A] {
  def next(): Response[A]
}
object Ir {
  import Response._

  final case class Constant[A](value: A) extends Ir[A] {
    def next(): Response[A] = Response.value(value)
  }
  final case class Emit[A](values: Iterator[A]) extends Ir[A] {
    def next(): Response[A] =
      if (values.hasNext) Response.value(values.next())
      else Response.halt
  }
  final case class Map[A, B](source: Ir[A], f: A => B) extends Ir[B] {
    def next(): Response[B] =
      source.next().map(f)
  }
  final case class Filter[A](source: Ir[A], pred: A => Boolean) extends Ir[A] {
    def next(): Response[A] =
      source.next() match {
        case Value(value) =>
          if (pred(value)) Value(value)
          else Response.await
        case Await => Response.await
        case Halt  => Response.halt
      }
  }
  final case class Interleave[A](left: Ir[A], right: Ir[A]) extends Ir[A] {
    // Which side we pull form first
    var pullFromLeft: Boolean = true

    def pull(first: Ir[A], second: Ir[A]): Response[A] =
      first.next().orElse(second.next())

    def next(): Response[A] =
      if (pullFromLeft) {
        pullFromLeft = false
        pull(left, right)
      } else {
        pullFromLeft = true
        pull(right, left)
      }
  }
  final case class Merge[A, B](left: Ir[A], right: Ir[B])
      extends Ir[Either[A, B]] {

    var pullFromLeft: Boolean = true

    def next(): Response[Either[A, B]] =
      if (pullFromLeft) {
        pullFromLeft = false
        left.next() match {
          case Value(value) => Value(Left(value))
          case _            => right.next().map(Right(_))
        }
      } else {
        pullFromLeft = true
        right.next() match {
          case Value(value) => Value(Right(value))
          case _            => left.next().map(Left(_))
        }
      }
  }
  final case class Zip[A, B](left: Ir[A], right: Ir[B]) extends Ir[(A, B)] {
    def next(): Response[(A, B)] = ???
  }

  // Utility methods
  def compile[A](stream: Stream[A]): Ir[A] = {
    stream match {
      case Stream.Constant(value)      => Ir.Constant(value)
      case Stream.Emit(values)         => Ir.Emit(values)
      case Stream.Filter(source, pred) => Ir.Filter(compile(source), pred)
      case Stream.Interleave(left, right) =>
        Ir.Interleave(compile(left), compile(right))
      case Stream.Map(source, f) => Ir.Map(compile(source), f)
      case m: Stream.Merge[a, b] => Ir.Merge(compile(m.left), compile(m.right))
      case z: Stream.Zip[a, b]   => Ir.Zip(compile(z.left), compile(z.right))
    }
  }
}
