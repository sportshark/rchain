package coop.rchain.comm.transport

import cats.Applicative
import cats.implicits._

trait MessageQueueMonitor[F[_]] {
  def added(id: Long, msg: ServerMessage): F[Unit]
  def consumed(id: Long): F[Unit]
  def read: F[Seq[ServerMessage]]
  def size: F[Int]
}

object MessageQueueMonitor {
  def apply[F[_]](implicit M: MessageQueueMonitor[F]): MessageQueueMonitor[F] = M

  def noop[F[_]: Applicative]: MessageQueueMonitor[F] =
    new MessageQueueMonitor[F] {
      def added(id: Long, msg: ServerMessage): F[Unit] = ().pure[F]
      def consumed(id: Long): F[Unit]                  = ().pure[F]
      def read: F[Seq[ServerMessage]]                  = Seq.empty[ServerMessage].pure[F]
      def size: F[Int]                                 = 0.pure[F]
    }
}
