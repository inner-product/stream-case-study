package stream

import cats.effect._
import cats.effect.std._
import cats.syntax.all._

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

  final case class Append[A](
      left: Ir[A],
      right: Ir[A]
  ) extends Ir[A] {
    val next: IO[Response[A]] =
      left.next.flatMap(r =>
        r match {
          case v: Value[A] => IO.pure(v)
          case Await       => awaitIO
          case Halt =>
            right.next.map(r =>
              r match {
                case v: Value[A] => v
                case Await       => Await
                case Halt        => Halt
              }
            )
        }
      )
  }

  final case class Constant[A](value: A) extends Ir[A] {
    val next: IO[Response[A]] = IO.pure(Response.value(value))
  }

  /** Semaphore is to avoid concurrenct access to the Iterator. It must have 1
    * permit, allowing a single fiber to access the Iterator at any one time.
    */
  final case class Emit[A](values: Iterator[A], semaphore: Semaphore[IO])
      extends Ir[A] {
    val next: IO[Response[A]] =
      semaphore.acquire *>
        IO(
          if (values.hasNext) Response.value(values.next())
          else Response.halt
        ) <* semaphore.release
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

  /** pullFromLeft stores which side we pull from first */
  final case class Interleave[A](
      left: Ir[A],
      right: Ir[A],
      pullFromLeft: Ref[IO, Boolean]
  ) extends Ir[A] {
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
      pullFromLeft
        .getAndUpdate(pfl => !pfl)
        .ifM(
          ifTrue = pull(left, right),
          ifFalse = pull(right, left)
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

  final case class ParMapUnordered[A, B](
      source: Ir[A],
      maxConcurrent: Int,
      f: A => B,
      start: CountDownLatch[IO],
      queue: Queue[IO, Response[B]]
  ) extends Ir[B] {
    val next: IO[Response[B]] =
      start.release *> queue.take
  }
  object ParMapUnordered {
    def mapWorker[A, B](
        start: CountDownLatch[IO],
        source: Queue[IO, Response[A]],
        f: A => B,
        sink: Queue[IO, Response[B]],
        stop: CyclicBarrier[IO]
    ): IO[FiberIO[Unit]] = {
      val act: IO[Unit] =
        source.take
          .flatMap(r =>
            r match {
              case Value(value) =>
                sink.offer(Response.value(f(value)))
              case Await => sink.offer(Await)
              case Halt  => stop.await *> sink.offer(Halt)
            }
          )
          .foreverM

      (start.await *> act).start
    }

    def upstreamWorker[A](
        start: CountDownLatch[IO],
        source: Ir[A],
        queue: Queue[IO, Response[A]]
    ): IO[FiberIO[Unit]] = {
      val act: IO[Unit] =
        source.next
          .flatMap(r =>
            r match {
              case v: Value[A] => queue.offer(v)
              case Await       => queue.offer(Await)
              case Halt        => queue.offer(Halt).foreverM
            }
          )
          .foreverM

      (start.await *> act).start
    }

    def apply[A, B](source: Ir[A], maxConcurrent: Int, f: A => B): IO[Ir[B]] =
      for {
        start <- CountDownLatch[IO](1)
        upstream <- Queue.bounded[IO, Response[A]](maxConcurrent)
        downstream <- Queue.bounded[IO, Response[B]](maxConcurrent)
        stop <- CyclicBarrier[IO](maxConcurrent)
        _ <- upstreamWorker(start, source, upstream)
        _ <- List
          .fill(maxConcurrent)(mapWorker(start, upstream, f, downstream, stop))
          .sequence
      } yield ParMapUnordered(source, maxConcurrent, f, start, downstream)
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
  final case class Range(
      start: Int,
      stop: Int,
      step: Int,
      current: Ref[IO, Int]
  ) extends Ir[Int] {
    val next: IO[Response[Int]] =
      current.modify(c =>
        if (start == stop) (c, Response.halt)
        else if (step > 0 && c >= stop) (c, Response.halt)
        else if (step < 0 && c <= stop) (c, Response.halt)
        else {
          val response = Response.value(c)
          val newC = c + step
          (newC, response)
        }
      )
  }
  case object Never extends Ir[Nothing] {
    val next: IO[Response[Nothing]] = haltIO
  }
  case object Waiting extends Ir[Nothing] {
    val next: IO[Response[Nothing]] = awaitIO
  }
  final case class WaitOnce(shouldWait: Ref[IO, Boolean]) extends Ir[Nothing] {
    val next: IO[Response[Nothing]] =
      shouldWait.modify(sw =>
        if (sw) (false, Await)
        else (false, Halt)
      )
  }

  // "Smart" constructors which supply private dependencies

  def append[A](left: Ir[A], right: Ir[A]): IO[Ir[A]] =
    IO.pure(Append(left, right))

  def constant[A](value: A): IO[Ir[A]] =
    IO.pure(Constant(value))

  def emit[A](values: Iterator[A]): IO[Ir[A]] =
    Semaphore[IO](1).map(semaphore => Emit(values, semaphore))

  def filter[A](source: Ir[A], pred: A => Boolean): IO[Ir[A]] =
    IO(Filter(source, pred))

  def interleave[A](left: Ir[A], right: Ir[A]): IO[Ir[A]] =
    Ref.of[IO, Boolean](true).map(ref => Interleave(left, right, ref))

  def map[A, B](source: Ir[A], f: A => B): IO[Ir[B]] =
    IO(Map(source, f))

  def range(start: Int, stop: Int, step: Int): IO[Ir[Int]] =
    Ref.of[IO, Int](start).map(ref => Range(start, stop, step, ref))

  def merge[A, B](left: Ir[A], right: Ir[B]): IO[Ir[Either[A, B]]] =
    IO(Merge(left, right))

  val never: IO[Ir[Nothing]] = IO.pure(Never)

  def parMapUnordered[A, B](
      source: Ir[A],
      maxConcurrent: Int,
      f: A => B
  ): IO[Ir[B]] =
    ParMapUnordered(source, maxConcurrent, f)

  val waiting: IO[Ir[Nothing]] = IO.pure(Waiting)

  val waitOnce: IO[Ir[Nothing]] =
    Ref.of[IO, Boolean](true).map(ref => WaitOnce(ref))

  def zip[A, B](left: Ir[A], right: Ir[B]): IO[Ir[(A, B)]] =
    IO(Zip(left, right))

  /** Convert a Stream to Ir */
  def compile[A](stream: Stream[A]): IO[Ir[A]] = {
    stream match {
      case Stream.Append(left, right) =>
        for {
          l <- compile(left)
          r <- compile(right)
          i <- append(l, r)
        } yield i

      case Stream.Constant(value) => constant(value)
      case Stream.Emit(values)    => emit(values)

      // This pattern is necessary to get around a type inference bug
      case f: Stream.Filter[A] =>
        compile(f.source).flatMap(s => filter(s, f.pred))

      case Stream.Interleave(left, right) =>
        for {
          l <- compile(left)
          r <- compile(right)
          i <- interleave(l, r)
        } yield i

      case Stream.Map(source, f) => compile(source).flatMap(s => map(s, f))

      case m: Stream.Merge[a, b] =>
        for {
          l <- compile(m.left)
          r <- compile(m.right)
          i <- merge(l, r)
        } yield i

      case Stream.ParMapUnordered(source, maxConcurrent, f) =>
        for {
          s <- compile(source)
          i <- parMapUnordered(s, maxConcurrent, f)
        } yield i

      case z: Stream.Zip[a, b] =>
        for {
          l <- compile(z.left)
          r <- compile(z.right)
          i <- zip(l, r)
        } yield i

      case Stream.Range(start, stop, step) => Ir.range(start, stop, step)
      case Stream.Never                    => never
      case Stream.Waiting                  => waiting
      case Stream.WaitOnce                 => waitOnce
    }
  }
}
