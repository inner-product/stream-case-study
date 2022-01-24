package stream

import munit._
import org.scalacheck.Prop._

class StreamSuite extends ScalaCheckSuite {
  property("emit emits all given values") {
    forAll { (values: List[Int]) =>
      val result = Stream
        .emit(values.iterator)
        .foldLeft(List.empty[Int])((accum, elt) => elt :: accum)
      assertEquals(result.reverse, values)
    }
  }

  property("map transforms values in expected way") {
    forAll { (values: List[Int]) =>
      val result = Stream
        .emit(values.iterator)
        .map(x => x + 1)
        .foldLeft(List.empty[Int])((accum, elt) => elt :: accum)
      assertEquals(result.reverse, values.map(x => x + 1))
    }
  }
}
