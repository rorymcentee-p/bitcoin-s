package org.bitcoins.testkit.rpc

import akka.actor.ActorSystem
import grizzled.slf4j.Logging
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts.AddNodeArgument
import org.bitcoins.commons.jsonmodels.bitcoind.{
  GetBlockWithTransactionsResult,
  GetTransactionResult,
  RpcOpts,
  SignRawTransactionResult
}
import org.bitcoins.core.compat.JavaConverters._
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script.ScriptSignature
import org.bitcoins.core.protocol.transaction.{
  Transaction,
  TransactionInput,
  TransactionOutPoint
}
import org.bitcoins.core.util.EnvUtil
import org.bitcoins.crypto.{
  DoubleSha256Digest,
  DoubleSha256DigestBE,
  ECPublicKey
}
import org.bitcoins.rpc.BitcoindException
import org.bitcoins.rpc.client.common.BitcoindVersion._
import org.bitcoins.rpc.client.common.{BitcoindRpcClient, BitcoindVersion}
import org.bitcoins.rpc.client.v16.BitcoindV16RpcClient
import org.bitcoins.rpc.client.v17.BitcoindV17RpcClient
import org.bitcoins.rpc.client.v18.BitcoindV18RpcClient
import org.bitcoins.rpc.client.v19.BitcoindV19RpcClient
import org.bitcoins.rpc.client.v20.BitcoindV20RpcClient
import org.bitcoins.rpc.client.v21.BitcoindV21RpcClient
import org.bitcoins.rpc.config._
import org.bitcoins.rpc.util.RpcUtil
import org.bitcoins.testkit.util.{BitcoindRpcTestClient, FileUtil, TorUtil}
import org.bitcoins.util.ListUtil

import java.io.File
import java.net.{InetSocketAddress, URI}
import java.nio.file.{Files, Path}
import scala.collection.immutable.Map
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util._
import scala.util.control.NonFatal

//noinspection AccessorLikeMethodIsEmptyParen
trait BitcoindRpcTestUtil extends Logging {

  lazy val network: RegTest.type = RegTest

  type RpcClientAccum =
    mutable.Builder[BitcoindRpcClient, Vector[BitcoindRpcClient]]

  private def newUri: URI = new URI(s"http://localhost:${RpcUtil.randomPort}")

  private def newInetSocketAddres: InetSocketAddress = {
    new InetSocketAddress(RpcUtil.randomPort)
  }

  /** Standard config used for testing purposes
    */
  def standardConfig: BitcoindConfig = {

    val hashBlock = newInetSocketAddres
    val hashTx = newInetSocketAddres
    val rawBlock = newInetSocketAddres
    val rawTx = newInetSocketAddres
    val zmqConfig = ZmqConfig(hashBlock = Some(hashBlock),
                              rawBlock = Some(rawBlock),
                              hashTx = Some(hashTx),
                              rawTx = Some(rawTx))
    config(uri = newUri,
           rpcUri = newUri,
           zmqConfig = zmqConfig,
           pruneMode = false)
  }

  def config(
      uri: URI,
      rpcUri: URI,
      zmqConfig: ZmqConfig,
      pruneMode: Boolean,
      blockFilterIndex: Boolean = false): BitcoindConfig = {
    val pass = FileUtil.randomDirName
    val username = "random_user_name"

    /* pruning and txindex are not compatible */
    val txindex = if (pruneMode) 0 else 1
    val conf = s"""
                  |regtest=1
                  |daemon=1
                  |server=1
                  |rpcuser=$username
                  |rpcpassword=$pass
                  |rpcport=${rpcUri.getPort}
                  |port=${uri.getPort}
                  |debug=1
                  |walletbroadcast=1
                  |peerbloomfilters=1
                  |fallbackfee=0.0002
                  |txindex=$txindex
                  |zmqpubhashtx=tcp://${zmqConfig.hashTx.get.getHostString}:${zmqConfig.hashTx.get.getPort}
                  |zmqpubhashblock=tcp://${zmqConfig.hashBlock.get.getHostString}:${zmqConfig.hashBlock.get.getPort}
                  |zmqpubrawtx=tcp://${zmqConfig.rawTx.get.getHostString}:${zmqConfig.rawTx.get.getPort}
                  |zmqpubrawblock=tcp://${zmqConfig.rawBlock.get.getHostString}:${zmqConfig.rawBlock.get.getPort}
                  |prune=${if (pruneMode) 1 else 0}
    """.stripMargin
    val config =
      if (blockFilterIndex) {
        conf + """
                 |blockfilterindex=1
                 |peerblockfilters=1
                 |""".stripMargin
      } else {
        conf
      }

    val configTor = if (TorUtil.torEnabled) {
      config +
        """
          |[regtest]
          |proxy=127.0.0.1:9050
          |listen=1
          |bind=127.0.0.1
          |""".stripMargin
    } else {
      config
    }
    BitcoindConfig(config = configTor, datadir = FileUtil.tmpDir())
  }

  /** Creates a `bitcoind` config within the system temp
    * directory, writes the file and returns the written
    * file
    */
  def writtenConfig(
      uri: URI,
      rpcUri: URI,
      zmqConfig: ZmqConfig,
      pruneMode: Boolean,
      blockFilterIndex: Boolean = false
  ): Path = {
    val conf = config(uri = uri,
                      rpcUri = rpcUri,
                      zmqConfig = zmqConfig,
                      pruneMode = pruneMode,
                      blockFilterIndex = blockFilterIndex)

    val datadir = conf.datadir
    val written = BitcoindConfig.writeConfigToFile(conf, datadir)
    logger.debug(s"Wrote conf to $written")
    written
  }

  def newestBitcoindBinary: File = getBinary(BitcoindVersion.newest)

  def getBinary(
      version: BitcoindVersion,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory): File =
    version match {
      // default to newest version
      case Unknown => getBinary(BitcoindVersion.newest, binaryDirectory)
      case known @ (Experimental | V16 | V17 | V18 | V19 | V20 | V21) =>
        val fileList = Files
          .list(binaryDirectory)
          .iterator()
          .asScala
          .toList
          .filter(f => Files.isDirectory(f))
        // drop leading 'v'
        val version = known.toString.drop(1)
        val filtered =
          if (known == Experimental)
            // we want exact match for the experimental version
            fileList
              .filter(f => f.toString.endsWith(version))
          else
            // we don't want the experimental version to appear in the list along with the production ones
            fileList
              .filterNot(f =>
                f.toString.endsWith(Experimental.toString.drop(1)))
              .filter(f => f.toString.contains(version))

        if (filtered.isEmpty)
          throw new RuntimeException(
            s"bitcoind ${known.toString} is not installed in $binaryDirectory. Run `sbt downloadBitcoind`")

        // might be multiple versions downloaded for
        // each major version, i.e. 0.16.2 and 0.16.3
        val versionFolder = filtered.max

        versionFolder
          .resolve("bin")
          .resolve(if (Properties.isWin) "bitcoind.exe" else "bitcoind")
          .toFile
    }

  /** Creates a `bitcoind` instance within the user temporary directory */
  def instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      versionOpt: Option[BitcoindVersion] = None,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory)(implicit
      system: ActorSystem): BitcoindInstanceLocal = {
    val uri = new URI("http://localhost:" + port)
    val rpcUri = new URI("http://localhost:" + rpcPort)
    val hasNeutrinoSupport = versionOpt match {
      case Some(V16) | Some(V17) | Some(V18) =>
        false
      case Some(V19) | Some(V20) | Some(V21) | Some(Experimental) | Some(
            Unknown) | None =>
        true
    }
    val configFile =
      writtenConfig(uri,
                    rpcUri,
                    zmqConfig,
                    pruneMode,
                    blockFilterIndex = hasNeutrinoSupport)
    val conf = BitcoindConfig(configFile)
    val binary: File = versionOpt match {
      case Some(version) => getBinary(version)
      case None =>
        if (Files.exists(binaryDirectory)) {
          newestBitcoindBinary
        } else {
          throw new RuntimeException(
            "Could not locate bitcoind. Make sure it is installed on your PATH, or if working with Bitcoin-S " +
              "directly, try running 'sbt downloadBitcoind'")
        }

    }

    BitcoindInstanceLocal.fromConfig(conf, binary)
  }

  def v16Instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal =
    instance(port = port,
             rpcPort = rpcPort,
             zmqConfig = zmqConfig,
             pruneMode = pruneMode,
             versionOpt = Some(BitcoindVersion.V16),
             binaryDirectory = binaryDirectory)

  def v17Instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal =
    instance(port = port,
             rpcPort = rpcPort,
             zmqConfig = zmqConfig,
             pruneMode = pruneMode,
             versionOpt = Some(BitcoindVersion.V17),
             binaryDirectory = binaryDirectory)

  def v18Instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal =
    instance(port = port,
             rpcPort = rpcPort,
             zmqConfig = zmqConfig,
             pruneMode = pruneMode,
             versionOpt = Some(BitcoindVersion.V18),
             binaryDirectory = binaryDirectory)

  def v19Instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal =
    instance(port = port,
             rpcPort = rpcPort,
             zmqConfig = zmqConfig,
             pruneMode = pruneMode,
             versionOpt = Some(BitcoindVersion.V19),
             binaryDirectory = binaryDirectory)

  def v20Instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal =
    instance(port = port,
             rpcPort = rpcPort,
             zmqConfig = zmqConfig,
             pruneMode = pruneMode,
             versionOpt = Some(BitcoindVersion.V20),
             binaryDirectory = binaryDirectory)

  def v21Instance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal =
    instance(port = port,
             rpcPort = rpcPort,
             zmqConfig = zmqConfig,
             pruneMode = pruneMode,
             versionOpt = Some(BitcoindVersion.V21),
             binaryDirectory = binaryDirectory)

  def vExperimentalInstance(
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory
  )(implicit system: ActorSystem): BitcoindInstanceLocal =
    instance(port = port,
             rpcPort = rpcPort,
             zmqConfig = zmqConfig,
             pruneMode = pruneMode,
             versionOpt = Some(BitcoindVersion.Experimental),
             binaryDirectory = binaryDirectory)

  /** Gets an instance of bitcoind with the given version */
  def getInstance(
      bitcoindVersion: BitcoindVersion,
      port: Int = RpcUtil.randomPort,
      rpcPort: Int = RpcUtil.randomPort,
      zmqConfig: ZmqConfig = RpcUtil.zmqConfig,
      pruneMode: Boolean = false,
      binaryDirectory: Path = BitcoindRpcTestClient.sbtBinaryDirectory)(implicit
      system: ActorSystem): BitcoindInstanceLocal = {
    bitcoindVersion match {
      case BitcoindVersion.V16 =>
        BitcoindRpcTestUtil.v16Instance(port,
                                        rpcPort,
                                        zmqConfig,
                                        pruneMode,
                                        binaryDirectory = binaryDirectory)
      case BitcoindVersion.V17 =>
        BitcoindRpcTestUtil.v17Instance(port,
                                        rpcPort,
                                        zmqConfig,
                                        pruneMode,
                                        binaryDirectory = binaryDirectory)
      case BitcoindVersion.V18 =>
        BitcoindRpcTestUtil.v18Instance(port,
                                        rpcPort,
                                        zmqConfig,
                                        pruneMode,
                                        binaryDirectory = binaryDirectory)
      case BitcoindVersion.V19 =>
        BitcoindRpcTestUtil.v19Instance(port,
                                        rpcPort,
                                        zmqConfig,
                                        pruneMode,
                                        binaryDirectory = binaryDirectory)
      case BitcoindVersion.V20 =>
        BitcoindRpcTestUtil.v20Instance(port,
                                        rpcPort,
                                        zmqConfig,
                                        pruneMode,
                                        binaryDirectory = binaryDirectory)
      case BitcoindVersion.V21 =>
        BitcoindRpcTestUtil.v21Instance(port,
                                        rpcPort,
                                        zmqConfig,
                                        pruneMode,
                                        binaryDirectory = binaryDirectory)
      case BitcoindVersion.Experimental =>
        BitcoindRpcTestUtil.vExperimentalInstance(port,
                                                  rpcPort,
                                                  zmqConfig,
                                                  pruneMode,
                                                  binaryDirectory =
                                                    binaryDirectory)
      case BitcoindVersion.Unknown =>
        sys.error(
          s"Could not create a bitcoind version with version=${BitcoindVersion.Unknown}")
    }
  }

  def startServers(servers: Vector[BitcoindRpcClient])(implicit
      ec: ExecutionContext): Future[Unit] = {
    val startedServers = servers.map { server =>
      server.start().flatMap { res =>
        val createWalletF = for {
          _ <- res.createWallet("")
        } yield res

        createWalletF.recoverWith { case NonFatal(_) =>
          Future.successful(res)
        }
      }
    }

    Future.sequence(startedServers).map(_ => ())
  }

  /** Stops the given servers and deletes their data directories
    */
  def stopServers(servers: Vector[BitcoindRpcClient])(implicit
      system: ActorSystem): Future[Unit] = {
    implicit val ec: ExecutionContextExecutor = system.getDispatcher

    val serverStops = servers.map { s =>
      val stopF = s.stop()
      stopF.onComplete {
        case Failure(exception) =>
          logger.error(s"Could not shut down bitcoind server: $exception")
        case Success(_) =>
      }
      for {
        _ <- stopF
        _ <- awaitStopped(s)
        _ <- removeDataDirectory(s)
      } yield ()
    }
    Future.sequence(serverStops).map(_ => ())
  }

  /** Stops the given server and deletes its data directory
    */
  def stopServer(server: BitcoindRpcClient)(implicit
      system: ActorSystem): Future[Unit] = {
    stopServers(Vector(server))
  }

  /** Awaits non-blockingly until the provided clients are connected
    */
  def awaitConnection(
      from: BitcoindRpcClient,
      to: BitcoindRpcClient,
      interval: FiniteDuration = 100.milliseconds,
      maxTries: Int = 50)(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher

    val isConnected: () => Future[Boolean] = () => {
      from
        .getAddedNodeInfo(to.getDaemon.uri)
        .map { info =>
          info.nonEmpty && info.head.connected.contains(true)
        }
    }

    AsyncUtil.retryUntilSatisfiedF(conditionF = isConnected,
                                   interval = interval,
                                   maxTries = maxTries)
  }

  /** Return index of output of TX `txid` with value `amount`
    *
    * @see function we're mimicking in
    *      [[https://github.com/bitcoin/bitcoin/blob/master/test/functional/test_framework/util.py#L410 Core test suite]]
    */
  def findOutput(
      client: BitcoindRpcClient,
      txid: DoubleSha256DigestBE,
      amount: Bitcoins,
      blockhash: Option[DoubleSha256DigestBE] = None)(implicit
      executionContext: ExecutionContext): Future[UInt32] = {
    client.getRawTransaction(txid, blockhash).map { tx =>
      tx.vout.zipWithIndex
        .find { case (output, _) =>
          output.value == amount
        }
        .map { case (_, i) => UInt32(i) }
        .getOrElse(throw new RuntimeException(
          s"Could not find output for $amount in TX ${txid.hex}"))
    }
  }

  /** Generates the specified amount of blocks with all provided clients
    * and waits until they are synced.
    *
    * @return Vector of Blockhashes of generated blocks, with index corresponding to the
    *         list of provided clients
    */
  def generateAllAndSync(
      clients: Vector[BitcoindRpcClient],
      blocks: Int = 6)(implicit
      system: ActorSystem): Future[Vector[Vector[DoubleSha256DigestBE]]] = {
    import system.dispatcher

    val sliding: Vector[Vector[BitcoindRpcClient]] =
      ListUtil.rotateHead(clients)

    val initF = Future.successful(Vector.empty[Vector[DoubleSha256DigestBE]])

    val genereratedHashesF = sliding
      .foldLeft(initF) { (accumHashesF, clients) =>
        accumHashesF.flatMap { accumHashes =>
          val hashesF = generateAndSync(clients, blocks)
          hashesF.map(hashes => hashes +: accumHashes)
        }
      }

    genereratedHashesF.map(_.reverse.toVector)
  }

  /** Generates the specified amount of blocks and waits until
    * the provided clients are synced.
    *
    * @return Blockhashes of generated blocks
    */
  def generateAndSync(clients: Vector[BitcoindRpcClient], blocks: Int = 6)(
      implicit system: ActorSystem): Future[Vector[DoubleSha256DigestBE]] = {
    require(clients.length > 1, "Can't sync less than 2 nodes")

    import system.dispatcher

    for {
      address <- clients.head.getNewAddress
      hashes <- clients.head.generateToAddress(blocks, address)
      _ <- {
        val pairs = ListUtil.uniquePairs(clients)
        val syncFuts = pairs.map { case (first, second) =>
          awaitSynced(first, second)
        }
        Future.sequence(syncFuts)
      }
    } yield hashes
  }

  def awaitSynced(
      client1: BitcoindRpcClient,
      client2: BitcoindRpcClient,
      interval: FiniteDuration = BitcoindRpcTestUtil.DEFAULT_LONG_INTERVAL,
      maxTries: Int = 50)(implicit system: ActorSystem): Future[Unit] = {
    implicit val ec: ExecutionContextExecutor = system.dispatcher

    def isSynced(): Future[Boolean] = {
      client1.getBestBlockHash.flatMap { hash1 =>
        client2.getBestBlockHash.map { hash2 =>
          hash1 == hash2
        }
      }
    }

    AsyncUtil.retryUntilSatisfiedF(conditionF = () => isSynced(),
                                   interval = interval,
                                   maxTries = maxTries)
  }

  def awaitSameBlockHeight(
      client1: BitcoindRpcClient,
      client2: BitcoindRpcClient,
      interval: FiniteDuration = BitcoindRpcTestUtil.DEFAULT_LONG_INTERVAL,
      maxTries: Int = 50)(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher

    def isSameBlockHeight(): Future[Boolean] = {
      client1.getBlockCount.flatMap { count1 =>
        client2.getBlockCount.map { count2 =>
          count1 == count2
        }
      }
    }

    AsyncUtil.retryUntilSatisfiedF(conditionF = () => isSameBlockHeight(),
                                   interval = interval,
                                   maxTries = maxTries)
  }

  def awaitDisconnected(
      from: BitcoindRpcClient,
      to: BitcoindRpcClient,
      interval: FiniteDuration = 100.milliseconds,
      maxTries: Int = 50)(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher

    def isDisconnected(): Future[Boolean] = {
      from
        .getAddedNodeInfo(to.getDaemon.uri)
        .map(info => info.isEmpty || info.head.connected.contains(false))
        .recoverWith {
          case exception: BitcoindException
              if exception.getMessage().contains("Node has not been added") =>
            from.getPeerInfo.map(
              _.forall(_.networkInfo.addr != to.instance.uri))
        }

    }

    AsyncUtil.retryUntilSatisfiedF(conditionF = () => isDisconnected(),
                                   interval = interval,
                                   maxTries = maxTries)
  }

  def awaitStopped(
      client: BitcoindRpcClient,
      interval: FiniteDuration = 100.milliseconds,
      maxTries: Int = 50)(implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher
    AsyncUtil.retryUntilSatisfiedF(conditionF = { () => client.isStoppedF },
                                   interval = interval,
                                   maxTries = maxTries)
  }

  def removeDataDirectory(
      client: BitcoindRpcClient,
      interval: FiniteDuration = 100.milliseconds,
      maxTries: Int = 50)(implicit system: ActorSystem): Future[Unit] = {
    implicit val ec = system.dispatcher
    AsyncUtil
      .retryUntilSatisfiedF(
        conditionF = { () =>
          Future {
            val dir = client.getDaemon match {
              case _: BitcoindInstanceRemote =>
                sys.error(s"Cannot have remote bitcoind instance in testkit")
              case local: BitcoindInstanceLocal => local.datadir
            }
            FileUtil.deleteTmpDir(dir)
            !dir.exists()
          }
        },
        interval = interval,
        maxTries = maxTries
      )
  }

  /** Returns a pair of unconnected
    * [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]s
    * with no blocks
    */
  def createUnconnectedNodePair(
      clientAccum: RpcClientAccum = Vector.newBuilder
  )(implicit
      system: ActorSystem): Future[(BitcoindRpcClient, BitcoindRpcClient)] = {
    implicit val ec: ExecutionContextExecutor = system.getDispatcher
    val client1: BitcoindRpcClient =
      BitcoindRpcClient.withActorSystem(instance())
    val client2: BitcoindRpcClient =
      BitcoindRpcClient.withActorSystem(instance())

    startServers(Vector(client1, client2)).map { _ =>
      clientAccum ++= List(client1, client2)
      (client1, client2)
    }
  }

  def syncPairs(pairs: Vector[(BitcoindRpcClient, BitcoindRpcClient)])(implicit
      system: ActorSystem): Future[Unit] = {
    import system.dispatcher
    val futures = pairs.map { case (first, second) =>
      BitcoindRpcTestUtil.awaitSynced(first, second)
    }
    Future.sequence(futures).map(_ => ())
  }

  /** Connects and waits non-blockingly until all the provided pairs of clients
    * are connected
    */
  def connectPairs(pairs: Vector[(BitcoindRpcClient, BitcoindRpcClient)])(
      implicit system: ActorSystem): Future[Unit] = {
    import system.dispatcher
    val addNodesF: Future[Vector[Unit]] = {
      val addedF = pairs.map { case (first, second) =>
        first.addNode(second.getDaemon.uri, AddNodeArgument.Add)
      }
      Future.sequence(addedF)
    }

    val connectedPairsF = addNodesF.flatMap { _ =>
      val futures = pairs.map { case (first, second) =>
        BitcoindRpcTestUtil
          .awaitConnection(first, second, interval = 10.second)
      }
      Future.sequence(futures)
    }

    connectedPairsF.map(_ => ())
  }

  private def createNodeSequence[T <: BitcoindRpcClient](
      numNodes: Int,
      version: BitcoindVersion)(implicit
      system: ActorSystem): Future[Vector[T]] = {
    import system.dispatcher

    val clients: Vector[T] = (0 until numNodes).map { _ =>
      val rpc = version match {
        case BitcoindVersion.Unknown =>
          BitcoindRpcClient.withActorSystem(BitcoindRpcTestUtil.instance())
        case BitcoindVersion.V16 =>
          BitcoindV16RpcClient.withActorSystem(
            BitcoindRpcTestUtil.v16Instance())
        case BitcoindVersion.V17 =>
          BitcoindV17RpcClient.withActorSystem(
            BitcoindRpcTestUtil.v17Instance())
        case BitcoindVersion.V18 =>
          BitcoindV18RpcClient.withActorSystem(
            BitcoindRpcTestUtil.v18Instance())
        case BitcoindVersion.V19 =>
          BitcoindV19RpcClient.withActorSystem(
            BitcoindRpcTestUtil.v19Instance())
        case BitcoindVersion.V20 =>
          BitcoindV20RpcClient.withActorSystem(
            BitcoindRpcTestUtil.v20Instance())
        case BitcoindVersion.V21 =>
          BitcoindV21RpcClient.withActorSystem(
            BitcoindRpcTestUtil.v21Instance())
        case BitcoindVersion.Experimental =>
          BitcoindV19RpcClient.withActorSystem(
            BitcoindRpcTestUtil.vExperimentalInstance())
      }

      // this is safe as long as this method is never
      // exposed as a public method, and that all public
      // methods calling this make sure that the version
      // arg and the type arg matches up
      val rpcT = rpc.asInstanceOf[T]
      rpcT
    }.toVector

    val startF = BitcoindRpcTestUtil.startServers(clients)

    val pairsF = startF.map { _ =>
      ListUtil.uniquePairs(clients)
    }

    for {
      pairs <- pairsF
      _ <- connectPairs(pairs)
      _ <- BitcoindRpcTestUtil.generateAllAndSync(clients, blocks = 101)
    } yield clients
  }

  private def createNodePairInternal[T <: BitcoindRpcClient](
      version: BitcoindVersion,
      clientAccum: RpcClientAccum)(implicit
      system: ActorSystem): Future[(T, T)] = {
    import system.dispatcher

    createNodePairInternal[T](version).map { pair =>
      clientAccum.++=(Vector(pair._1, pair._2))
      pair
    }
  }

  private def createNodePairInternal[T <: BitcoindRpcClient](
      version: BitcoindVersion)(implicit
      system: ActorSystem): Future[(T, T)] = {
    import system.dispatcher

    createNodeSequence[T](numNodes = 2, version).map {
      case first +: second +: _ => (first, second)
      case _: Vector[BitcoindRpcClient] =>
        throw new RuntimeException("Did not get two clients!")
    }
  }

  /** Returns a pair of [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]
    * that are connected with some blocks in the chain
    */
  def createNodePair[T <: BitcoindRpcClient](
      clientAccum: RpcClientAccum = Vector.newBuilder)(implicit
      system: ActorSystem): Future[(BitcoindRpcClient, BitcoindRpcClient)] =
    createNodePair[T](BitcoindVersion.newest).map { pair =>
      clientAccum.++=(Vector(pair._1, pair._2))
      pair
    }(system.dispatcher)

  def createNodePair[T <: BitcoindRpcClient](version: BitcoindVersion)(implicit
      system: ActorSystem): Future[(T, T)] = {
    createNodePairInternal(version)
  }

  /** Returns a pair of [[org.bitcoins.rpc.client.v16.BitcoindV16RpcClient BitcoindV16RpcClient]]
    * that are connected with some blocks in the chain
    */
  def createNodePairV16(clientAccum: RpcClientAccum)(implicit
  system: ActorSystem): Future[(BitcoindV16RpcClient, BitcoindV16RpcClient)] =
    createNodePairInternal(BitcoindVersion.V16, clientAccum)

  /** Returns a pair of [[org.bitcoins.rpc.client.v17.BitcoindV17RpcClient BitcoindV17RpcClient]]
    * that are connected with some blocks in the chain
    */
  def createNodePairV17(clientAccum: RpcClientAccum)(implicit
  system: ActorSystem): Future[(BitcoindV17RpcClient, BitcoindV17RpcClient)] =
    createNodePairInternal(BitcoindVersion.V17, clientAccum)

  /** Returns a pair of [[org.bitcoins.rpc.client.v18.BitcoindV18RpcClient BitcoindV18RpcClient]]
    * that are connected with some blocks in the chain
    */
  def createNodePairV18(clientAccum: RpcClientAccum)(implicit
  system: ActorSystem): Future[(BitcoindV18RpcClient, BitcoindV18RpcClient)] =
    createNodePairInternal(BitcoindVersion.V18, clientAccum)

  /** Returns a pair of [[org.bitcoins.rpc.client.v19.BitcoindV19RpcClient BitcoindV19RpcClient]]
    * that are connected with some blocks in the chain
    */
  def createNodePairV19(clientAccum: RpcClientAccum)(implicit
  system: ActorSystem): Future[(BitcoindV19RpcClient, BitcoindV19RpcClient)] =
    createNodePairInternal(BitcoindVersion.V19, clientAccum)

  /** Returns a pair of [[org.bitcoins.rpc.client.v20.BitcoindV20RpcClient BitcoindV20RpcClient]]
    * that are connected with some blocks in the chain
    */
  def createNodePairV20(clientAccum: RpcClientAccum)(implicit
  system: ActorSystem): Future[(BitcoindV20RpcClient, BitcoindV20RpcClient)] =
    createNodePairInternal(BitcoindVersion.V20, clientAccum)

  /** Returns a pair of [[org.bitcoins.rpc.client.v21.BitcoindV21RpcClient BitcoindV21RpcClient]]
    * that are connected with some blocks in the chain
    */
  def createNodePairV21(clientAccum: RpcClientAccum)(implicit
  system: ActorSystem): Future[(BitcoindV21RpcClient, BitcoindV21RpcClient)] =
    createNodePairInternal(BitcoindVersion.V21, clientAccum)

  /** Returns a triple of [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]
    * that are connected with some blocks in the chain
    */
  private def createNodeTripleInternal[T <: BitcoindRpcClient](
      version: BitcoindVersion,
      clientAccum: RpcClientAccum
  )(implicit system: ActorSystem): Future[(T, T, T)] = {
    import system.dispatcher

    createNodeTripleInternal[T](version).map { nodes =>
      clientAccum.+=(nodes._1)
      clientAccum.+=(nodes._2)
      clientAccum.+=(nodes._3)
      nodes
    }
  }

  /** Returns a triple of [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]
    * that are connected with some blocks in the chain
    */
  private def createNodeTripleInternal[T <: BitcoindRpcClient](
      version: BitcoindVersion
  )(implicit system: ActorSystem): Future[(T, T, T)] = {
    import system.dispatcher

    createNodeSequence[T](numNodes = 3, version).map {
      case first +: second +: third +: _ => (first, second, third)
      case _: Vector[T] =>
        throw new RuntimeException("Did not get three clients!")
    }
  }

  /** Returns a triple of org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient
    * that are connected with some blocks in the chain
    */
  def createNodeTriple(
      clientAccum: RpcClientAccum
  )(implicit system: ActorSystem): Future[
    (BitcoindRpcClient, BitcoindRpcClient, BitcoindRpcClient)] = {
    createNodeTripleInternal(BitcoindVersion.Unknown, clientAccum)
  }

  /** Returns a triple of org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient
    * that are connected with some blocks in the chain
    */
  def createNodeTriple[T <: BitcoindRpcClient](version: BitcoindVersion)(
      implicit system: ActorSystem): Future[(T, T, T)] = {
    createNodeTripleInternal(version)
  }

  /** @return a triple of [[org.bitcoins.rpc.client.v17.BitcoindV17RpcClient BitcoindV17RpcClient]]
    * that are connected with some blocks in the chain
    */
  def createNodeTripleV17(
      clientAccum: RpcClientAccum
  )(implicit system: ActorSystem): Future[
    (BitcoindV17RpcClient, BitcoindV17RpcClient, BitcoindV17RpcClient)] = {
    createNodeTripleInternal(BitcoindVersion.V17, clientAccum)
  }

  def createNodeTripleV18(
      clientAccum: RpcClientAccum
  )(implicit system: ActorSystem): Future[
    (BitcoindV18RpcClient, BitcoindV18RpcClient, BitcoindV18RpcClient)] = {
    createNodeTripleInternal(BitcoindVersion.V18, clientAccum)
  }

  def createNodeTripleV19(
      clientAccum: RpcClientAccum
  )(implicit system: ActorSystem): Future[
    (BitcoindV19RpcClient, BitcoindV19RpcClient, BitcoindV19RpcClient)] = {
    createNodeTripleInternal(BitcoindVersion.V19, clientAccum)
  }

  def createRawCoinbaseTransaction(
      sender: BitcoindRpcClient,
      receiver: BitcoindRpcClient,
      amount: Bitcoins = Bitcoins(1))(implicit
      executionContext: ExecutionContext): Future[Transaction] = {
    for {
      address <- sender.getNewAddress
      blocks <- sender.generateToAddress(2, address)
      block0 <- sender.getBlock(blocks(0))
      block1 <- sender.getBlock(blocks(1))
      transaction0 <- sender.getTransaction(block0.tx(0))
      transaction1 <- sender.getTransaction(block1.tx(0))
      input0 = TransactionOutPoint(transaction0.txid.flip,
                                   UInt32(transaction0.blockindex.get))
      input1 = TransactionOutPoint(transaction1.txid.flip,
                                   UInt32(transaction1.blockindex.get))
      sig: ScriptSignature = ScriptSignature.empty
      address <- receiver.getNewAddress
      tx <- sender.createRawTransaction(
        Vector(TransactionInput(input0, sig, UInt32(1)),
               TransactionInput(input1, sig, UInt32(2))),
        Map(address -> amount))
    } yield tx

  }

  /** Bitcoin Core 0.16 and 0.17 has diffrent APIs for signing raw transactions.
    * This method tries to construct either a
    * [[org.bitcoins.rpc.client.v16.BitcoindV16RpcClient BitcoindV16RpcClient]]
    * or a [[org.bitcoins.rpc.client.v16.BitcoindV16RpcClient BitcoindV16RpcClient]]
    * from the provided `signer`, and then calls the appropriate method on the result.
    *
    * @throws RuntimeException if no versioned
    * [[org.bitcoins.rpc.client.common.BitcoindRpcClient BitcoindRpcClient]]
    * can be constructed.
    */
  def signRawTransaction(
      signer: BitcoindRpcClient,
      transaction: Transaction,
      utxoDeps: Vector[RpcOpts.SignRawTransactionOutputParameter] = Vector.empty
  ): Future[SignRawTransactionResult] =
    signer match {
      case v17: BitcoindV17RpcClient =>
        v17.signRawTransactionWithWallet(transaction, utxoDeps)
      case v16: BitcoindV16RpcClient =>
        v16.signRawTransaction(transaction, utxoDeps)
      case v20: BitcoindV20RpcClient =>
        v20.signRawTransactionWithWallet(transaction)
      case v21: BitcoindV21RpcClient =>
        v21.signRawTransactionWithWallet(transaction)
      case unknown: BitcoindRpcClient =>
        val v16T = BitcoindV16RpcClient.fromUnknownVersion(unknown)
        val v17T = BitcoindV17RpcClient.fromUnknownVersion(unknown)
        val v18T = BitcoindV18RpcClient.fromUnknownVersion(unknown)
        val v19T = BitcoindV19RpcClient.fromUnknownVersion(unknown)
        (v16T, v17T, v18T, v19T) match {
          case (Failure(_), Failure(_), Failure(_), Failure(_)) =>
            throw new RuntimeException(
              "Could not figure out version of provided bitcoind RPC client!" +
                "This should not happen, managed to construct different versioned RPC clients from one single client")
          case (Success(v16), _, _, _) =>
            v16.signRawTransaction(transaction, utxoDeps)
          case (_, Success(v17), _, _) =>
            v17.signRawTransactionWithWallet(transaction, utxoDeps)
          case (_, _, Success(v18), _) =>
            v18.signRawTransactionWithWallet(transaction, utxoDeps)
          case (_, _, _, Success(v19)) =>
            v19.signRawTransactionWithWallet(transaction, utxoDeps)
        }
    }

  /** Gets the pubkey (if it exists) asscociated with a given
    * bitcoin address in a version-agnostic manner
    */
  def getPubkey(client: BitcoindRpcClient, address: BitcoinAddress)(implicit
      system: ActorSystem): Future[Option[ECPublicKey]] = {
    import system.dispatcher

    client match {
      case v17: BitcoindV17RpcClient =>
        v17.getAddressInfo(address).map(_.pubkey)
      case v16: BitcoindV16RpcClient =>
        v16.getAddressInfo(address).map(_.pubkey)
      case other: BitcoindRpcClient =>
        other.version.flatMap { v =>
          if (v.toString >= BitcoindVersion.V17.toString) {
            val v17 = new BitcoindV17RpcClient(other.instance)
            v17.getAddressInfo(address).map(_.pubkey)
          } else {
            other.getAddressInfo(address).map(_.pubkey)
          }
        }
    }
  }

  def sendCoinbaseTransaction(
      sender: BitcoindRpcClient,
      receiver: BitcoindRpcClient,
      amount: Bitcoins = Bitcoins(1))(implicit
      actorSystem: ActorSystem): Future[GetTransactionResult] = {
    implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher
    for {
      rawcoinbasetx <- createRawCoinbaseTransaction(sender, receiver, amount)
      signedtx <- signRawTransaction(sender, rawcoinbasetx)
      addr <- sender.getNewAddress
      _ <- sender.generateToAddress(100, addr)
      // Can't spend coinbase until depth 100
      transactionHash <- sender.sendRawTransaction(signedtx.hex, maxfeerate = 0)
      transaction <- sender.getTransaction(transactionHash)
    } yield transaction
  }

  /** @return The first block (after genesis) in the
    *         given node's blockchain
    */
  def getFirstBlock(node: BitcoindRpcClient)(implicit
      executionContext: ExecutionContext): Future[
    GetBlockWithTransactionsResult] = {
    node
      .getBlockHash(1)
      .flatMap(node.getBlockWithTransactions)
  }

  /** Mines blocks until the specified block height. */
  def waitUntilBlock(
      blockHeight: Int,
      client: BitcoindRpcClient,
      addressForMining: BitcoinAddress)(implicit
      ec: ExecutionContext): Future[Unit] = {
    for {
      currentCount <- client.getBlockCount
      blocksToMine = blockHeight - currentCount
      _ <- client.generateToAddress(blocks = blocksToMine, addressForMining)
    } yield ()
  }

  /** Produces a confirmed transaction from `sender` to `address`
    * for `amount`
    */
  def fundBlockChainTransaction(
      sender: BitcoindRpcClient,
      receiver: BitcoindRpcClient,
      address: BitcoinAddress,
      amount: Bitcoins)(implicit
      system: ActorSystem): Future[DoubleSha256DigestBE] = {

    implicit val ec: ExecutionContextExecutor = system.dispatcher

    for {
      txid <- fundMemPoolTransaction(sender, address, amount)
      addr <- sender.getNewAddress
      blockHash <- sender.generateToAddress(1, addr).map(_.head)
      seenBlock <- hasSeenBlock(receiver, blockHash)
      _ <-
        if (seenBlock) {
          Future.unit
        } else {
          sender
            .getBlockRaw(blockHash)
            .flatMap(receiver.submitBlock)
        }
    } yield {
      txid
    }
  }

  /** Produces a unconfirmed transaction from `sender` to `address`
    * for `amount`
    */
  def fundMemPoolTransaction(
      sender: BitcoindRpcClient,
      address: BitcoinAddress,
      amount: Bitcoins)(implicit
      system: ActorSystem): Future[DoubleSha256DigestBE] = {
    import system.dispatcher
    sender
      .createRawTransaction(Vector.empty, Map(address -> amount))
      .flatMap(sender.fundRawTransaction)
      .flatMap { fundedTx =>
        signRawTransaction(sender, fundedTx.hex).flatMap { signedTx =>
          sender.sendRawTransaction(signedTx.hex)
        }
      }
  }

  /** Stops the provided nodes and deletes their data directories
    */
  def deleteNodePair(client1: BitcoindRpcClient, client2: BitcoindRpcClient)(
      implicit executionContext: ExecutionContext): Future[Unit] = {
    val stopsF = List(client1, client2).map { client =>
      implicit val sys = client.system
      for {
        _ <- client.stop()
        _ <- awaitStopped(client)
        _ <- removeDataDirectory(client)
      } yield ()
    }
    Future.sequence(stopsF).map(_ => ())
  }

  /** Checks whether the provided client has seen the given block hash
    */
  def hasSeenBlock(client: BitcoindRpcClient, hash: DoubleSha256DigestBE)(
      implicit ec: ExecutionContext): Future[Boolean] = {
    val p = Promise[Boolean]()

    client.getBlock(hash.flip).onComplete {
      case Success(_) => p.success(true)
      case Failure(_) => p.success(false)
    }

    p.future
  }

  def hasSeenBlock(client1: BitcoindRpcClient, hash: DoubleSha256Digest)(
      implicit ec: ExecutionContext): Future[Boolean] = {
    hasSeenBlock(client1, hash.flip)
  }

  /** @param clientAccum If provided, the generated client is added to
    *                    this vectorbuilder.
    */
  def startedBitcoindRpcClient(
      instanceOpt: Option[BitcoindInstanceLocal] = None,
      clientAccum: RpcClientAccum)(implicit
      system: ActorSystem): Future[BitcoindRpcClient] = {
    implicit val ec: ExecutionContextExecutor = system.dispatcher

    val instance = instanceOpt.getOrElse(BitcoindRpcTestUtil.instance())

    require(
      instance.datadir.getPath.startsWith(Properties.tmpDir),
      s"${instance.datadir} is not in user temp dir! This could lead to bad things happening.")

    //start the bitcoind instance so eclair can properly use it
    val rpc = BitcoindRpcClient.withActorSystem(instance)
    val startedF = startServers(Vector(rpc))

    val blocksToGenerate = 102
    //fund the wallet by generating 102 blocks, need this to get over coinbase maturity
    val generatedF = startedF.flatMap { _ =>
      clientAccum += rpc
      rpc.getNewAddress.flatMap(rpc.generateToAddress(blocksToGenerate, _))
    }

    def areBlocksGenerated(): Future[Boolean] = {
      rpc.getBlockCount.map { count =>
        count >= blocksToGenerate
      }
    }

    val blocksGeneratedF = generatedF.flatMap { _ =>
      AsyncUtil.retryUntilSatisfiedF(
        () => areBlocksGenerated(),
        interval = BitcoindRpcTestUtil.DEFAULT_LONG_INTERVAL
      )
    }

    val result = blocksGeneratedF.map(_ => rpc)

    result
  }
}

object BitcoindRpcTestUtil extends BitcoindRpcTestUtil {

  /** Used for long running async tasks
    */
  val DEFAULT_LONG_INTERVAL = {
    if (EnvUtil.isMac && EnvUtil.isCI) 10.seconds
    else 3.seconds
  }
}
