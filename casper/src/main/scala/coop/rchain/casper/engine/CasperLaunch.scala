package coop.rchain.casper.engine

import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.Monad
import coop.rchain.blockstorage.{BlockDagStorage, BlockStore}
import coop.rchain.casper._
import coop.rchain.casper.LastApprovedBlock.LastApprovedBlock
import coop.rchain.casper.MultiParentCasperRef.MultiParentCasperRef
import coop.rchain.casper.util.comm._
import EngineCell._
import coop.rchain.blockstorage.util.io.IOError.RaiseIOError
import coop.rchain.casper.genesis.Genesis
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.catscontrib.TaskContrib
import coop.rchain.comm.discovery.NodeDiscovery
import coop.rchain.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import coop.rchain.comm.transport.TransportLayer
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.shared._
import monix.eval.Task
import monix.execution.Scheduler

object CasperLaunch {

  class CasperInit[F[_]](
      val conf: CasperConf,
      val tracing: Boolean
  )
  def apply[F[_]: LastApprovedBlock: Metrics: Span: BlockStore: ConnectionsCell: NodeDiscovery: TransportLayer: RPConfAsk: SafetyOracle: LastFinalizedBlockCalculator: Sync: Concurrent: Time: Log: EventLog: MultiParentCasperRef: BlockDagStorage: EngineCell: EnvVars: RaiseIOError: RuntimeManager](
      init: CasperInit[F],
      toTask: F[_] => Task[_]
  )(implicit scheduler: Scheduler): F[Unit] =
    BlockStore[F].getApprovedBlock map {
      case Some(approvedBlock) =>
        val msg    = "Found ApprovedBlock in storage, reconnecting to existing network"
        val action = connectToExistingNetwork[F](approvedBlock, init)
        (msg, action)
      case None if (init.conf.approveGenesis) =>
        val msg    = "ApprovedBlock not found in storage, taking part in ceremony as genesis validator"
        val action = connectAsGenesisValidator[F](init)
        (msg, action)
      case None if (init.conf.createGenesis) =>
        val msg =
          "ApprovedBlock not found in storage, taking part in ceremony as ceremony master"
        val action = initBootstrap[F](init, toTask, init.tracing)
        (msg, action)
      case None =>
        val msg    = "ApprovedBlock not found in storage, connecting to existing network"
        val action = connectAndQueryApprovedBlock[F](init)
        (msg, action)
    } >>= {
      case (msg, action) => Log[F].info(msg) >> action
    }

  def connectToExistingNetwork[F[_]: LastApprovedBlock: Metrics: Span: BlockStore: ConnectionsCell: NodeDiscovery: TransportLayer: RPConfAsk: SafetyOracle: LastFinalizedBlockCalculator: Concurrent: Time: Log: EventLog: MultiParentCasperRef: BlockDagStorage: EngineCell: EnvVars: RuntimeManager](
      approvedBlock: ApprovedBlock,
      init: CasperInit[F]
  ): F[Unit] =
    for {
      validatorId <- ValidatorIdentity.fromConfig[F](init.conf)
      genesis     = approvedBlock.candidate.flatMap(_.block).get
      casper <- MultiParentCasper
                 .hashSetCasper[F](validatorId, genesis, init.conf.shardId)
      _ <- Engine
            .transitionToRunning[F](casper, approvedBlock, CommUtil.sendForkChoiceTipRequest[F])
    } yield ()

  def connectAsGenesisValidator[F[_]: Monad: Sync: Metrics: Span: LastApprovedBlock: Time: Concurrent: MultiParentCasperRef: Log: EventLog: RPConfAsk: BlockStore: ConnectionsCell: TransportLayer: SafetyOracle: LastFinalizedBlockCalculator: BlockDagStorage: EngineCell: EnvVars: RaiseIOError: RuntimeManager](
      init: CasperInit[F]
  ): F[Unit] =
    for {
      timestamp <- init.conf.deployTimestamp.fold(Time[F].currentMillis)(_.pure[F])
      bonds <- Genesis.getBonds[F](
                init.conf.bondsFile,
                init.conf.genesisPath.resolve("bonds.txt"),
                init.conf.numValidators,
                init.conf.genesisPath
              )
      validatorId <- ValidatorIdentity.fromConfig[F](init.conf)
      bap = new BlockApproverProtocol(
        validatorId.get,
        timestamp,
        bonds,
        init.conf.minimumBond,
        init.conf.maximumBond,
        init.conf.requiredSigs
      )
      _ <- EngineCell[F].set(
            new GenesisValidator(validatorId.get, init.conf.shardId, bap)
          )
    } yield ()

  def initBootstrap[F[_]: Monad: Sync: LastApprovedBlock: Time: MultiParentCasperRef: Log: EventLog: RPConfAsk: BlockStore: ConnectionsCell: TransportLayer: Concurrent: Metrics: Span: SafetyOracle: LastFinalizedBlockCalculator: BlockDagStorage: EngineCell: EnvVars: RaiseIOError: RuntimeManager](
      init: CasperInit[F],
      toTask: F[_] => Task[_],
      tracing: Boolean
  )(implicit scheduler: Scheduler): F[Unit] =
    for {
      genesis <- Genesis.fromInputFiles[F](
                  init.conf.bondsFile,
                  init.conf.numValidators,
                  init.conf.genesisPath,
                  init.conf.walletsFile,
                  init.conf.minimumBond,
                  init.conf.maximumBond,
                  init.conf.shardId,
                  init.conf.deployTimestamp
                )
      validatorId <- ValidatorIdentity.fromConfig[F](init.conf)
      abp <- ApproveBlockProtocol
              .of[F](
                genesis,
                init.conf.requiredSigs,
                init.conf.approveGenesisDuration,
                init.conf.approveGenesisInterval
              )
      // TODO OMG Fix, use Concurrent+!11
      _ <- Sync[F].delay {
            val _ = toTask(
              GenesisCeremonyMaster
                .approveBlockInterval[F](
                  init.conf.approveGenesisInterval,
                  init.conf.shardId,
                  validatorId
                )
            ).executeWithOptions(TaskContrib.enableTracing(tracing)).forkAndForget.runToFuture
            ().pure[F]
          }
      _ <- EngineCell[F].set(new GenesisCeremonyMaster[F](abp))
    } yield ()

  def connectAndQueryApprovedBlock[F[_]: Monad: Sync: LastApprovedBlock: Time: MultiParentCasperRef: Log: EventLog: RPConfAsk: BlockStore: ConnectionsCell: TransportLayer: Metrics: Span: Concurrent: SafetyOracle: LastFinalizedBlockCalculator: BlockDagStorage: EnvVars: EngineCell: RuntimeManager](
      init: CasperInit[F]
  ): F[Unit] =
    for {
      validators  <- CasperConf.parseValidatorsFile[F](init.conf.knownValidatorsFile)
      validatorId <- ValidatorIdentity.fromConfig[F](init.conf)
      _ <- Engine.tranistionToInitializing(
            init.conf.shardId,
            validatorId,
            validators,
            CommUtil.requestApprovedBlock[F]
          )
    } yield ()
}
