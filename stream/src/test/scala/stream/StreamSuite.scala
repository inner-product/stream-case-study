package stream

import munit._
import org.scalacheck.Prop._

class StreamSuite extends ScalaCheckSuite {
  import Response.Value
  import Generators._

  property("emit emits all given values") {
    forAll { (values: List[Int]) =>
      val result = Stream
        .emit(values.iterator)
        .toList

      assertEquals(result, values)
    }
  }

  property("map transforms values in expected way") {
    forAll { (values: List[Int]) =>
      val result = Stream
        .emit(values.iterator)
        .map(x => x + 1)
        .toList

      assertEquals(result, values.map(x => x + 1))
    }
  }

  property("filter keeps values that match the predicate") {
    forAll { (values: List[Int]) =>
      val pred: Int => Boolean = x => x > 0
      val result = Stream
        .emit(values.iterator)
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
        .emit(values.iterator)
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
        .emit(evens.iterator)
        .interleave(Stream.emit(odds.iterator))
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
          .interleave(Stream.emit(List(expected1, expected2).iterator))

      val ir = stream.compile
      val Value(result1) = ir.next()
      val Value(result2) = ir.next()

      assertEquals(result1, expected1)
      assertEquals(result2, expected2)
    }
  }

  property(
    "interleave pulls all values from non-empty stream if other stream halts"
  ) {
    forAll { values: List[Int] =>
      val left = Stream.emit(values.iterator).interleave(Stream.never)
      val right = Stream.never.interleave(Stream.emit(values.iterator))

      assertEquals(left.toList, values)
      assertEquals(right.toList, values)
    }
  }

  property(
    "merge emits all values from both streams in the order they occur"
  ) {
    forAll(genEvenList, genOddList) { (evens: List[Int], odds: List[Int]) =>
      val result = Stream
        .emit(evens.iterator)
        .merge(Stream.emit(odds.iterator))
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
          .merge(Stream.emit(List(expected1, expected2).iterator))

      val ir = stream.compile
      val Value(Right(result1)) = ir.next()
      val Value(Right(result2)) = ir.next()

      assertEquals(result1, expected1)
      assertEquals(result2, expected2)
    }
  }

  property("append produces values from left and right sides in order") {
    forAll { (left: List[Int], right: List[Int]) =>
      val stream = Stream.emit(left.iterator) ++ Stream.emit(right.iterator)

      assertEquals(stream.toList, left ++ right)
    }
  }
}
