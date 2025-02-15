package coop.rchain.rholang.interpreter.storage

import cats.FlatMap
import cats.implicits._
import coop.rchain.models.TaggedContinuation.TaggedCont.ParBody
import coop.rchain.models._
import coop.rchain.models.rholang.implicits._
import coop.rchain.rholang.interpreter.PrettyPrinter
import coop.rchain.rholang.interpreter.Runtime.RhoISpace
import coop.rchain.rspace.internal.{Datum, Row, WaitingContinuation}
import coop.rchain.rspace.trace.{Consume, Produce}
import scala.collection.SortedSet

object StoragePrinter {

  def prettyPrint[F[_]: FlatMap](space: RhoISpace[F]): F[String] =
    for {
      mapped <- space.toMap
      pars = mapped.map {
        case (
            channels: Seq[Par],
            row: Row[BindPattern, ListParWithRandom, TaggedContinuation]
            ) => {
          row match {
            case Row(Nil, Nil) =>
              Par()
            case Row(data: Seq[Datum[ListParWithRandom]], Nil) =>
              toSends(data)(channels)
            case Row(
                Nil,
                wks: Seq[WaitingContinuation[BindPattern, TaggedContinuation]]
                ) =>
              toReceive(wks)(channels)
            case Row(
                data: Seq[Datum[ListParWithRandom]],
                wks: Seq[WaitingContinuation[BindPattern, TaggedContinuation]]
                ) =>
              toSends(data)(channels) ++ toReceive(wks)(channels)
          }
        }
      }.toList
    } yield {
      if (pars.isEmpty) {
        "The space is empty. Note that top level terms that are not sends or receives are discarded."
      } else {
        val par = pars.reduce { (p1: Par, p2: Par) =>
          p1 ++ p2
        }
        PrettyPrinter().buildString(par)
      }
    }

  private[this] def toSends(data: Seq[Datum[ListParWithRandom]])(channels: Seq[Par]): Par = {
    val sends: Seq[Send] = data.flatMap {
      case Datum(as: ListParWithRandom, persist: Boolean, _: Produce) =>
        channels.map { channel =>
          Send(channel, as.pars, persist)
        }
    }
    sends.foldLeft(Par()) { (acc: Par, send: Send) =>
      acc.prepend(send)
    }
  }

  private[this] def toReceive(
      wks: Seq[WaitingContinuation[BindPattern, TaggedContinuation]]
  )(channels: Seq[Par]): Par = {
    val receives: Seq[Receive] = wks.map {
      case WaitingContinuation(
          patterns: Seq[BindPattern],
          continuation: TaggedContinuation,
          persist: Boolean,
          peeks: SortedSet[Int],
          _: Consume
          ) =>
        val receiveBinds: Seq[ReceiveBind] = (channels zip patterns).map {
          case (channel, pattern) =>
            ReceiveBind(
              pattern.patterns,
              channel,
              pattern.remainder,
              pattern.freeCount
            )
        }
        continuation.taggedCont match {
          case ParBody(p) =>
            Receive(
              receiveBinds,
              p.body,
              persist,
              peeks.nonEmpty,
              patterns.map(_.freeCount).sum
            )
          case _ => Receive(receiveBinds, Par.defaultInstance, persist)
        }
    }
    receives.foldLeft(Par()) { (acc: Par, receive: Receive) =>
      acc.prepend(receive)
    }
  }
}
