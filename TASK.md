# Implement semigroup reduction

1. Implement `reduceSemigroup` for a `Stream[A]`, which returns a `Stream[A]` using a `Semigroup[A]` to combine all the elements in the stream.

2. Implement `foldMap` for a `Stream[A]`, which returns a `Stream[B]`, the result of mapping each element with an `A => B` function and then reducing with a `Monoid[B]`.

Note that `Semigroup` and `Monoid` are provided by Cats. Import `cats.Semigroup` and `cats.Monoid`.

There are tests for these methods that you can use to check your implementation. Note that the tests won't compile until you implement the methods. Unlike other methods you have implemented, in this case we have not provided the method signature as working out the signature is part of the task.
