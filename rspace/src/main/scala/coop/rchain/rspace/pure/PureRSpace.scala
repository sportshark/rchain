package coop.rchain.rspace.pure

import cats.Id
import cats.effect.Sync
import coop.rchain.rspace.ISpace.IdISpace
import coop.rchain.rspace._

import scala.collection.SortedSet

trait PureRSpace[F[_], C, P, A, R, K] {
  def consume(
      channels: Seq[C],
      patterns: Seq[P],
      continuation: K,
      persist: Boolean,
      sequenceNumber: Int = 0,
      peek: Boolean = false
  ): F[Option[(ContResult[C, P, K], Seq[Result[R]])]]

  def install(channels: Seq[C], patterns: Seq[P], continuation: K): F[Option[(K, Seq[R])]]

  def produce(
      channel: C,
      data: A,
      persist: Boolean,
      sequenceNumber: Int = 0
  ): F[Option[(ContResult[C, P, K], Seq[Result[R]])]]

  def createCheckpoint(): F[Checkpoint]

  def reset(hash: Blake2b256Hash): F[Unit]

  def close(): F[Unit]
}

object PureRSpace {
  def apply[F[_]](implicit F: Sync[F]): PureRSpaceApplyBuilders[F] = new PureRSpaceApplyBuilders(F)

  final class PureRSpaceApplyBuilders[F[_]](val F: Sync[F]) extends AnyVal {
    def of[C, P, A, R, K](
        space: ISpace[F, C, P, A, R, K]
    )(implicit mat: Match[F, P, A, R]): PureRSpace[F, C, P, A, R, K] =
      new PureRSpace[F, C, P, A, R, K] {
        def consume(
            channels: Seq[C],
            patterns: Seq[P],
            continuation: K,
            persist: Boolean,
            sequenceNumber: Int,
            peek: Boolean = false
        ): F[Option[(ContResult[C, P, K], Seq[Result[R]])]] =
          space.consume(channels, patterns, continuation, persist, sequenceNumber, if (peek) {
            SortedSet((0 to channels.size - 1): _*)
          } else SortedSet.empty)

        def install(channels: Seq[C], patterns: Seq[P], continuation: K): F[Option[(K, Seq[R])]] =
          space.install(channels, patterns, continuation)

        def produce(
            channel: C,
            data: A,
            persist: Boolean,
            sequenceNumber: Int
        ): F[Option[(ContResult[C, P, K], Seq[Result[R]])]] =
          space.produce(channel, data, persist, sequenceNumber)

        def createCheckpoint(): F[Checkpoint] = space.createCheckpoint()

        def reset(hash: Blake2b256Hash): F[Unit] = space.reset(hash)

        def close(): F[Unit] = space.close()
      }
  }
}
