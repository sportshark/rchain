package coop.rchain.rholang.interpreter.util
import coop.rchain.crypto.PublicKey
import coop.rchain.crypto.codec.Base16
import coop.rchain.models.Validator

final case class RevAddress(address: Address) {

  def toBase58: String = address.toBase58
}

object RevAddress {

  private val coinId  = "000000"
  private val version = "00"
  private val prefix  = Base16.unsafeDecode(coinId + version)

  private val tools = new AddressTools(prefix, keyLength = Validator.Length, checksumLength = 4)

  def fromPublicKey(pk: PublicKey): Option[RevAddress] =
    tools.fromPublicKey(pk).map(RevAddress(_))

  def fromEthAddress(ethAddress: String): RevAddress =
    RevAddress(tools.fromEthAddress(ethAddress))

  def parse(address: String): Either[String, RevAddress] =
    tools.parse(address).map(RevAddress(_))

  def isValid(address: String): Boolean = parse(address).isRight
}
