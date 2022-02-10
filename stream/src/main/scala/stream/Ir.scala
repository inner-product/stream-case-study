package stream

import cats.effect.IO

/** Ir stands for "Intermediate representation". It is stateful representation
  * we compile Stream into.
  */
sealed trait Ir[+A] {
  def next: IO[Response[A]]
}
object Ir {
  import Response._

  val awaitIO: IO[Response[Nothing]] =
    IO.pure(Await)

  val haltIO: IO[Response[Nothing]] =
    IO.pure(Halt)

  final case class Append[A](left: Ir[A], right: Ir[A]) extends Ir[A] {
    var leftHasValues = true
    var rightHasValues = true

    val pullFromRight: IO[Response[A]] =
      IO(rightHasValues).ifM(
        ifTrue = right.next.map(r =>
          r match {
            case v: Value[A] => v
            case Await       => Await
            case Halt =>
              rightHasValues = false
              Halt
          }
        ),
        ifFalse = haltIO
      )

    val next: IO[Response[A]] =
      IO(leftHasValues).ifM(
        ifTrue = left.next.flatMap(r =>
          r match {
            case v: Value[A] => IO.pure(v)
            case Await       => awaitIO
            case Halt =>
              leftHasValues = false
              pullFromRight
          }
        ),
        ifFalse = pullFromRight
      )
  }

  final case class Constant[A](value: A) extends Ir[A] {
    val next: IO[Response[A]] = IO.pure(Response.value(value))
  }
  final case class Emit[A](values: Iterator[A]) extends Ir[A] {
    val next: IO[Response[A]] =
      IO(
        if (values.hasNext) Response.value(values.next())
        else Response.halt
      )
  }
  final case class FlatMap[A, B](source: Ir[A], f: A => Ir[B]) extends Ir[B] {
    var sourceHasValues = true
    var flatMapStream: Ir[B] = _
    var flatMapStreamHasValues = false

    // Needs to be a def not a val as we need to delay calling
    // flatMapStream.next till it exists
    def pullFromFlatMapStream: IO[Response[B]] =
      flatMapStream.next.flatMap(r =>
        r match {
          case Value(value) => IO.pure(Value(value))
          case Await        => awaitIO
          case Halt =>
            flatMapStreamHasValues = false
            source.next.flatMap(r =>
              r match {
                case Value(value) =>
                  flatMapStream = f(value)
                  flatMapStreamHasValues = true
                  pullFromFlatMapStream
                case Await => awaitIO
                case Halt =>
                  flatMapStreamHasValues = false
                  sourceHasValues = false
                  haltIO
              }
            )
        }
      )

    val next: IO[Response[B]] =
      IO(flatMapStreamHasValues).ifM(
        ifTrue = pullFromFlatMapStream,
        ifFalse = IO(sourceHasValues).ifM(
          ifTrue = source.next.flatMap(r =>
            r match {
              case Value(value) =>
                flatMapStream = f(value)
                flatMapStreamHasValues = true
                pullFromFlatMapStream
              case Await => awaitIO
              case Halt =>
                sourceHasValues = false
                haltIO
            }
          ),
          ifFalse = haltIO
        )
      )

  }
  final case class Filter[A](source: Ir[A], pred: A => Boolean) extends Ir[A] {
    val next: IO[Response[A]] =
      source.next.map(response =>
        response match {
          case Value(value) =>
            if (pred(value)) Value(value)
            else Response.await
          case Await => Response.await
          case Halt  => Response.halt
        }
      )
  }
  final case class Interleave[A](left: Ir[A], right: Ir[A]) extends Ir[A] {
    // Which side we pull form first
    var pullFromLeft: Boolean = true

    def pull(first: Ir[A], second: Ir[A]): IO[Response[A]] =
      first.next.flatMap(r =>
        r match {
          case v: Value[A] => IO.pure(v)
          case Await =>
            second.next.map(r =>
              r match {
                case v: Value[A] => v
                case Await       => Await
                case Halt        => Await
              }
            )
          case Halt =>
            second.next.map(r =>
              r match {
                case v: Value[A] => v
                case Await       => Await
                case Halt        => Halt
              }
            )
        }
      )

    val next: IO[Response[A]] =
      IO(pullFromLeft).ifM(
        ifTrue = IO({ pullFromLeft = false }) *> pull(left, right),
        ifFalse = IO({ pullFromLeft = true }) *> pull(right, left)
      )
  }
  final case class Map[A, B](source: Ir[A], f: A => B) extends Ir[B] {
    val next: IO[Response[B]] = source.next.map(r => r.map(f))
  }
  final case class Merge[A, B](left: Ir[A], right: Ir[B])
      extends Ir[Either[A, B]] {

    var leftHasHalted = false
    var rightHasHalted = false
    var pullFromLeft: Boolean = true

    val pullLeft: IO[Response[Either[A, B]]] =
      left.next.flatMap(r =>
        r match {
          case Value(value) => IO(Value(Left(value)))
          case Await =>
            if (rightHasHalted) awaitIO
            else
              right.next.map(r =>
                r match {
                  case Value(value) => Value(Right(value))
                  case Await        => Await
                  case Halt =>
                    rightHasHalted = true
                    Await
                }
              )
          case Halt =>
            leftHasHalted = true
            if (rightHasHalted) haltIO
            else
              right.next.map(r =>
                r match {
                  case Value(value) => Value(Right(value))
                  case Await        => Await
                  case Halt =>
                    rightHasHalted = true
                    Halt
                }
              )
        }
      )

    val pullRight: IO[Response[Either[A, B]]] =
      right.next.flatMap(r =>
        r match {
          case Value(value) => IO(Value(Right(value)))
          case Await =>
            if (leftHasHalted) awaitIO
            else
              left.next.map(r =>
                r match {
                  case Value(value) => Value(Left(value))
                  case Await        => Await
                  case Halt =>
                    leftHasHalted = true
                    Await
                }
              )
          case Halt =>
            rightHasHalted = true
            if (leftHasHalted) haltIO
            else
              left.next.map(r =>
                r match {
                  case Value(value) => Value(Left(value))
                  case Await        => Await
                  case Halt =>
                    leftHasHalted = true
                    Halt
                }
              )
        }
      )

    val next: IO[Response[Either[A, B]]] =
      IO(pullFromLeft).ifM(
        ifTrue = {
          pullFromLeft = false
          if (leftHasHalted) {
            if (rightHasHalted) haltIO
            else pullRight
          } else {
            pullLeft
          }
        },
        ifFalse = {
          pullFromLeft = true
          if (rightHasHalted) {
            if (leftHasHalted) haltIO
            else pullLeft
          } else {
            pullRight
          }
        }
      )
  }
  final case class Zip[A, B](left: Ir[A], right: Ir[B]) extends Ir[(A, B)] {
    // True if the left stream is still producing values, false if it has halted
    var leftHasValues = true
    // True if the right stream is still producing values, false if it has halted
    var rightHasValues = true
    var leftValue: Option[A] = None
    var rightValue: Option[B] = None

    val pullLeft: IO[Unit] =
      IO(leftValue.isEmpty).ifM(
        ifTrue = left.next.map(r =>
          r match {
            case Value(value) => leftValue = Some(value)
            case Await        => ()
            case Halt         => leftHasValues = false
          }
        ),
        ifFalse = IO.unit
      )

    val pullRight: IO[Unit] =
      IO(rightValue.isEmpty).ifM(
        ifTrue = right.next.map(r =>
          r match {
            case Value(value) => rightValue = Some(value)
            case Await        => ()
            case Halt         => rightHasValues = false
          }
        ),
        ifFalse = IO.unit
      )

    val next: IO[Response[(A, B)]] =
      IO(leftHasValues && rightHasValues).ifM(
        ifTrue = pullLeft *> pullRight *>
          // Need to check again as pulling from left and right may have changed this condition
          IO(leftHasValues && rightHasValues).ifM(
            ifTrue = (leftValue, rightValue) match {
              case (Some(l), Some(r)) =>
                val result = (l, r)
                leftValue = None
                rightValue = None
                IO(Value(result))

              case _ => awaitIO
            },
            ifFalse = haltIO
          ),
        ifFalse = haltIO
      )
  }
  final case class Range(start: Int, stop: Int, step: Int) extends Ir[Int] {
    var current: Int = start

    val next: IO[Response[Int]] =
      IO(
        if (start == stop) Response.halt
        else if (step > 0 && current >= stop) Response.halt
        else if (step < 0 && current <= stop) Response.halt
        else {
          val response = Response.value(current)
          current = current + step
          response
        }
      )
  }
  case object Never extends Ir[Nothing] {
    val next: IO[Response[Nothing]] = haltIO
  }
  case object Waiting extends Ir[Nothing] {
    val next: IO[Response[Nothing]] = awaitIO
  }
  final case class WaitOnce() extends Ir[Nothing] {
    var shouldWait = true

    val next: IO[Response[Nothing]] =
      IO(
        if (shouldWait) {
          shouldWait = false
          Await
        } else {
          Halt
        }
      )
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
