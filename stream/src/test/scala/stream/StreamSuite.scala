package stream

import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import munit._
import org.scalacheck.Prop._

class StreamSuite extends ScalaCheckSuite {
  import Response.Value
  import Generators._

  property("emit emits all given values") {
    forAll { (values: List[Int]) =>
      val result = Stream
        .emit(values)
        .toList

      assertEquals(result, values)
    }
  }

  property("map transforms values in expected way") {
    forAll { (values: List[Int]) =>
      val result = Stream
        .emit(values)
        .map(x => x + 1)
        .toList

      assertEquals(result, values.map(x => x + 1))
    }
  }

  property("filter keeps values that match the predicate") {
    forAll { (values: List[Int]) =>
      val pred: Int => Boolean = x => x > 0
      val result = Stream
        .emit(values)
        .filter(pred)
        .toList

      assertEquals(result, values.filter(pred))
    }
  }

  property("filter chains keep values that match all predicates") {
    forAll { (values: List[Int]) =>
      val pred1: Int => Boolean = x => x > 0
      val pred2: Int => Boolean = x => x < 1000
      val result = Stream
        .emit(values)
        .filter(pred1)
        .filter(pred2)
        .toList

      assertEquals(result, values.filter(pred1).filter(pred2))
    }
  }

  property(
    "interleave emits all values from both streams in the order they occur"
  ) {
    forAll(genEvenList, genOddList) { (evens: List[Int], odds: List[Int]) =>
      val result = Stream
        .emit(evens)
        .interleave(Stream.emit(odds))
        .toList

      val (evenResult, oddResult) = result.partition(x => x % 2 == 0)

      assertEquals(evenResult, evens)
      assertEquals(oddResult, odds)
    }
  }

  property("interleave is fair, attempting to get values from both Streams") {
    forAll { (expected1: Int, expected2: Int) =>
      val stream =
        Stream
          .constant(1)
          .filter(x => x > 1)
          .interleave(Stream.emit(List(expected1, expected2)))

      val compiled = stream.compile
      val assertion = for {
        ir <- compiled
        r1 <- ir.next
        r2 <- ir.next
      } yield {
        val Value(result1) = r1
        val Value(result2) = r2

        assertEquals(result1, expected1)
        assertEquals(result2, expected2)
      }

      assertion.unsafeRunSync()
    }
  }

  property(
    "interleave pulls all values from non-empty stream if other stream halts"
  ) {
    forAll { values: List[Int] =>
      val left = Stream.emit(values).interleave(Stream.never)
      val right = Stream.never.interleave(Stream.emit(values))

      assertEquals(left.toList, values)
      assertEquals(right.toList, values)
    }
  }

  property(
    "merge emits all values from both streams in the order they occur"
  ) {
    forAll(genEvenList, genOddList) { (evens: List[Int], odds: List[Int]) =>
      val result = Stream
        .emit(evens)
        .merge(Stream.emit(odds))
        .toList

      val (evenResult, oddResult) = result.partitionMap(identity)

      assertEquals(evenResult, evens)
      assertEquals(oddResult, odds)
    }
  }

  property("merge is fair, attempting to get values from both Streams") {
    forAll { (expected1: Int, expected2: Int) =>
      val stream =
        Stream
          .constant(1)
          .filter(x => x > 1)
          .merge(Stream.emit(List(expected1, expected2)))

      val compiled = stream.compile
      val assertion = for {
        ir <- compiled
        r1 <- ir.next
        r2 <- ir.next
      } yield {
        val Value(Right(result1)) = r1
        val Value(Right(result2)) = r2

        assertEquals(result1, expected1)
        assertEquals(result2, expected2)
      }

      assertion.unsafeRunSync()
    }
  }

  test(
    "merge doesn't halt if one stream is awaiting while the other stream has halted"
  ) {
    val stream1 =
      Stream.waitOnce.append(Stream.emit(List(1, 2, 3))).merge(Stream.never)
    val result1 = stream1.toList

    assert(result1.forall(x => x.isLeft))
    assertEquals(result1.collect { case Left(x) => x }, List(1, 2, 3))

    val stream2 =
      Stream.never.merge(Stream.waitOnce.append(Stream.emit(List(1, 2, 3))))
    val result2 = stream2.toList

    assert(result2.forall(x => x.isRight))
    assertEquals(result2.collect { case Right(x) => x }, List(1, 2, 3))
  }

  property("append produces values from left and right sides in order") {
    forAll { (left: List[Int], right: List[Int]) =>
      val stream = Stream.emit(left) ++ Stream.emit(right)

      assertEquals(stream.toList, left ++ right)
    }
  }

  test("waitOnce waits once and then halts") {
    val stream = Stream.waitOnce
    val compiled = stream.compile
    val assertion = for {
      ir <- compiled
      r1 <- ir.next
      r2 <- ir.next
    } yield {
      assertEquals(r1, Response.await)
      assertEquals(r2, Response.halt)
    }

    assertion.unsafeRunSync()
  }

  property("parMapUnordered returns all values in the input") {
    forAll { (values: List[Int]) =>
      val stream = Stream.emit(values).parMapUnordered(5)(x => x)
      val result = stream.toList

      assertEquals(result.sorted, values.sorted)
    }
  }

  property("reduceSemigroup reduces values to the expected result") {
    forAll { (values: List[Int]) =>
      val stream: Stream[Int] = Stream.emit(values).reduceSemigroup
      val result = stream.toList

      // combineAll uses the monoid, not the semigroup, so the result of
      // combineAll on the empty list is a list containing the identity element.
      if (values.isEmpty) assertEquals(result, values)
      else assertEquals(result, List(values.combineAll))
    }
  }

  property("reduceSemigroup works with any type that has a semigroup") {
    forAll { (values: List[String]) =>
      val stream: Stream[String] = Stream.emit(values).reduceSemigroup
      val result = stream.toList

      // combineAll uses the monoid, not the semigroup, so the result of
      // combineAll on the empty list is a list containing the identity element.
      if (values.isEmpty) assertEquals(result, values)
      else assertEquals(result, List(values.combineAll))
    }
  }

  property("foldMap maps and then folds") {
    forAll { (values: List[Int]) =>
      val stream: Stream[Int] = Stream.emit(values).foldMap(x => -x)
      val result = stream.toList

      assertEquals(result, List(values.foldMap(x => -x)))
    }
  }

  property("foldMap uses the Monoid for the type the value is mapped into") {
    forAll { (values: List[Int]) =>
      val stream: Stream[Option[Int]] =
        Stream.emit(values).foldMap(x => Some(x))
      val result = stream.toList

      assertEquals(result, List(values.foldMap[Option[Int]](x => Some(x))))
    }
  }
}
