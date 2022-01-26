package stream

import org.scalacheck.Gen

object Generators {
  // More efficient to generate even numbers directly this way than filter out odd numbers
  val genEvenInts: Gen[Int] = Gen.choose(-1000, 1000).map(x => x * 2)
  val genOddInts: Gen[Int] = Gen.choose(-1000, 1000).map(x => x * 2 + 1)

  val genEvenList: Gen[List[Int]] = Gen.listOf(genEvenInts)
  val genOddList: Gen[List[Int]] = Gen.listOf(genOddInts)
}
