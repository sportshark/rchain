package coop.rchain.rholang.interpreter

import cats.implicits._
import cats.effect.concurrent.Ref
import com.google.protobuf.ByteString
import coop.rchain.crypto.codec.Base16
import coop.rchain.crypto.hash.{Blake2b256, Blake2b512Random}
import coop.rchain.metrics.{NoopSpan, Span}
import coop.rchain.models.Expr.ExprInstance._
import coop.rchain.models._
import coop.rchain.models.TaggedContinuation.TaggedCont.ScalaBodyRef
import coop.rchain.models.Var.VarInstance.FreeVar
import coop.rchain.models.rholang.implicits._
import coop.rchain.rholang.interpreter.Runtime.{BodyRefs, RhoDispatchMap}
import coop.rchain.rholang.interpreter.accounting._
import coop.rchain.rholang.interpreter.errors.InterpreterError
import coop.rchain.rholang.interpreter.storage.implicits._
import coop.rchain.rspace.{ISpace, Match}
import coop.rchain.rspace.internal.{Datum, Row}
import monix.eval.{Coeval, Task}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

trait RegistryTester extends PersistentStoreTester {
  implicit val errorLog         = new ErrorLog[Task]()
  implicit val span: Span[Task] = NoopSpan[Task]

  private[this] def dispatchTableCreator(registry: Registry[Task]): RhoDispatchMap[Task] = {
    import coop.rchain.rholang.interpreter.Runtime.BodyRefs._
    Map(
      REG_LOOKUP                   -> registry.lookup,
      REG_LOOKUP_CALLBACK          -> registry.lookupCallback,
      REG_INSERT                   -> registry.insert,
      REG_INSERT_CALLBACK          -> registry.insertCallback,
      REG_NONCE_INSERT_CALLBACK    -> registry.nonceInsertCallback,
      REG_DELETE                   -> registry.delete,
      REG_DELETE_ROOT_CALLBACK     -> registry.deleteRootCallback,
      REG_DELETE_CALLBACK          -> registry.deleteCallback,
      REG_REGISTER_INSERT_CALLBACK -> registry.registerInsertCallback,
      REG_PUBLIC_LOOKUP            -> registry.publicLookup,
      REG_PUBLIC_REGISTER_RANDOM   -> registry.publicRegisterRandom,
      REG_PUBLIC_REGISTER_SIGNED   -> registry.publicRegisterSigned
    )
  }

  def withRegistryAndTestSpace[R](
      f: (
          ChargingReducer[Task],
          ISpace[
            Task,
            Par,
            BindPattern,
            ListParWithRandom,
            ListParWithRandom,
            TaggedContinuation
          ]
      ) => R
  ): R =
    withTestSpace(errorLog) {
      case TestFixture(space, _) =>
        val _             = errorLog.readAndClearErrorVector().runSyncUnsafe(1.second)
        implicit val cost = CostAccounting.emptyCost[Task].runSyncUnsafe(1.second)
        implicit val span = NoopSpan[Task]

        lazy val dispatchTable: RhoDispatchMap[Task] = dispatchTableCreator(registry)
        lazy val (dispatcher @ _, reducer, registry) =
          RholangAndScalaDispatcher
            .create(
              space,
              dispatchTable,
              Registry.testingUrnMap
            )
        reducer.setPhlo(Cost.UNSAFE_MAX).runSyncUnsafe(1.second)
        testInstall(space).runSyncUnsafe(1.second)
        f(reducer, space)
    }

  private val lookupPatterns = List(
    BindPattern(
      Seq(
        EVar(FreeVar(0)),
        EVar(FreeVar(1))
      ),
      freeCount = 2
    )
  )

  private val publicRegisterRandomChannels =
    List[Par](GPrivate(ByteString.copyFrom(Array[Byte](18))))
  private val publicRegisterRandomPatterns = List(
    BindPattern(
      Seq(
        // Value to be registered
        EVar(FreeVar(0)),
        // Return channel to receive URI
        EVar(FreeVar(1))
      ),
      freeCount = 2
    )
  )

  private val lookupChannels = List[Par](GPrivate(ByteString.copyFrom(Array[Byte](10))))
  private val insertPatterns = List(
    BindPattern(
      Seq(
        EVar(FreeVar(0)),
        EVar(FreeVar(1)),
        EVar(FreeVar(2))
      ),
      freeCount = 3
    )
  )

  private val insertChannels = List[Par](GPrivate(ByteString.copyFrom(Array[Byte](12))))
  private val deletePatterns = List(
    BindPattern(
      Seq(
        EVar(FreeVar(0)),
        EVar(FreeVar(1))
      ),
      freeCount = 2
    )
  )

  private val deleteChannels = List[Par](GPrivate(ByteString.copyFrom(Array[Byte](14))))

  private val publicLookupChannels = List[Par](GPrivate(ByteString.copyFrom(Array[Byte](17))))
  private val publicLookupPatterns = List(
    BindPattern(
      Seq(
        EVar(FreeVar(0)),
        EVar(FreeVar(1))
      ),
      freeCount = 2
    )
  )

  private val publicRegisterSignedPatterns = List(
    BindPattern(
      Seq(
        // Public Key
        EVar(FreeVar(0)),
        // Nonce, Value tuple
        EVar(FreeVar(1)),
        // Signature
        EVar(FreeVar(2)),
        // Return channel
        EVar(FreeVar(3))
      ),
      freeCount = 4
    )
  )
  private val publicRegisterSignedChannels = List[Par](
    GPrivate(ByteString.copyFrom(Array[Byte](19)))
  )

  def testInstall(space: Runtime.RhoISpace[Task])(implicit cost: _cost[Task]): Task[Unit] = {
    implicit val m: Match[Task, BindPattern, ListParWithRandom, ListParWithRandom] =
      matchListPar[Task]

    for {
      _ <- space.install(
            lookupChannels,
            lookupPatterns,
            TaggedContinuation(ScalaBodyRef(BodyRefs.REG_LOOKUP))
          )
      _ <- space.install(
            insertChannels,
            insertPatterns,
            TaggedContinuation(ScalaBodyRef(BodyRefs.REG_INSERT))
          )
      _ <- space.install(
            deleteChannels,
            deletePatterns,
            TaggedContinuation(ScalaBodyRef(BodyRefs.REG_DELETE))
          )
      _ <- space.install(
            publicLookupChannels,
            publicLookupPatterns,
            TaggedContinuation(ScalaBodyRef(BodyRefs.REG_PUBLIC_LOOKUP))
          )
      _ <- space.install(
            publicRegisterRandomChannels,
            publicRegisterRandomPatterns,
            TaggedContinuation(ScalaBodyRef(BodyRefs.REG_PUBLIC_REGISTER_RANDOM))
          )
      _ <- space.install(
            publicRegisterSignedChannels,
            publicRegisterSignedPatterns,
            TaggedContinuation(ScalaBodyRef(BodyRefs.REG_PUBLIC_REGISTER_SIGNED))
          )
    } yield ()
  }

}

class RegistrySpec extends FlatSpec with Matchers with RegistryTester {

  private val EvaluateTimeout = 10.seconds

  /*
    0897e9533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2
    089775e6bbe6f893b810e66615867bede6e16fcf22a5dd869bb17ca8415f0b8e
    0897ef763354e4266d31c74b7a5be55fbfeb464fe65ce56ce9ccbfd9a1fddef0
    0897a37e047d2fc591185812e4a9526ded5509544e6586092c25a17abf366ea3
   */
  val eightByteArray: Par       = GByteArray(ByteString.copyFrom(Array[Byte](0x08.toByte)))
  val ninetySevenByteArray: Par = GByteArray(ByteString.copyFrom(Array[Byte](0x97.toByte)))
  val branchName: Par = GPrivate(
    ByteString
      .copyFrom(
        Base16.unsafeDecode("d5fd3d8daf9f295aa590b37b50d5518803b4596ed6940aa42d46b1413a1bb16e")
      )
  )
  // format: off
  val rootSend: Send = Send(
    chan = Registry.registryRoot,
    data = Seq(
      Expr(EMapBody(
        ParMap(Seq(eightByteArray -> Par(exprs = Seq(Expr(ETupleBody(ETuple(Seq(GInt(1), ninetySevenByteArray, branchName))))))))))),
    persistent = false
  )
  // format: on
  val sevenFiveByteArray: Par = GByteArray(ByteString.copyFrom(Array[Byte](0x75.toByte)))
  val aThreeByteArray: Par    = GByteArray(ByteString.copyFrom(Array[Byte](0xa3.toByte)))
  val eNineByteArray: Par     = GByteArray(ByteString.copyFrom(Array[Byte](0xe9.toByte)))
  val efByteArray: Par        = GByteArray(ByteString.copyFrom(Array[Byte](0xef.toByte)))
  val emptyByteArray: Par     = GByteArray(ByteString.EMPTY)
  val branch1: Par = GByteArray(
    ByteString.copyFrom(
      Base16.unsafeDecode("533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2")
    )
  )
  val branch2: Par = GByteArray(
    ByteString.copyFrom(
      Base16.unsafeDecode("e6bbe6f893b810e66615867bede6e16fcf22a5dd869bb17ca8415f0b8e")
    )
  )
  val branch3: Par = GByteArray(
    ByteString.copyFrom(
      Base16.unsafeDecode("763354e4266d31c74b7a5be55fbfeb464fe65ce56ce9ccbfd9a1fddef0")
    )
  )
  val branch4: Par = GByteArray(
    ByteString.copyFrom(
      Base16.unsafeDecode("7e047d2fc591185812e4a9526ded5509544e6586092c25a17abf366ea3")
    )
  )
  val branchSend: Send = Send(
    chan = branchName,
    data = Seq(
      Expr(
        EMapBody(
          ParMap(
            Seq(
              emptyByteArray -> Par(
                exprs = Seq(Expr(ETupleBody(ETuple(Seq(GInt(0), emptyByteArray, GInt(7))))))
              ),
              eNineByteArray -> Par(
                exprs = Seq(Expr(ETupleBody(ETuple(Seq(GInt(0), branch1, GInt(8))))))
              ),
              sevenFiveByteArray -> Par(
                exprs = Seq(Expr(ETupleBody(ETuple(Seq(GInt(0), branch2, GInt(9))))))
              )
            )
          )
        )
      )
    ),
    persistent = false
  )

  val fullBranchSend: Send = Send(
    chan = branchName,
    data = Seq(
      Expr(
        EMapBody(
          ParMap(
            Seq(
              eNineByteArray -> Par(
                exprs = Seq(Expr(ETupleBody(ETuple(Seq(GInt(0), branch1, GInt(8))))))
              ),
              sevenFiveByteArray -> Par(
                exprs = Seq(Expr(ETupleBody(ETuple(Seq(GInt(0), branch2, GInt(9))))))
              ),
              efByteArray -> Par(
                exprs = Seq(Expr(ETupleBody(ETuple(Seq(GInt(0), branch3, GInt(10))))))
              ),
              aThreeByteArray -> Par(
                exprs = Seq(Expr(ETupleBody(ETuple(Seq(GInt(0), branch4, GInt(11))))))
              )
            )
          )
        )
      )
    ),
    persistent = false
  )

  val baseRand: Blake2b512Random = Blake2b512Random(Array.empty[Byte])

  def checkResult(
      result: Map[
        Seq[Par],
        Row[BindPattern, ListParWithRandom, TaggedContinuation]
      ],
      s: String,
      expected: Par,
      rand: Blake2b512Random
  ): Unit = {
    val resultRow      = result.get(List[Par](GString(s)))
    val sequenceNumber = resultSequenceNumber(resultRow)
    resultRow should be(
      Some(
        Row(
          List(
            Datum.create[Par, ListParWithRandom](
              GString(s),
              ListParWithRandom(Seq(expected), rand),
              false,
              sequenceNumber
            )
          ),
          List()
        )
      )
    )
  }

  // It is safe to pull out the sequence number because it can vary depending
  // on the registry execution of the produces/consumes and we are not
  // testing replay
  private def resultSequenceNumber(
      resultRow: Option[Row[BindPattern, ListParWithRandom, TaggedContinuation]]
  ) =
    resultRow.fold(0)(_.data.head.source.sequenceNumber)

  "lookup" should "recurse" in {
    val lookupString: String =
      """
      new r(`rho:registry:testing:lookup`) in {
        r!("0897".hexToBytes(), "result0") |
        r!("089775e6bbe6f893b810e66615867bede6e16fcf22a5dd869bb17ca8415f0b8e".hexToBytes(), "result1") |
        r!("0897e9533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2".hexToBytes(), "result2")
      }"""

    val lookupPar: Par = ParBuilderUtil.buildNormalizedTerm[Coeval](lookupString).value

    val completePar                     = lookupPar.addSends(rootSend, branchSend)
    implicit val rand: Blake2b512Random = baseRand.splitByte(1)
    val newRand                         = rand.splitByte(2)
    val randResult0                     = newRand.splitByte(0)
    randResult0.next; randResult0.next
    val randResult1 = newRand.splitByte(1)
    randResult1.next; randResult1.next
    val randResult2 = newRand.splitByte(2)
    randResult2.next; randResult2.next

    val result = evaluate(completePar)

    checkResult(result, "result0", GInt(7), randResult0)
    checkResult(result, "result1", GInt(9), randResult1)
    checkResult(result, "result2", GInt(8), randResult2)
  }

  "insert" should "successfully split" in {
    val insertString =
      """
        new rl(`rho:registry:testing:lookup`), ri(`rho:registry:testing:insert`), ack in {
          ri!("0897e953".hexToBytes(), 10, *ack) |
          for (@10 <- ack) { //merge 0
            rl!("0897e9533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2".hexToBytes(), *ack) |
            for (@x <- ack) { //merge 1
              @"result0"!(x) |
              rl!("0897e953".hexToBytes(), *ack) |
              for (@x <- ack) { //merge 2
                @"result1"!(x) |
                ri!("0897e9".hexToBytes(), 11, *ack) |
                for (@11 <- ack) { //merge 3
                  rl!("0897e9533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2".hexToBytes(), *ack) |
                  for (@x <- ack) { //merge4
                    @"result2"!(x) |
                    rl!("0897e953".hexToBytes(), *ack) |
                    for (@x <- ack) { //merge5
                      @"result3"!(x) |
                      rl!("0897e9".hexToBytes(), *ack) |
                      for (@x <- ack) {
                        @"result4"!(x) |
                        ri!("08bb".hexToBytes(), 12, *ack) |
                        for (@12 <- ack) {
                          rl!("0897".hexToBytes(), "result5") |
                          rl!("089775e6bbe6f893b810e66615867bede6e16fcf22a5dd869bb17ca8415f0b8e".hexToBytes(), "result6") |
                          rl!("0897e9".hexToBytes(), "result7") |
                          rl!("0897e953".hexToBytes(), "result8") |
                          rl!("0897e9533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2".hexToBytes(), "result9") |
                          rl!("08bb".hexToBytes(), "result10")
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }"""
    val insertPar: Par                      = ParBuilderUtil.buildNormalizedTerm[Coeval](insertString).value
    val completePar                         = insertPar.addSends(rootSend, branchSend)
    implicit val evalRand: Blake2b512Random = baseRand.splitByte(2)

    // Compute the random states for the results
    val resultRand = baseRand.splitByte(2)
    // The two sends setting up the initial state, and then the new.
    val newRand = resultRand.splitByte(2)
    // once, for ack.
    newRand.next()
    val insert0Rand = newRand.splitByte(0)
    // Twice for lookup, once for new name to insert.
    insert0Rand.next(); insert0Rand.next(); insert0Rand.next()
    val merge0      = Blake2b512Random.merge(Seq(newRand.splitByte(1), insert0Rand.splitByte(1)))
    val lookup0Rand = merge0.splitByte(0)
    // It takes 3 now because of the previous insert.
    lookup0Rand.next(); lookup0Rand.next(); lookup0Rand.next()
    val merge1      = Blake2b512Random.merge(Seq(merge0.splitByte(1), lookup0Rand))
    val result0Rand = merge1.splitByte(0)
    val lookup1Rand = merge1.splitByte(1)
    lookup1Rand.next(); lookup1Rand.next(); lookup1Rand.next()
    val merge2      = Blake2b512Random.merge(Seq(merge1.splitByte(2), lookup1Rand))
    val result1Rand = merge2.splitByte(0)
    val insert1Rand = merge2.splitByte(1)
    //0897e9 only takes 2 lookups (root, and 0897). It uses 1 more to split
    insert1Rand.next(); insert1Rand.next(); insert1Rand.next()
    val merge3      = Blake2b512Random.merge(Seq(merge2.splitByte(2), insert1Rand.splitByte(1)))
    val lookup2Rand = merge3.splitByte(0)
    // It takes 4 lookups now because of the second insert.
    lookup2Rand.next(); lookup2Rand.next(); lookup2Rand.next(); lookup2Rand.next()
    val merge4      = Blake2b512Random.merge(Seq(merge3.splitByte(1), lookup2Rand))
    val result2Rand = merge4.splitByte(0)
    val lookup3Rand = merge4.splitByte(1)
    lookup3Rand.next(); lookup3Rand.next(); lookup3Rand.next(); lookup3Rand.next()
    val merge5      = Blake2b512Random.merge(Seq(merge4.splitByte(2), lookup3Rand))
    val result3Rand = merge5.splitByte(0)
    val lookup4Rand = merge5.splitByte(1)
    //Only 3 lookups: root, 0897, e9
    lookup4Rand.next(); lookup4Rand.next(); lookup4Rand.next()
    val merge6      = Blake2b512Random.merge(Seq(merge5.splitByte(2), lookup4Rand))
    val result4Rand = merge6.splitByte(0)
    val insert2Rand = merge6.splitByte(1)
    // Only 2 because we perform a split at the root
    insert2Rand.next(); insert2Rand.next()
    val merge7      = Blake2b512Random.merge(Seq(merge6.splitByte(2), insert2Rand.splitByte(1)))
    val result5Rand = merge7.splitByte(0)
    result5Rand.next(); result5Rand.next(); result5Rand.next()
    val result6Rand = merge7.splitByte(1)
    result6Rand.next(); result6Rand.next(); result6Rand.next()
    val result7Rand = merge7.splitByte(2)
    result7Rand.next(); result7Rand.next(); result7Rand.next(); result7Rand.next()
    val result8Rand = merge7.splitByte(3)
    result8Rand.next(); result8Rand.next(); result8Rand.next(); result8Rand.next();
    result8Rand.next()
    val result9Rand = merge7.splitByte(4)
    result9Rand.next(); result9Rand.next(); result9Rand.next(); result9Rand.next();
    result9Rand.next()
    val result10Rand = merge7.splitByte(5)
    result10Rand.next(); result10Rand.next()

    val result = evaluate(completePar)

    checkResult(result, "result0", GInt(8), result0Rand)
    checkResult(result, "result1", GInt(10), result1Rand)
    checkResult(result, "result2", GInt(8), result2Rand)
    checkResult(result, "result3", GInt(10), result3Rand)
    checkResult(result, "result4", GInt(11), result4Rand)
    checkResult(result, "result5", GInt(7), result5Rand)
    checkResult(result, "result6", GInt(9), result6Rand)
    checkResult(result, "result7", GInt(11), result7Rand)
    checkResult(result, "result8", GInt(10), result8Rand)
    checkResult(result, "result9", GInt(8), result9Rand)
    checkResult(result, "result10", GInt(12), result10Rand)
  }

  "delete" should "successfully merge" in {
    /* The overview of this program is 1 delete, 3 lookups, 1 delete,
    2 lookups, 1 delete, 1 lookup, 1 delete */
    val deleteString =
      """
      new rl(`rho:registry:testing:lookup`), rd(`rho:registry:testing:delete`), ack in {
        rd!("0897a37e047d2fc591185812e4a9526ded5509544e6586092c25a17abf366ea3".hexToBytes(), *ack) |
        for (@11 <- ack) { //merge 0
          rl!("0897e9533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2".hexToBytes(), *ack) |
          for (@x <- ack) { //merge 1
            @"result0"!(x) |
            rl!("089775e6bbe6f893b810e66615867bede6e16fcf22a5dd869bb17ca8415f0b8e".hexToBytes(), *ack) |
            for (@x <- ack) { //merge 2
              @"result1"!(x) |
              rl!("0897ef763354e4266d31c74b7a5be55fbfeb464fe65ce56ce9ccbfd9a1fddef0".hexToBytes(), *ack) |
              for (@x <- ack) { //merge 3
                @"result2"!(x) |
                rd!("0897ef763354e4266d31c74b7a5be55fbfeb464fe65ce56ce9ccbfd9a1fddef0".hexToBytes(), *ack) |
                for (@10 <- ack) { //merge4
                  rl!("0897e9533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2".hexToBytes(), *ack) |
                  for (@x <- ack) { //merge5
                    @"result3"!(x) |
                    rl!("089775e6bbe6f893b810e66615867bede6e16fcf22a5dd869bb17ca8415f0b8e".hexToBytes(), *ack) |
                    for (@x <- ack) { //merge6
                      @"result4"!(x) |
                      rd!("089775e6bbe6f893b810e66615867bede6e16fcf22a5dd869bb17ca8415f0b8e".hexToBytes(), *ack) |
                      for (@9 <- ack) { //merge7
                        rl!("0897e9533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2".hexToBytes(), *ack) |
                        for (@x <- ack) { //merge8
                          @"result5"!(x) |
                          rd!("0897e9533fd9c5c26e7ea3fe07f99a4dbbde31eb2c59f84810d03e078e7d31c2".hexToBytes(), "result6")
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }"""
    val deletePar: Par                  = ParBuilderUtil.buildNormalizedTerm[Coeval](deleteString).value
    val completePar                     = deletePar.addSends(rootSend, fullBranchSend)
    implicit val rand: Blake2b512Random = baseRand.splitByte(3)

    val result = evaluate(completePar)

    // Compute the random states for the results
    val resultRand = baseRand.splitByte(3)
    // The two sends setting up the initial state, and then the new.
    val newRand  = resultRand.splitByte(2)
    val rootRand = resultRand.splitByte(0)
    // once, for ack.
    newRand.next()
    val delete0Rand = newRand.splitByte(0)
    // Once for root, and twice for a single iteration through the tree
    delete0Rand.next(); delete0Rand.next(); delete0Rand.next()
    val merge0      = Blake2b512Random.merge(Seq(newRand.splitByte(1), delete0Rand))
    val lookup0Rand = merge0.splitByte(0)
    lookup0Rand.next(); lookup0Rand.next();
    val merge1      = Blake2b512Random.merge(Seq(merge0.splitByte(1), lookup0Rand))
    val result0Rand = merge1.splitByte(0)
    val lookup1Rand = merge1.splitByte(1)
    lookup1Rand.next(); lookup1Rand.next();
    val merge2      = Blake2b512Random.merge(Seq(merge1.splitByte(2), lookup1Rand))
    val result1Rand = merge2.splitByte(0)
    val lookup2Rand = merge2.splitByte(1)
    lookup2Rand.next(); lookup2Rand.next();
    val merge3      = Blake2b512Random.merge(Seq(merge2.splitByte(2), lookup2Rand))
    val result2Rand = merge3.splitByte(0)
    val delete1Rand = merge3.splitByte(1)
    // Once for root, and twice for a single iteration through the tree
    delete1Rand.next(); delete1Rand.next(); delete1Rand.next()
    val merge4      = Blake2b512Random.merge(Seq(merge3.splitByte(2), delete1Rand))
    val lookup3Rand = merge4.splitByte(0)
    lookup3Rand.next(); lookup3Rand.next();
    val merge5      = Blake2b512Random.merge(Seq(merge4.splitByte(1), lookup3Rand))
    val result3Rand = merge5.splitByte(0)
    val lookup4Rand = merge5.splitByte(1)
    lookup4Rand.next(); lookup4Rand.next();
    val merge6      = Blake2b512Random.merge(Seq(merge5.splitByte(2), lookup4Rand))
    val result4Rand = merge6.splitByte(0)
    val delete2Rand = merge6.splitByte(1)
    // Once for root, and twice for a single iteration through the tree
    delete2Rand.next(); delete2Rand.next(); delete2Rand.next()
    val merge7      = Blake2b512Random.merge(Seq(merge6.splitByte(2), delete2Rand))
    val lookup5Rand = merge7.splitByte(0)
    // The last delete should have merged into the root, so it should only take
    // 1 new name.
    lookup5Rand.next();
    val merge8      = Blake2b512Random.merge(Seq(merge7.splitByte(1), lookup5Rand))
    val result5Rand = merge8.splitByte(0)
    val result6Rand = merge8.splitByte(1)
    // This is the last delete. It should take only 1 lookup, because of the
    // previous merge to root.
    result6Rand.next()

    checkResult(result, "result0", GInt(8), result0Rand)
    checkResult(result, "result1", GInt(9), result1Rand)
    checkResult(result, "result2", GInt(10), result2Rand)
    checkResult(result, "result3", GInt(8), result3Rand)
    checkResult(result, "result4", GInt(9), result4Rand)
    checkResult(result, "result5", GInt(8), result5Rand)
    checkResult(result, "result6", GInt(8), result6Rand)
    checkResult(result, "result6", GInt(8), result6Rand)
    val registryRootResult = result.get(List[Par](Registry.registryRoot))
    val sequenceNumber     = resultSequenceNumber(registryRootResult)
    registryRootResult should be(
      Some(
        Row(
          List(
            Datum.create[Par, ListParWithRandom](
              Registry.registryRoot,
              ListParWithRandom(Seq(EMapBody(ParMap(SortedParMap.empty))), rootRand),
              false,
              sequenceNumber
            )
          ),
          List()
        )
      )
    )
  }

  "Public lookup" should "decode and then call lookup" in {
    val lookupString =
      """
      new r(`rho:registry:lookup`) in {
        r!(`rho:id:bnm61w3958nhr5u6wx9yx6c4js77hcxmftc9o1yo4y9yxdu7g8bnq3`, "result0") |
        r!(`rho:id:bnmzm3i5h5hj8qyoh3ubmbu57zuqn56xrk175bw5sf6kook9bq8ny3`, "result1")
      }"""
    val lookupPar: Par                  = ParBuilderUtil.buildNormalizedTerm[Coeval](lookupString).value
    val completePar                     = lookupPar.addSends(rootSend, branchSend)
    implicit val rand: Blake2b512Random = baseRand.splitByte(4)
    val newRand                         = rand.splitByte(2)
    val randResult0                     = newRand.splitByte(0)
    randResult0.next; randResult0.next
    val randResult1 = newRand.splitByte(1)
    randResult1.next; randResult1.next

    val result = evaluate(completePar)

    checkResult(result, "result0", GInt(8), randResult0)
    checkResult(result, "result1", GInt(9), randResult1)
  }

  "Random Registry" should "use the random generator and insert" in {
    val registerString =
      """
      new rr(`rho:registry:insertArbitrary`), rl(`rho:registry:lookup`), x, y in {
        rr!(bundle+{*x}, *y) |
        for(@{uri /\ Uri} <- y) {
          @"result0"!(uri) |
          rl!(uri, "result1")
        }
      }"""
    val registerPar: Par                = ParBuilderUtil.buildNormalizedTerm[Coeval](registerString).value
    val completePar                     = registerPar.addSends(rootSend, branchSend)
    implicit val rand: Blake2b512Random = baseRand.splitByte(5)
    val newRand                         = rand.splitByte(2)
    val registeredName                  = newRand.next();
    newRand.next()
    val registerRand = newRand.splitByte(0)
    // Once for Uri and twice for temporary channels to handle the insert.
    val uriBytes = registerRand.next();
    registerRand.next(); registerRand.next()
    val insertRand = registerRand
    // Goes directly into root
    insertRand.next();
    val merge0Rand  = Blake2b512Random.merge(Seq(newRand.splitByte(1), insertRand))
    val randResult0 = merge0Rand.splitByte(0)
    val lookupRand  = merge0Rand.splitByte(1)
    lookupRand.next();
    val randResult1 = lookupRand

    val result = evaluate(completePar)

    val expectedBundle: Par =
      Bundle(GPrivate(ByteString.copyFrom(registeredName)), writeFlag = true, readFlag = false)

    val expectedUri = Registry.buildURI(uriBytes)

    checkResult(result, "result0", GUri(expectedUri), randResult0)
    checkResult(result, "result1", expectedBundle, randResult1)
  }

  "Signed Insert" should "work like plain insert if the signatures match" in {
    // Secret key:
    // 84e1aa32db02b53a4024d8a3fb460f37d30996bfa566f1df953c1c14b694b618
    // Public key:
    // 049d575d3b375ea985dfa087a3e7511c0e3903ce6042407cd83cfc22a8b8b32b78b3070169f20a1d050c78ec13d9ea7653e78119bb3963d470fa989d19ebfdf8bf
    // The signatures here are over the serialized representation of the nonce, value tuple.
    // To recreate the signatures, you can do something like the following:
    // val key = Base16.decode("<secret key goes here>")
    // val toSign: Par = ETuple(Seq(GInt(789), GString("entry")))
    // val sig = Ed25519.sign(toSign.toByteArray, key)
    val registerString =
      """
      new rr(`rho:registry:insertSigned:secp256k1`), rl(`rho:registry:lookup`), ack in {
        rr!("049d575d3b375ea985dfa087a3e7511c0e3903ce6042407cd83cfc22a8b8b32b78b3070169f20a1d050c78ec13d9ea7653e78119bb3963d470fa989d19ebfdf8bf".hexToBytes(),
            (789, "entry"),
            "3045022100ea044a4ef79dde5707fefbca9a96699372e49b0e1bbea90b51b933b2bc91d8620220167bd71626f4abb417d6a10db9fddcbe0050f9c98956419a32753b49e1549829".hexToBytes(),
            *ack) |
        for(@{uri /\ Uri} <- ack) { // merge0
          rl!(uri, *ack) |
          for(@result <- ack) { // merge1
            @"result0"!(result) |
            rr!("049d575d3b375ea985dfa087a3e7511c0e3903ce6042407cd83cfc22a8b8b32b78b3070169f20a1d050c78ec13d9ea7653e78119bb3963d470fa989d19ebfdf8bf".hexToBytes(),
              (788, "entryFail"),
              "30450221009557f072051e7157d5d09f4919cd1b16df2603d985e5971a64bf8763f2db59c502205a49c8dae5ee385adca9a3540eb580b1abedab2c24fc873edd8475a1f8ffad2c".hexToBytes(),
              *ack) |
            for(@Nil <- ack) { // merge2
              rl!(uri, *ack) |
              for(@result <- ack) { // merge3
                @"result1"!(result) |
                rr!("049d575d3b375ea985dfa087a3e7511c0e3903ce6042407cd83cfc22a8b8b32b78b3070169f20a1d050c78ec13d9ea7653e78119bb3963d470fa989d19ebfdf8bf".hexToBytes(),
                  (790, "entryReplace"),
                  "30450221009b5dcfd466a0fe6b524e05742bc7a23129394bac5ca3de730fd8fbd84b14a42a022079d65c2470d740820547d858d3176dff6cc32ac34f42dbd841e055d671a03118".hexToBytes(),
                  *ack) |
                for(@{uri2 /\ Uri} <- ack) { // merge4
                  @"result2"!(uri == uri2) |
                  rl!(uri2, *ack) |
                  for(@result <- ack) { // merge5
                    @"result3"!(result) |
                    rr!("049d575d3b375ea985dfa087a3e7511c0e3903ce6042407cd83cfc22a8b8b32b78b3070169f20a1d050c78ec13d9ea7653e78119bb3963d470fa989d19ebfdf8bf".hexToBytes(),
                      (791, "entrySigShort"),
                      "".hexToBytes(),
                      *ack) |
                    for(@Nil <- ack) { // merge6
                      rl!(uri, *ack) |
                      for(@result <- ack) { // merge7
                        @"result4"!(result) |
                        rr!("049d575d3b375ea985dfa087a3e7511c0e3903ce6042407cd83cfc22a8b8b32b78b3070169f20a1d050c78ec13d9ea7653e78119bb3963d470fa989d19ebfdf8bf".hexToBytes(),
                          (792, "entrySigFail"),
                          "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000".hexToBytes(),
                          *ack) |
                        for(@Nil <- ack) { // merge8
                          @"result6"!(uri) |
                          rl!(uri, "result5")
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }"""
    val registerPar: Par                = ParBuilderUtil.buildNormalizedTerm[Coeval](registerString).value
    val completePar                     = registerPar.addSends(rootSend, branchSend)
    implicit val rand: Blake2b512Random = baseRand.splitByte(6)
    val newRand                         = rand.splitByte(2)
    newRand.next() //ack
    val registerRand = newRand.splitByte(0)
    // Twice for temporary channels to handle the insert.
    registerRand.next(); registerRand.next()
    val insertRand = registerRand
    // Goes directly into root, so a single temp channel is allocated
    insertRand.next()
    val merge0Rand  = Blake2b512Random.merge(Seq(newRand.splitByte(1), insertRand))
    val lookup0Rand = merge0Rand.splitByte(0)
    lookup0Rand.next()
    val merge1Rand       = Blake2b512Random.merge(Seq(merge0Rand.splitByte(1), lookup0Rand))
    val result0Rand      = merge1Rand.splitByte(0)
    val registerFailRand = merge1Rand.splitByte(1)
    // Twice for temporary channels to handle the insert.
    registerFailRand.next(); registerFailRand.next()
    val insertFailRand = registerFailRand
    // Would go directly into root, so a single temp channel is allocated
    insertFailRand.next()
    val merge2Rand  = Blake2b512Random.merge(Seq(merge1Rand.splitByte(2), insertFailRand))
    val lookup1Rand = merge2Rand.splitByte(0)
    lookup1Rand.next()
    val merge3Rand          = Blake2b512Random.merge(Seq(merge2Rand.splitByte(1), lookup1Rand))
    val result1Rand         = merge3Rand.splitByte(0)
    val registerReplaceRand = merge3Rand.splitByte(1)
    // Twice for temporary channels to handle the insert.
    registerReplaceRand.next(); registerReplaceRand.next()
    val insertReplaceRand = registerReplaceRand
    // Goes directly into root, so a single temp channel is allocated
    insertReplaceRand.next()
    val merge4Rand  = Blake2b512Random.merge(Seq(merge3Rand.splitByte(2), insertReplaceRand))
    val result2Rand = merge4Rand.splitByte(0)
    val lookup3Rand = merge4Rand.splitByte(1)
    lookup3Rand.next()
    val merge5Rand           = Blake2b512Random.merge(Seq(merge4Rand.splitByte(2), lookup3Rand))
    val result3Rand          = merge5Rand.splitByte(0)
    val registerSigShortRand = merge5Rand.splitByte(1)
    // We don't allocate any temporary channels if the signature fails.
    val merge6Rand  = Blake2b512Random.merge(Seq(merge5Rand.splitByte(2), registerSigShortRand))
    val lookup4Rand = merge6Rand.splitByte(0)
    lookup4Rand.next()
    val merge7Rand          = Blake2b512Random.merge(Seq(merge6Rand.splitByte(1), lookup4Rand))
    val result4Rand         = merge7Rand.splitByte(0)
    val registerSigFailRand = merge7Rand.splitByte(1)
    val merge8Rand          = Blake2b512Random.merge(Seq(merge7Rand.splitByte(2), registerSigFailRand))
    val result6Rand         = merge8Rand.splitByte(0)
    val lookup5Rand         = merge8Rand.splitByte(1)
    lookup5Rand.next()
    val result5Rand = lookup5Rand

    val expectedUri = Registry.buildURI(
      Blake2b256
        .hash(
          Base16.unsafeDecode(
            "049d575d3b375ea985dfa087a3e7511c0e3903ce6042407cd83cfc22a8b8b32b78b3070169f20a1d050c78ec13d9ea7653e78119bb3963d470fa989d19ebfdf8bf"
          )
        )
    )

    val result = evaluate(completePar)

    checkResult(result, "result0", ETuple(List(GInt(789), GString("entry"))), result0Rand)
    checkResult(result, "result1", ETuple(List(GInt(789), GString("entry"))), result1Rand)
    checkResult(result, "result2", GBool(true), result2Rand)
    checkResult(result, "result3", ETuple(List(GInt(790), GString("entryReplace"))), result3Rand)
    checkResult(result, "result4", ETuple(List(GInt(790), GString("entryReplace"))), result4Rand)
    checkResult(result, "result5", ETuple(List(GInt(790), GString("entryReplace"))), result5Rand)
    checkResult(result, "result6", GUri(expectedUri), result6Rand)
  }

  private def evaluate(completePar: Par)(implicit rand: Blake2b512Random) =
    withRegistryAndTestSpace { (reducer, space) =>
      implicit val env = Env[Par]()
      val resultTask   = reducer.eval(completePar) >> space.toMap
      Await.result(resultTask.runToFuture, EvaluateTimeout)
    }

}
