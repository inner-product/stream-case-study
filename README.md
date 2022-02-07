# Stream Case Study


## Benchmarks

To run them from within sbt:

``` sh
project benchmarks
Jmh / run
```

If the benchmarks take too long (they probably will) you can try

``` sh
Jmh / run -i 3 -wi 1 -f1 -t1
```

