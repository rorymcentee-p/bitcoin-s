package org.bitcoins.rpc.v20

import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.WalletFlag
import org.bitcoins.commons.jsonmodels.bitcoind._
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.gcs.{BlockFilter, FilterType}
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.rpc.client.common.BitcoindVersion
import org.bitcoins.rpc.client.v20.BitcoindV20RpcClient
import org.bitcoins.testkit.rpc.BitcoindFixturesFundedCachedV20

import java.io.File
import java.nio.file.Files
import scala.concurrent.Future

class BitcoindV20RpcClientTest extends BitcoindFixturesFundedCachedV20 {

  behavior of "BitcoindV20RpcClient"

  it should "be able to start a V20 bitcoind instance" in {
    client: BitcoindV20RpcClient =>
      for {
        v <- client.version
      } yield assert(v == BitcoindVersion.V20)
  }

  it should "get a block filter given a block hash" in {
    client: BitcoindV20RpcClient =>
      for {
        blocks <- client.getNewAddress.flatMap(client.generateToAddress(1, _))
        blockFilter <- client.getBlockFilter(blocks.head, FilterType.Basic)

        block <- client.getBlockRaw(blocks.head)
        txs <- Future.sequence(
          block.transactions
            .filterNot(_.isCoinbase)
            .map(tx => client.getTransaction(tx.txIdBE)))

        prevFilter <- client.getBlockFilter(
          block.blockHeader.previousBlockHashBE,
          FilterType.Basic)
      } yield {
        val pubKeys = txs.flatMap(_.hex.outputs.map(_.scriptPubKey)).toVector
        val filter = BlockFilter(block, pubKeys)
        assert(filter.hash == blockFilter.filter.hash)
        assert(
          blockFilter.header == filter
            .getHeader(prevFilter.header.flip)
            .hash
            .flip)
      }
  }

  it should "be able to get the balances" in { client: BitcoindV20RpcClient =>
    for {
      immatureBalance <- client.getBalances
      _ <- client.getNewAddress.flatMap(client.generateToAddress(1, _))
      newImmatureBalance <- client.getBalances
    } yield {
      val blockReward = 50
      assert(immatureBalance.mine.immature.toBigDecimal >= 0)
      assert(
        immatureBalance.mine.trusted.toBigDecimal + blockReward == newImmatureBalance.mine.trusted.toBigDecimal)
    }
  }

  it should "be able to get blockchain info" in {
    client: BitcoindV20RpcClient =>
      for {
        info <- client.getBlockChainInfo
        bestHash <- client.getBestBlockHash
      } yield {
        assert(info.isInstanceOf[GetBlockChainInfoResultPostV19])
        val preV19Info = info.asInstanceOf[GetBlockChainInfoResultPostV19]
        assert(preV19Info.chain == RegTest)
        assert(preV19Info.softforks.size >= 5)
        assert(
          preV19Info.softforks.values.exists(
            _.isInstanceOf[Bip9SoftforkPostV19]))
        assert(preV19Info.bestblockhash == bestHash)
      }
  }

  it should "be able to set the wallet flag 'avoid_reuse'" in {
    client: BitcoindV20RpcClient =>
      for {
        unspentPre <- client.listUnspent
        result <- client.setWalletFlag(WalletFlag.AvoidReuse, value = true)
        unspentPost <- client.listUnspent
      } yield {
        assert(result.flag_name == "avoid_reuse")
        assert(result.flag_state)
        assert(unspentPre.forall(utxo => utxo.reused.isEmpty))
        assert(unspentPost.forall(utxo => utxo.reused.isDefined))
      }
  }

  it should "create a wallet with a passphrase" in {
    client: BitcoindV20RpcClient =>
      for {
        _ <- client.createWallet("suredbits", passphrase = "stackingsats")
        wallets <- client.listWallets
      } yield {
        assert(wallets.contains("suredbits"))
      }

  }

  it should "check to see if the utxoUpdate input has been updated" in {
    client: BitcoindV20RpcClient =>
      val descriptor =
        "pk(0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798)"

      val psbt =
        PSBT.fromBase64(
          "cHNidP8BACoCAAAAAAFAQg8AAAAAABepFG6Rty1Vk+fUOR4v9E6R6YXDFkHwhwAAAAAAAA==")

      for {
        result <- client.utxoUpdatePsbt(psbt, Seq(descriptor))
      } yield {
        assert(result == psbt)
      }
  }

  it should "correct create multisig and get its descriptor" in {
    client: BitcoindV20RpcClient =>
      val pubKey1 = ECPublicKey.freshPublicKey
      val pubKey2 = ECPublicKey.freshPublicKey

      for {
        multiSigResult <- client.createMultiSig(2, Vector(pubKey1, pubKey2))
      } yield {
        // just validate we are able to receive a sane descriptor
        // no need to check checksum
        assert(
          multiSigResult.descriptor.startsWith(
            s"sh(multi(2,${pubKey1.hex},${pubKey2.hex}))#"))
      }
  }

  it should "correctly dump tx out set" in { client: BitcoindV20RpcClient =>
    for {
      hash <- client.getBestBlockHash
      height <- client.getBestHashBlockHeight()
      result <- client.dumpTxOutSet(new File("utxo.dat").toPath)
    } yield {
      assert(Files.exists(result.path))
      // Mild clean up
      Files.delete(result.path)

      assert(result.base_hash == hash)
      assert(result.base_height == height)
      assert(result.coins_written > 0)
    }
  }

  it should "correct generate to a descriptor" in {
    client: BitcoindV20RpcClient =>
      // 2-of-2 multisig descriptor
      val descriptor =
        "sh(sortedmulti(2,023f720438186fbdfde0c0a403e770a0f32a2d198623a8a982c47b621f8b307640,03ed261094d609d5e02ba6553c2d91e4fd056006ce2fe64aace72b69cb5be3ab9c))#nj9wx7up"
      val numBlocks = 10
      for {
        hashes <- client.generateToDescriptor(numBlocks, descriptor)
      } yield assert(hashes.size == numBlocks)
  }
}
