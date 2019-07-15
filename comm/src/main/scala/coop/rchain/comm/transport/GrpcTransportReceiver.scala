package coop.rchain.comm.transport

import java.nio.file.Path

import scala.concurrent.duration._

import cats.implicits._

import coop.rchain.comm.{CommMetricsSource, PeerNode}
import coop.rchain.comm.protocol.routing._
import coop.rchain.comm.rp.Connect.RPConfAsk
import coop.rchain.comm.rp.ProtocolHelper
import coop.rchain.comm.transport.buffer.LimitedBuffer
import coop.rchain.metrics.Metrics
import coop.rchain.shared._
import coop.rchain.shared.PathOps.PathDelete

import io.grpc.netty.NettyServerBuilder
import io.netty.handler.ssl.SslContext
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable

object GrpcTransportReceiver {

  implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "rp.transport")

  def create(
      networkId: String,
      port: Int,
      serverSslContext: SslContext,
      maxMessageSize: Int,
      maxStreamMessageSize: Long,
      buffer: LimitedBuffer[ServerMessageWithId],
      askTimeout: FiniteDuration = 5.second,
      tempFolder: Path
  )(
      implicit scheduler: Scheduler,
      rPConfAsk: RPConfAsk[Task],
      logger: Log[Task],
      metrics: Metrics[Task],
      messageQueueMonitor: MessageQueueMonitor[Task]
  ): Task[Cancelable] =
    Task.delay {
      val service = new RoutingGrpcMonix.TransportLayer {

        def send(request: TLRequest): Task[TLResponse] =
          request.protocol
            .fold(internalServerError("protocol not available in request").pure[Task]) { protocol =>
              for {
                src <- rPConfAsk.reader(_.local)
                msg = ServerMessageWithId(System.currentTimeMillis(), Send(protocol))
                enq <- Task.delay(buffer.pushNext(msg))
                _ <- if (enq)
                      messageQueueMonitor
                        .added(msg.id, msg.msg) >> metrics.incrementCounter("enqueued.messages")
                    else metrics.incrementCounter("dropped.messages")
              } yield if (enq) ack(src) else internalServerError("message dropped")
            }

        private val circuitBreaker: StreamHandler.CircuitBreaker = streamed =>
          if (streamed.header.exists(_.networkId != networkId))
            Opened(StreamHandler.StreamError.wrongNetworkId)
          else if (streamed.readSoFar > maxStreamMessageSize)
            Opened(StreamHandler.StreamError.circuitOpened)
          else Closed

        def stream(observable: Observable[Chunk]): Task[TLResponse] = {
          import StreamHandler._
          import StreamError.StreamErrorToMessage

          handleStream(tempFolder, observable, circuitBreaker) >>= {
            case Left(error @ StreamError.Unexpected(t)) =>
              logger.error(error.message, t).as(internalServerError(error.message))
            case Left(error) =>
              logger.warn(error.message).as(internalServerError(error.message))
            case Right(streamMsg) =>
              for {
                _   <- metrics.incrementCounter("received.packets")
                msg = ServerMessageWithId(System.currentTimeMillis(), streamMsg)
                enq <- Task.delay(buffer.pushNext(msg))
                _ <- if (enq)
                      List(
                        logger.debug(s"Enqueued for handling packet ${streamMsg.path}"),
                        metrics.incrementCounter("enqueued.packets")
                      ).sequence
                    else
                      List(
                        logger.debug(s"Dropped packet ${streamMsg.path}"),
                        metrics.incrementCounter("dropped.packets"),
                        streamMsg.path.deleteSingleFile[Task]
                      ).sequence
                src <- rPConfAsk.reader(_.local)
              } yield ack(src)
          }
        }

        // TODO InternalServerError should take msg in constructor
        private def internalServerError(msg: String): TLResponse =
          TLResponse(
            TLResponse.Payload
              .InternalServerError(InternalServerError(ProtocolHelper.toProtocolBytes(msg)))
          )

        private def ack(src: PeerNode): TLResponse =
          TLResponse(
            TLResponse.Payload.Ack(Ack(Some(ProtocolHelper.header(src, networkId))))
          )
      }

      val server = NettyServerBuilder
        .forPort(port)
        .executor(scheduler)
        .maxMessageSize(maxMessageSize)
        .sslContext(serverSslContext)
        .addService(RoutingGrpcMonix.bindService(service, scheduler))
        .intercept(new SslSessionServerInterceptor(networkId))
        .build
        .start

      () => {
        server.shutdown().awaitTermination()
      }
    }
}
