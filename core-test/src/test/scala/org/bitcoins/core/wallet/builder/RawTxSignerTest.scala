package org.bitcoins.core.wallet.builder

import org.bitcoins.core.crypto.{
  BaseTxSigComponent,
  WitnessTxSigComponentP2SH,
  WitnessTxSigComponentRaw
}
import org.bitcoins.core.currency.{Bitcoins, CurrencyUnits, Satoshis}
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.policy.Policy
import org.bitcoins.core.protocol.script.{
  CLTVScriptPubKey,
  CSVScriptPubKey,
  ConditionalScriptPubKey,
  EmptyScriptPubKey,
  MultiSignatureScriptPubKey,
  NonStandardScriptPubKey,
  P2PKHScriptPubKey,
  P2PKScriptPubKey,
  P2PKWithTimeoutScriptPubKey,
  P2SHScriptPubKey,
  P2SHScriptSignature,
  P2WSHWitnessV0,
  UnassignedWitnessScriptPubKey,
  WitnessCommitment,
  WitnessScriptPubKey,
  WitnessScriptPubKeyV0
}
import org.bitcoins.core.protocol.transaction.{
  BaseTransaction,
  Transaction,
  TransactionConstants,
  TransactionInput,
  TransactionOutPoint,
  TransactionOutput,
  WitnessTransaction
}
import org.bitcoins.core.script.PreExecutionScriptProgram
import org.bitcoins.core.script.constant.ScriptNumber
import org.bitcoins.core.script.crypto.HashType
import org.bitcoins.core.script.interpreter.ScriptInterpreter
import org.bitcoins.core.wallet.fee.{SatoshisPerByte, SatoshisPerVirtualByte}
import org.bitcoins.core.wallet.utxo.{
  ConditionalPath,
  InputInfo,
  InputSigningInfo,
  LockTimeInputInfo,
  ScriptSignatureParams
}
import org.bitcoins.crypto.{DoubleSha256DigestBE, ECPrivateKey}
import org.bitcoins.testkit.Implicits._
import org.bitcoins.testkit.core.gen.{CreditingTxGen, ScriptGenerators}
import org.bitcoins.testkit.util.BitcoinSAsyncTest

class RawTxSignerTest extends BitcoinSAsyncTest {
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    generatorDrivenConfigNewCode

  behavior of "RawTxSigner"

  private val (spk, privKey) = ScriptGenerators.p2pkhScriptPubKey.sampleSome

  it should "fail a transaction when the user invariants fail" in {
    val creditingOutput = TransactionOutput(CurrencyUnits.zero, spk)
    val destinations =
      Vector(TransactionOutput(Satoshis.one, EmptyScriptPubKey))
    val creditingTx = BaseTransaction(TransactionConstants.validLockVersion,
                                      Nil,
                                      Vector(creditingOutput),
                                      TransactionConstants.lockTime)
    val outPoint = TransactionOutPoint(creditingTx.txId, UInt32.zero)
    val utxo =
      ScriptSignatureParams(
        InputInfo(
          outPoint = outPoint,
          output = creditingOutput,
          redeemScriptOpt = None,
          scriptWitnessOpt = None,
          conditionalPath = ConditionalPath.NoCondition,
          hashPreImages = Vector(privKey.publicKey)
        ),
        privKey,
        HashType.sigHashAll
      )
    val utxos = Vector(utxo)
    val feeUnit = SatoshisPerVirtualByte(currencyUnit = Satoshis(1))
    val utxF = StandardNonInteractiveFinalizer.txFrom(outputs = destinations,
                                                      utxos = utxos,
                                                      feeRate = feeUnit,
                                                      changeSPK =
                                                        EmptyScriptPubKey)
    //trivially false
    val f = (_: Seq[ScriptSignatureParams[InputInfo]], _: Transaction) => false

    recoverToSucceededIf[IllegalArgumentException] {
      utxF.flatMap(utx => RawTxSigner.sign(utx, utxos, feeUnit, f))
    }
  }

  it should "fail to sign a p2pkh if we don't pass in the public key" in {
    val p2pkh = P2PKHScriptPubKey(privKey.publicKey)
    val creditingOutput = TransactionOutput(CurrencyUnits.zero, p2pkh)
    val destinations =
      Vector(TransactionOutput(Satoshis.one, EmptyScriptPubKey))
    val creditingTx = BaseTransaction(TransactionConstants.validLockVersion,
                                      Nil,
                                      Vector(creditingOutput),
                                      TransactionConstants.lockTime)
    val outPoint = TransactionOutPoint(creditingTx.txId, UInt32.zero)
    val utxo = ScriptSignatureParams(
      InputInfo(
        outPoint = outPoint,
        output = creditingOutput,
        redeemScriptOpt = None,
        scriptWitnessOpt = Some(P2WSHWitnessV0(EmptyScriptPubKey)),
        conditionalPath = ConditionalPath.NoCondition,
        hashPreImages = Vector(privKey.publicKey)
      ),
      privKey,
      HashType.sigHashAll
    )
    val utxos = Vector(utxo)

    val feeUnit = SatoshisPerVirtualByte(Satoshis.one)
    val utxF = StandardNonInteractiveFinalizer.txFrom(outputs = destinations,
                                                      utxos = utxos,
                                                      feeRate = feeUnit,
                                                      changeSPK =
                                                        EmptyScriptPubKey)

    recoverToSucceededIf[IllegalArgumentException] {
      utxF.flatMap(utx => RawTxSigner.sign(utx, utxos, feeUnit))
    }
  }

  it should "fail to sign a p2pkh if we pass in the wrong public key" in {
    val p2pkh = P2PKHScriptPubKey(privKey.publicKey)
    val creditingOutput = TransactionOutput(CurrencyUnits.zero, p2pkh)
    val destinations =
      Vector(TransactionOutput(Satoshis.one, EmptyScriptPubKey))
    val creditingTx = BaseTransaction(version =
                                        TransactionConstants.validLockVersion,
                                      inputs = Nil,
                                      outputs = Vector(creditingOutput),
                                      lockTime = TransactionConstants.lockTime)
    val outPoint =
      TransactionOutPoint(txId = creditingTx.txId, vout = UInt32.zero)
    val utxo = ScriptSignatureParams(
      InputInfo(
        outPoint = outPoint,
        output = creditingOutput,
        redeemScriptOpt = None,
        scriptWitnessOpt = Some(P2WSHWitnessV0(EmptyScriptPubKey)),
        conditionalPath = ConditionalPath.NoCondition,
        hashPreImages = Vector(privKey.publicKey)
      ),
      signer = privKey,
      hashType = HashType.sigHashAll
    )
    val utxos = Vector(utxo)

    val feeUnit = SatoshisPerVirtualByte(Satoshis.one)
    val utxF = StandardNonInteractiveFinalizer.txFrom(outputs = destinations,
                                                      utxos = utxos,
                                                      feeRate = feeUnit,
                                                      changeSPK =
                                                        EmptyScriptPubKey)

    recoverToSucceededIf[IllegalArgumentException] {
      utxF.flatMap(utx => RawTxSigner.sign(utx, utxos, feeUnit))
    }
  }

  it should "succeed to sign a cltv spk that uses a second-based locktime" in {
    val fundingPrivKey = ECPrivateKey.freshPrivateKey

    val lockTime = System.currentTimeMillis / 1000

    val cltvSPK =
      CLTVScriptPubKey(ScriptNumber(lockTime),
                       P2PKScriptPubKey(fundingPrivKey.publicKey))

    val cltvSpendingInfo = ScriptSignatureParams(
      LockTimeInputInfo(TransactionOutPoint(DoubleSha256DigestBE.empty,
                                            UInt32.zero),
                        Bitcoins.one,
                        cltvSPK,
                        ConditionalPath.NoCondition),
      Vector(fundingPrivKey),
      HashType.sigHashAll
    )

    val utxos = Vector(cltvSpendingInfo)
    val feeUnit = SatoshisPerByte(Satoshis.one)

    val utxF =
      StandardNonInteractiveFinalizer.txFrom(
        outputs = Vector(
          TransactionOutput(Bitcoins.one - CurrencyUnits.oneMBTC,
                            EmptyScriptPubKey)),
        utxos = utxos,
        feeRate = feeUnit,
        changeSPK = EmptyScriptPubKey
      )

    utxF
      .flatMap(utx => RawTxSigner.sign(utx, utxos, feeUnit))
      .map(tx => assert(tx.lockTime == UInt32(lockTime)))
  }

  it should "succeed to sign a cltv spk that uses a block height locktime" in {
    val fundingPrivKey = ECPrivateKey.freshPrivateKey

    val lockTime = 1000

    val cltvSPK =
      CLTVScriptPubKey(ScriptNumber(lockTime),
                       P2PKScriptPubKey(fundingPrivKey.publicKey))

    val cltvSpendingInfo = ScriptSignatureParams(
      LockTimeInputInfo(TransactionOutPoint(DoubleSha256DigestBE.empty,
                                            UInt32.zero),
                        Bitcoins.one,
                        cltvSPK,
                        ConditionalPath.NoCondition),
      Vector(fundingPrivKey),
      HashType.sigHashAll
    )

    val utxos = Vector(cltvSpendingInfo)
    val feeUnit = SatoshisPerByte(Satoshis.one)

    val utxF =
      StandardNonInteractiveFinalizer.txFrom(
        outputs = Vector(
          TransactionOutput(Bitcoins.one - CurrencyUnits.oneMBTC,
                            EmptyScriptPubKey)),
        utxos = utxos,
        feeRate = feeUnit,
        changeSPK = EmptyScriptPubKey
      )

    utxF
      .flatMap(utx => RawTxSigner.sign(utx, utxos, feeUnit))
      .map(tx => assert(tx.lockTime == UInt32(lockTime)))
  }

  it should "fail to sign a cltv spk that uses both a second-based and a block height locktime" in {
    val fundingPrivKey1 = ECPrivateKey.freshPrivateKey
    val fundingPrivKey2 = ECPrivateKey.freshPrivateKey

    val lockTime1 = System.currentTimeMillis / 1000
    val lockTime2 = 1000

    val cltvSPK1 =
      CLTVScriptPubKey(ScriptNumber(lockTime1),
                       P2PKScriptPubKey(fundingPrivKey1.publicKey))
    val cltvSPK2 =
      CLTVScriptPubKey(ScriptNumber(lockTime2),
                       P2PKScriptPubKey(fundingPrivKey2.publicKey))

    val cltvSpendingInfo1 = ScriptSignatureParams(
      LockTimeInputInfo(TransactionOutPoint(DoubleSha256DigestBE.empty,
                                            UInt32.zero),
                        Bitcoins.one,
                        cltvSPK1,
                        ConditionalPath.NoCondition),
      Vector(fundingPrivKey1),
      HashType.sigHashAll
    )

    val cltvSpendingInfo2 = ScriptSignatureParams(
      LockTimeInputInfo(TransactionOutPoint(DoubleSha256DigestBE.empty,
                                            UInt32.one),
                        Bitcoins.one,
                        cltvSPK2,
                        ConditionalPath.NoCondition),
      Vector(fundingPrivKey2),
      HashType.sigHashAll
    )

    val utxos = Vector(cltvSpendingInfo1, cltvSpendingInfo2)
    val feeRate = SatoshisPerByte(Satoshis.one)

    val utxF =
      StandardNonInteractiveFinalizer.txFrom(
        Vector(
          TransactionOutput(Bitcoins.one + Bitcoins.one - CurrencyUnits.oneMBTC,
                            EmptyScriptPubKey)),
        utxos,
        feeRate,
        EmptyScriptPubKey
      )

    recoverToSucceededIf[IllegalArgumentException](
      utxF.flatMap(utx => RawTxSigner.sign(utx, utxos, feeRate))
    )
  }

  def verifyScript(
      tx: Transaction,
      utxos: Vector[InputSigningInfo[InputInfo]]): Boolean = {
    val programs: Vector[PreExecutionScriptProgram] =
      tx.inputs.zipWithIndex.toVector.map {
        case (input: TransactionInput, idx: Int) =>
          val outpoint = input.previousOutput

          val creditingTx =
            utxos.find(u => u.outPoint.txId == outpoint.txId).get

          val output = creditingTx.output

          val spk = output.scriptPubKey

          val amount = output.value

          val txSigComponent = spk match {
            case witSPK: WitnessScriptPubKeyV0 =>
              val o = TransactionOutput(amount, witSPK)
              WitnessTxSigComponentRaw(tx.asInstanceOf[WitnessTransaction],
                                       UInt32(idx),
                                       o,
                                       Policy.standardFlags)
            case _: UnassignedWitnessScriptPubKey => ???
            case x @ (_: P2PKScriptPubKey | _: P2PKHScriptPubKey |
                _: P2PKWithTimeoutScriptPubKey | _: MultiSignatureScriptPubKey |
                _: WitnessCommitment | _: CSVScriptPubKey |
                _: CLTVScriptPubKey | _: ConditionalScriptPubKey |
                _: NonStandardScriptPubKey | EmptyScriptPubKey) =>
              val o = TransactionOutput(CurrencyUnits.zero, x)
              BaseTxSigComponent(tx, UInt32(idx), o, Policy.standardFlags)

            case _: P2SHScriptPubKey =>
              val p2shScriptSig =
                tx.inputs(idx).scriptSignature.asInstanceOf[P2SHScriptSignature]
              p2shScriptSig.redeemScript match {

                case _: WitnessScriptPubKey =>
                  WitnessTxSigComponentP2SH(
                    transaction = tx.asInstanceOf[WitnessTransaction],
                    inputIndex = UInt32(idx),
                    output = output,
                    flags = Policy.standardFlags)

                case _ =>
                  BaseTxSigComponent(tx,
                                     UInt32(idx),
                                     output,
                                     Policy.standardFlags)
              }
          }

          PreExecutionScriptProgram(txSigComponent)
      }
    ScriptInterpreter.runAllVerify(programs)
  }

  it should "sign a mix of spks in a tx and then have it verified" in {
    forAllAsync(CreditingTxGen.inputsAndOutputs(),
                ScriptGenerators.scriptPubKey) {
      case ((creditingTxsInfo, destinations), (changeSPK, _)) =>
        val fee = SatoshisPerVirtualByte(Satoshis(1000))
        val utxF =
          StandardNonInteractiveFinalizer.txFrom(outputs = destinations,
                                                 utxos = creditingTxsInfo,
                                                 feeRate = fee,
                                                 changeSPK = changeSPK)
        val txF = utxF.flatMap(utx =>
          RawTxSigner.sign(utx, creditingTxsInfo.toVector, fee))

        txF.map { tx =>
          assert(verifyScript(tx, creditingTxsInfo.toVector))
        }
    }
  }

  it should "sign a mix of p2sh/p2wsh in a tx and then have it verified" in {
    forAllAsync(CreditingTxGen.inputsAndOutputs(CreditingTxGen.nestedOutputs),
                ScriptGenerators.scriptPubKey) {
      case ((creditingTxsInfo, destinations), (changeSPK, _)) =>
        val fee = SatoshisPerByte(Satoshis(1000))
        val utxF =
          StandardNonInteractiveFinalizer.txFrom(outputs = destinations,
                                                 utxos = creditingTxsInfo,
                                                 feeRate = fee,
                                                 changeSPK = changeSPK)
        val txF = utxF.flatMap(utx =>
          RawTxSigner.sign(utx, creditingTxsInfo.toVector, fee))

        txF.map { tx =>
          assert(verifyScript(tx, creditingTxsInfo.toVector))
        }
    }
  }
}