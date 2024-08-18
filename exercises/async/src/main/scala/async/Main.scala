package async

@main def Main =
  println(Async.transformSuccess(concurrent.Future.successful(3)))
