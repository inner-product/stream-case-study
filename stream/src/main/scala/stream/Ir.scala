package stream

/** Ir stands for "Intermediate representation". It is stateful representation
  * we compile Stream into.
  */
sealed trait Ir[+A] {
  def next(): Response[A]
}
object Ir {
  import Response._

  final case class Append[A](left: Ir[A], right: Ir[A]) extends Ir[A] {
    var leftHasValues = true
    var rightHasValues = true

    def pullFromRight(): Response[A] =
      if (rightHasValues) {
        right.next() match {
          case v: Value[A] => v
          case Await       => Await
          case Halt =>
            rightHasValues = false
            Halt
        }
      } else {
        Halt
      }

    def next(): Response[A] =
      if (leftHasValues) {
        left.next() match {
          case v: Value[A] => v
          case Await       => Await
          case Halt =>
            leftHasValues = false
            pullFromRight()
        }
      } else {
        pullFromRight()
      }
  }

  final case class Constant[A](value: A) extends Ir[A] {
    def next(): Response[A] = Response.value(value)
  }
  final case class Emit[A](values: Iterator[A]) extends Ir[A] {
    def next(): Response[A] =
      if (values.hasNext) Response.value(values.next())
      else Response.halt
  }
  final case class FlatMap[A, B](source: Ir[A], f: A => Ir[B]) extends Ir[B] {
    var sourceHasValues = true
    var flatMapStream: Ir[B] = _
    var flatMapStreamHasValues = false

    def pullFromFlatMapStream(): Response[B] = {
      flatMapStream.next() match {
        case Value(value) => Value(value)
        case Await        => Await
        case Halt =>
          flatMapStreamHasValues = false
          source.next() match {
            case Value(value) =>
              flatMapStream = f(value)
              flatMapStreamHasValues = true
              pullFromFlatMapStream()
            case Await => Await
            case Halt =>
              flatMapStreamHasValues = false
              sourceHasValues = false
              Halt
          }
      }
    }

    def next(): Response[B] = {
      if (flatMapStreamHasValues) {
        pullFromFlatMapStream()
      } else if (sourceHasValues) {
        source.next() match {
          case Value(value) =>
            flatMapStream = f(value)
            flatMapStreamHasValues = true
            pullFromFlatMapStream()
          case Await => Await
          case Halt =>
            sourceHasValues = false
            Halt
        }
      } else {
        Halt
      }

    }
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
  final case class Map[A, B](source: Ir[A], f: A => B) extends Ir[B] {
    def next(): Response[B] =
      source.next().map(f)
  }
  final case class Merge[A, B](left: Ir[A], right: Ir[B])
      extends Ir[Either[A, B]] {

    var leftHasHalted = false
    var rightHasHalted = false
    var pullFromLeft: Boolean = true

    def next(): Response[Either[A, B]] =
      if (leftHasHalted && rightHasHalted) Halt
      else if (leftHasHalted) {
        right.next() match {
          case Value(value) => Value(Right(value))
          case Await        => Await
          case Halt =>
            rightHasHalted = true
            Halt
        }
      } else if (rightHasHalted) {
        left.next() match {
          case Value(value) => Value(Left(value))
          case Await        => Await
          case Halt =>
            leftHasHalted = true
            Halt
        }
      } else if (pullFromLeft) {
        pullFromLeft = false
        left.next() match {
          case Value(value) => Value(Left(value))
          case Await =>
            right.next() match {
              case Value(value) => Value(Right(value))
              case Await        => Await
              case Halt =>
                rightHasHalted = true
                Await
            }
          case Halt =>
            leftHasHalted = true
            right.next() match {
              case Value(value) => Value(Right(value))
              case Await        => Await
              case Halt =>
                rightHasHalted = true
                Halt
            }
        }
      } else {
        pullFromLeft = true
        right.next() match {
          case Value(value) => Value(Right(value))
          case Await =>
            left.next() match {
              case Value(value) => Value(Left(value))
              case Await        => Await
              case Halt =>
                leftHasHalted = true
                Await
            }
          case Halt =>
            left.next() match {
              case Value(value) => Value(Left(value))
              case Await        => Await
              case Halt =>
                leftHasHalted = true
                Halt
            }
        }
      }
  }
  final case class Zip[A, B](left: Ir[A], right: Ir[B]) extends Ir[(A, B)] {
    var leftHasValues = true
    var rightHasValues = true
    var leftValue: Option[A] = None
    var rightValue: Option[B] = None

    def next(): Response[(A, B)] = {
      if (leftHasValues && rightHasValues) {
        if (leftValue.isEmpty) {
          left.next() match {
            case Value(value) => leftValue = Some(value)
            case Await        => ()
            case Halt         => leftHasValues = false
          }
        }

        if (rightValue.isEmpty) {
          right.next() match {
            case Value(value) => rightValue = Some(value)
            case Await        => ()
            case Halt         => rightHasValues = false
          }
        }

        (leftValue, rightValue) match {
          case (Some(l), Some(r)) =>
            val result = (l, r)
            leftValue = None
            rightValue = None
            Value(result)

          case _ =>
            if (leftHasValues && rightHasValues) Await
            else Halt
        }
      } else {
        Halt
      }
    }
  }
  final case class Range(start: Int, stop: Int, step: Int) extends Ir[Int] {
    var current: Int = start

    def next(): Response[Int] =
      if (start == stop) Response.halt
      else if (step > 0 && current >= stop) Response.halt
      else if (step < 0 && current <= stop) Response.halt
      else {
        val response = Response.value(current)
        current = current + step
        response
      }
  }
  case object Never extends Ir[Nothing] {
    def next(): Response[Nothing] = Response.halt
  }
  case object Waiting extends Ir[Nothing] {
    def next(): Response[Nothing] = Response.await
  }
  final case class WaitOnce() extends Ir[Nothing] {
    var shouldWait = true

    def next(): Response[Nothing] =
      if (shouldWait) {
        shouldWait = false
        Await
      } else {
        Halt
      }
  }

  // Utility methods
  def compile[A](stream: Stream[A]): Ir[A] = {
    stream match {
      case Stream.Append(left, right) =>
        Ir.Append(compile(left), compile(right))
      case Stream.Constant(value) => Ir.Constant(value)
      case Stream.Emit(values)    => Ir.Emit(values)
      case f: Stream.FlatMap[a, b] =>
        Ir.FlatMap(compile(f.source), (a: a) => compile(f.f(a)))
      // This pattern is necessary to get around a type inference bug
      case f: Stream.Filter[A] => Ir.Filter(compile(f.source), f.pred)
      case Stream.Interleave(left, right) =>
        Ir.Interleave(compile(left), compile(right))
      case Stream.Map(source, f) => Ir.Map(compile(source), f)
      case m: Stream.Merge[a, b] => Ir.Merge(compile(m.left), compile(m.right))
      case z: Stream.Zip[a, b]   => Ir.Zip(compile(z.left), compile(z.right))
      case Stream.Range(start, stop, step) => Ir.Range(start, stop, step)
      case Stream.Never                    => Ir.Never
      case Stream.Waiting                  => Ir.Waiting
      case Stream.WaitOnce                 => Ir.WaitOnce()
    }
  }
}
