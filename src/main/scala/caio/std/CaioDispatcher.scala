package caio.std

import caio._
import cats.effect._
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future

class CaioDispatcher[C, L](
  context: C,
  closed: AtomicBoolean
)(
  onSuccess: (C, Option[L]) => IO[Unit] = (_: C, _: Option[L]) => IO.unit,
  onError: (Throwable, C, Option[L]) => IO[Unit] = (_: Throwable, _: C, _: Option[L]) => IO.unit
)(
  dispatcher: Dispatcher[IO],
  closeDispatcher: IO[Unit]
)
    extends Dispatcher[Caio[C, L, _]] {

  import cats.instances.vector._
  import cats.syntax.parallel._

  def unsafeClose: Caio[C, L, Unit] =
    Caio.liftIO(closeDispatcher).map(_ => closed.set(true))

  def toIO[A](fa: Caio[C, L, A]): IO[FoldCaioPure[C, L, A]] =
    Caio.foldIO[C, L, A](fa, context)

  def unsafeToFutureCancelable[A](fa: Caio[C, L, A]): (Future[A], () => Future[Unit]) =
    if (closed.get()) {
      throw new CaioDispatcherAlreadyClosedException
    } else {
      val io = IO.async[A](cb => toIO(fa).attempt.flatMap(handle(_, cb)))
      dispatcher.unsafeToFutureCancelable[A](io)
    }

  @inline private def handle[A](
    either: Either[Throwable, FoldCaioPure[C, L, A]],
    cb: Either[Throwable, A] => Unit
  ): IO[Option[IO[Unit]]] =
    either match {
      case Left(ex)                         =>
        Vector(IO(cb(Left(ex))), onError(ex, context, None)).parSequence_.as(None)
      case Right(FoldCaioSuccess(c2, l, a)) =>
        Vector(IO(cb(Right(a))), onSuccess(c2, l.map(_._1))).parSequence_.as(None)
      case Right(FoldCaioError(c2, l, ex))  =>
        Vector(IO(cb(Left(ex))), onError(ex, c2, l.map(_._1))).parSequence_.as(None)
    }
}

object CaioDispatcher {
  def apply[C, L](c: C)(onSuccess: (C, Option[L]) => IO[Unit] = (_: C, _: Option[L]) => IO.unit)(
    onError: (Throwable, C, Option[L]) => IO[Unit] = (_: Throwable, _: C, _: Option[L]) => IO.unit
  ): Resource[Caio[C, L, _], CaioDispatcher[C, L]] =
    Resource.make[Caio[C, L, _], CaioDispatcher[C, L]](Caio.liftIO(unsafeIO(c)(onSuccess)(onError)))(_.unsafeClose)

  def unsafe[C, L](c: C)(onSuccess: (C, Option[L]) => IO[Unit] = (_: C, _: Option[L]) => IO.unit)(
    onError: (Throwable, C, Option[L]) => IO[Unit] = (_: Throwable, _: C, _: Option[L]) => IO.unit
  ): CaioDispatcher[C, L] =
    unsafeIO[C, L](c)(onSuccess)(onError).unsafeRunSync()

  def unsafeIO[C, L](c: C)(onSuccess: (C, Option[L]) => IO[Unit] = (_: C, _: Option[L]) => IO.unit)(
    onError: (Throwable, C, Option[L]) => IO[Unit] = (_: Throwable, _: C, _: Option[L]) => IO.unit
  ): IO[CaioDispatcher[C, L]] =
    Dispatcher.parallel[IO].allocated.map { case (dispatcher, close) =>
      new CaioDispatcher[C, L](c, new AtomicBoolean(false))(onSuccess, onError)(dispatcher, close)
    }
}
