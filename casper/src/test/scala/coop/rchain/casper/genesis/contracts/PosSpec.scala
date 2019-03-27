package coop.rchain.casper.genesis.contracts
import coop.rchain.casper.helper.RhoSpec
import coop.rchain.rholang.build.CompiledRholangSource

import scala.concurrent.duration._

class PosSpec
    extends RhoSpec(CompiledRholangSource("PosTest.rho"), Seq(StandardDeploys.pos), 10.seconds)
