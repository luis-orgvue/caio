package caio

import cats.effect.IO


sealed trait Caio[A] {

  def flatMap[B](f: A => Caio[B]): Caio[B]

  def map[B](f: A => B): Caio[B]

  private[caio] def combineContext(proceedingContext:Context):Caio[A]

  private[caio] def toRes(evalContext:Context):Res[A]

  def runAsync():IO[(Either[Throwable, A], EventLog)]
}


private[caio] sealed trait Res[A] {
  def map[B](f: A => B): Res[B]
  def mapC[B](proceedingContext:Context)(f: A => B): Res[B]
  def flatMapC[B](proceedingContext:Context)(f: A => Caio[B]): Res[B]
  def combineContext(proceedingContext:Context):Res[A]
  def toIO: IO[PureRes[A]]
}

private[caio] sealed trait PureRes[A] extends Res[A] {
  def map[B](f: A => B): PureRes[B]
  def mapC[B](proceedingContext:Context)(f: A => B): PureRes[B]
  def combineContext(proceedingContext:Context):PureRes[A]
}


private[caio] final case class SuccessReturn[A] private(a:A, context:Context) extends PureRes[A] {
  def map[B](f: A => B): SuccessReturn[B] =
    SuccessReturn(f(a), context)

  def mapC[B](proceedingContext:Context)(f: A => B): SuccessReturn[B] =
    SuccessReturn(f(a), proceedingContext + context)


  def combineContext(proceedingContext: Context): PureRes[A] =
    SuccessReturn(a, proceedingContext + context)

  def toIO: IO[PureRes[A]] = IO.delay(this)

  def flatMapC[B](proceedingContext: Context)(f: A => Caio[B]): Res[B] =
    f(a).toRes(proceedingContext)

}

private[caio] final case class ErrorReturn[A] private(e:Throwable, context:Context) extends PureRes[A] {
  @inline
  private def shiftValue[B]: ErrorReturn[B] = this.asInstanceOf[ErrorReturn[B]]

  def map[B](f: A => B): ErrorReturn[B] = shiftValue[B]

  def mapC[B](proceedingContext:Context)(f: A => B): ErrorReturn[B] =
    ErrorReturn(e, proceedingContext + context)

  def combineContext(proceedingContext: Context): PureRes[A] =
    ErrorReturn(e, proceedingContext + context)

  def handleWith[B](f: Throwable => B): B = f(e)

  def toIO: IO[PureRes[A]] = IO.delay(this)

  def flatMapC[B](proceedingContext: Context)(f: A => Caio[B]): Res[B] =
    ErrorReturn(e, proceedingContext + context)

}

private[caio] final case class IOReturn[A](io:IO[PureRes[A]]) extends Res[A] {
  def mapC[B](proceedingContext:Context)(f: A => B): IOReturn[B] =
    IOReturn(io.map(_.mapC(proceedingContext)(f)))

  def map[B](f: A => B): IOReturn[B] =
    IOReturn(io.map(_.map(f)))

  def combineContext(proceedingContext: Context): Res[A] =
    IOReturn(io.map(_.combineContext(proceedingContext)))

  def mapRes[B](f: Res[A] => Res[B]): IOReturn[B] =
    IOReturn(io.flatMap(a => f(a).toIO))

  def toIO: IO[PureRes[A]] = io

  def flatMapC[B](proceedingContext: Context)(f: A => Caio[B]): Res[B] =
    IOReturn(io.flatMap{r =>
      r.flatMapC(proceedingContext)(f) match {
        case IOReturn(io2) => io2
        case pure:PureRes[B] => IO.pure(pure)

      }
    })

}

object IOReturn {
  def from[S, L, E, A](f: IO[A]): IOReturn[A] =
    IOReturn[A](f.map(SuccessReturn(_, Context.empty)))
}

private[caio] final case class CaioPure[A](a:A) extends Caio[A] {

  def map[B](f: A => B): Caio[B] =
    CaioPure(f(a))

  def flatMap[B]( f: A => Caio[B]): Caio[B] =
    f(a)

  def combineContext(proceedingContext: Context): Caio[A] =
    CaioContext(a, proceedingContext)

  private[caio] def toRes(proceedingContext:Context):Res[A] =
    SuccessReturn(a, proceedingContext)

  def runAsync():IO[(Either[Throwable, A], EventLog)] =
    IO.delay(Right(a) -> EventLog.empty)
}

private[caio] final case class CaioError[A](e:Throwable, context:Context) extends Caio[A] {

  def map[B](f: A => B):Caio[B] =
    this.asInstanceOf[Caio[B]]

  def flatMap[B](f: A => Caio[B]):Caio[B] =
    this.asInstanceOf[Caio[B]]

  private[caio] def combineContext(proceedingContext: Context) =
    CaioError(e, proceedingContext + context)

  private[caio] def toRes(proceedingContext:Context):Res[A] =
    ErrorReturn(e, proceedingContext + context)

  def runAsync():IO[(Either[Throwable, A], EventLog)] =
    IO.delay(Left(e) -> Context.getLog(context))
}



private[caio] final case class CaioContext[A](a:A, context:Context) extends Caio[A] {

  def map[B](f: A => B): Caio[B] =
    CaioPure(f(a))

  def flatMap[B](f: A => Caio[B]): Caio[B] =
    f(a).combineContext(context)

  private[caio] def combineContext(proceedingContext: Context) =
    CaioContext(a, proceedingContext + context)

  private[caio] def toRes(proceedingContext:Context):Res[A] =
    SuccessReturn(a, proceedingContext + context)

  def runAsync(): IO[(Either[Throwable, A], EventLog)] =
    IO.delay(Right(a) -> Context.getLog(context))

}


private[caio] final case class CaioKleisli[A](func:Context => Res[A]) extends Caio[A] {

  def flatMap[B](f: A => Caio[B]):Caio[B] =
    CaioKleisli(c => func(c).flatMapC(c)(f))


  def map[B](f: A => B):Caio[B] =
    CaioKleisli { c => func(c).map(f) }

  private[caio] def combineContext(proceedingContext: Context) =
    CaioKleisli(func.andThen(_.combineContext(proceedingContext)))

  private[caio] def toRes(evalContext:Context):Res[A] =
    func(evalContext)

  def runAsync(): IO[(Either[Throwable, A], EventLog)] =
    IO.delay(func(Context.empty).toIO.map{
      case ErrorReturn(e, c) =>
        Left(e) -> Context.getLog(c)
      case SuccessReturn(a, c) =>
        Right(a) -> Context.getLog(c)
    }).flatMap(identity)
}

