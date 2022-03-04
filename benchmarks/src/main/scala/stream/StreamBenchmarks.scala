package stream

import org.openjdk.jmh.annotations._

class StreamBenchmarks {
  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  def multipleFilters(): Unit = {
    Stream
      .emit(Vector.range(0, 1000000))
      .filter(x => x > 1000)
      .filter(x => x < 10000)
      .foldLeft(0)(_ + _)
    ()
  }

}
