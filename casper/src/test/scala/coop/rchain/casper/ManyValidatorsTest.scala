package coop.rchain.casper

import cats.Monad
import cats.effect.Sync
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.IndexedBlockDagStorage
import coop.rchain.casper.api.BlockAPI
import coop.rchain.casper.helper.BlockGenerator._
import coop.rchain.casper.helper._
import coop.rchain.casper.protocol.{BlockMessage, Bond}
import coop.rchain.metrics.{Metrics, NoopSpan, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.Validator
import coop.rchain.p2p.EffectsTestInstances.LogStub
import coop.rchain.shared.{Log, Time}
import monix.eval.Task
import monix.execution.schedulers.CanBlock
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import scala.util.Random

class ManyValidatorsTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockDagStorageFixture {
  "Get blocks" should "be processed quickly for a node with 300 validators" in {
    val blockDagStorageDir            = BlockDagStorageTestFixture.blockDagStorageDir
    val blockStoreDir                 = BlockDagStorageTestFixture.blockStorageDir
    implicit val metrics              = new Metrics.MetricsNOP[Task]()
    implicit val noopSpan: Span[Task] = NoopSpan[Task]()
    implicit val log                  = new Log.NOPLog[Task]()
    val bonds = Seq
      .fill(300) {
        val array = Array.ofDim[Byte](Validator.Length)
        Random.nextBytes(array)
        ByteString.copyFrom(array)
      }
      .map(Bond(_, 10))
    val v1 = bonds(0).validator

    val testProgram = for {
      blockStore             <- BlockDagStorageTestFixture.createBlockStorage[Task](blockStoreDir)
      blockDagStorage        <- BlockDagStorageTestFixture.createBlockDagStorage(blockDagStorageDir)
      indexedBlockDagStorage <- IndexedBlockDagStorage.create(blockDagStorage)
      genesis <- createGenesis[Task](bonds = bonds)(
                  Monad[Task],
                  Time[Task],
                  blockStore,
                  indexedBlockDagStorage
                )
      b <- createBlock[Task](Seq(genesis.blockHash), genesis, v1, bonds, bonds.map {
            case Bond(validator, _) => validator -> genesis.blockHash
          }.toMap)(Monad[Task], Time[Task], blockStore, indexedBlockDagStorage)
      _                     <- indexedBlockDagStorage.close()
      initialLatestMessages = bonds.map { case Bond(validator, _) => validator -> b }.toMap
      _ <- Sync[Task].delay {
            BlockDagStorageTestFixture.writeInitialLatestMessages(
              blockDagStorageDir.resolve("latest-messages-data"),
              blockDagStorageDir.resolve("latest-messages-crc"),
              initialLatestMessages
            )
          }
      newBlockDagStorage        <- BlockDagStorageTestFixture.createBlockDagStorage(blockDagStorageDir)
      newIndexedBlockDagStorage <- IndexedBlockDagStorage.create(newBlockDagStorage)
      dag                       <- newIndexedBlockDagStorage.getRepresentation
      tips                      <- Estimator.tips[Task](dag, genesis)
      casperEffect <- NoOpsCasperEffect[Task](
                       HashMap.empty[BlockHash, BlockMessage],
                       tips.toIndexedSeq
                     )(Sync[Task], blockStore, newIndexedBlockDagStorage)
      logEff             = new LogStub[Task]
      casperRef          <- MultiParentCasperRef.of[Task]
      _                  <- casperRef.set(casperEffect)
      cliqueOracleEffect = SafetyOracle.cliqueOracle[Task]
      result <- BlockAPI.getBlocks[Task](Some(Int.MaxValue))(
                 Monad[Task],
                 casperRef,
                 logEff,
                 cliqueOracleEffect,
                 blockStore
               )
    } yield result
    testProgram.runSyncUnsafe(1.minute)(scheduler, CanBlock.permit)
  }
}
