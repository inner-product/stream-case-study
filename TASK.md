# Implement semigroup reduction

1. Implement `reduceSemigroup` for a `Stream[A]`, which returns a `Stream[A]` using a `Semigroup[A]` to combine all the elements in the stream.

2. Implement `foldMap` for a `Stream[A]`, which returns a `Stream[B]`, the result of mapping each element with an `A => B` function and then reducing with a `Monoid[B]`.

