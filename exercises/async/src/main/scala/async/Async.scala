package async

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.Success
import scala.util.Failure

object Async extends AsyncInterface:

  /**
    * Transforms a successful asynchronous `Int` computation
    * into a `Boolean` indicating whether the number was even or not.
    * In case the given `Future` value failed, this method
    * should return a failed `Future` with the same error.
    */
  def transformSuccess(eventuallyX: Future[Int]): Future[Boolean] =
    eventuallyX
      .flatMap(value => Future.successful(value % 2 == 0)) 
      .recoverWith(reason => Future.failed(reason))
  end transformSuccess

    

  /**
    * Transforms a failed asynchronous `Int` computation into a
    * successful one returning `-1`.
    * Any non-fatal failure should be recovered.
    * In case the given `Future` value was successful, this method
    * should return a successful `Future` with the same value.
    */
  def recoverFailure(eventuallyX: Future[Int]): Future[Int] =
    eventuallyX
      .flatMap(_ => eventuallyX) 
      .recoverWith(_ => Future.successful(-1))
  end recoverFailure

  /**
    * Perform two asynchronous computation, one after the other. `makeAsyncComputation2`
    * should start ''after'' the `Future` returned by `makeAsyncComputation1` has
    * completed.
    * In case the first asynchronous computation failed, the second one should not even
    * be started.
    * The returned `Future` value should contain the successful result of the first and
    * second asynchronous computations, paired together.
    */
  def sequenceComputations[A, B](
    makeAsyncComputation1: () => Future[A],
    makeAsyncComputation2: () => Future[B]
  ): Future[(A, B)] =
    makeAsyncComputation1()
      .flatMap( 
        comp1 => makeAsyncComputation2()
          .flatMap(
            comp2 => Future.successful(comp1,comp2))
      )
  end sequenceComputations

  /**
    * Concurrently perform two asynchronous computations and pair their successful
    * result together.
    * The two computations should be started independently of each other.
    * If one of them fails, this method should return the failure.
    */
  def concurrentComputations[A, B](
    makeAsyncComputation1: () => Future[A],
    makeAsyncComputation2: () => Future[B]
  ): Future[(A, B)] = 
    makeAsyncComputation1().recoverWith(reason => Future.failed(reason)) zip makeAsyncComputation2().recoverWith(reason => Future.failed(reason))
  end concurrentComputations

  /**
    * Attempt to perform an asynchronous computation.
    * In case of failure this method should try again to make
    * the asynchronous computation so that at most `maxAttempts`
    * are eventually performed.
    */
  def insist[A](makeAsyncComputation: () => Future[A], maxAttempts: Int): Future[A] =
    makeAsyncComputation()
      .flatMap(value => Future.successful(value))
      .recoverWith(reason =>
        if (maxAttempts <= 1) {
          Future.failed(reason)
        } else {
          insist(makeAsyncComputation, maxAttempts - 1)
        }
      )
  end insist

  /**
    * Turns a callback-based API into a Future-based API
    * @return A `FutureBasedApi` that forwards calls to `computeIntAsync` to the `callbackBasedApi`
    *         and returns its result in a `Future` value
    *
    * Hint: Use a `Promise`
    */
  def futurize(callbackBasedApi: CallbackBasedApi): FutureBasedApi =
    new FutureBasedApi {
      def computeIntAsync(): Future[Int] =
        val p = Promise[Int]()
        callbackBasedApi
          .computeIntAsync(
            num => num match {
              case Success(value) => p.trySuccess(value)
              case Failure(reason) => p.tryFailure(reason)
              }
            ) 
        p.future
          .flatMap(value => Future.successful(value))
          .recoverWith(reason => Future.failed(reason))
     }
  end futurize


/**
  * Dummy example of a callback-based API
  */
trait CallbackBasedApi:
  def computeIntAsync(continuation: Try[Int] => Unit): Unit

/**
  * API similar to [[CallbackBasedApi]], but based on `Future` instead
  */
trait FutureBasedApi:
  def computeIntAsync(): Future[Int]
