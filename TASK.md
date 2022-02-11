# Implement parallel operations

Terminology:

Demand for a value (calls to `next`; pulls) flow upstream. Values (`Responses`) flow downstream. The most downstream point is responsible for producing the demand that drives the stream.


1. Implement `parMapUnordered` with the following signature:

   ```scala
   def parMapUnordered[B](maxConcurrent: Int)(f: A => B): Stream[B]
   ```
    
   This method is like map, except it has up to `maxConcurrent` concurrent
   mapping functions running in parallel and results are emitted downstream in
   the order in which they are available, not necessarily in the order in which
   they are produced by the upstream.

   Demand from the downstream may result in at most `maxConcurrent` demands
   upstream.


2. Implement `parMap` with the following signature:

   ```scala
   def parMap[B](maxConcurrent: Int)(f: A => B): Stream[B]
   ```
    
   This method is like `parMapUnordered` except values must be emitted
   downstream in the order they arrive from the upstream.
    
3. Implement `parInterleave`

   ```scala
   def parInterleave(that: Stream[A]): Stream[A]
   ```

   which is like `interleave` except the interleaving is concurrent. The left
   and right streams should race to produce a value.
