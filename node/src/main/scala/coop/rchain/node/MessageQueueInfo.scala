package coop.rchain.node

import cats.effect.Sync
import cats.implicits._

import coop.rchain.comm.protocol.routing.Protocol
import coop.rchain.comm.rp.ProtocolHelper
import coop.rchain.comm.transport._

import org.http4s.HttpRoutes

object MessageQueueInfo {

  final case class QueueElement(
      messageType: String,
      typeId: String,
      sender: String
  )

  def queue[F[_]: Sync: MessageQueueMonitor]: F[Seq[QueueElement]] =
    MessageQueueMonitor[F].read.map(_.map {
      case Send(msg: Protocol) =>
        QueueElement(
          "Protocol",
          msg.message.getClass.getSimpleName,
          ProtocolHelper.sender(msg).fold("")(_.toString)
        )
      case StreamMessage(sender, typeId, _, _, _) =>
        QueueElement(
          "StreamMessage",
          typeId,
          sender.toString
        )
    })

  def service[F[_]: Sync: MessageQueueMonitor]: HttpRoutes[F] = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe.CirceEntityEncoder._

    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      case GET -> Root => Ok(queue.map(_.asJson))
    }
  }
}
