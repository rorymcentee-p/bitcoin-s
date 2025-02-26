package org.bitcoins.server

import org.bitcoins.cli.{CliCommand, Config, ConsoleCli}
import org.bitcoins.commons.util.ServerArgParser
import org.bitcoins.testkit.fixtures.BitcoinSAppConfigBitcoinFixtureNotStarted
import org.bitcoins.testkit.tor.CachedTor

/** Test starting bitcoin-s with bitcoind as the backend for app */
class BitcoinSServerMainBitcoindTorTest
    extends BitcoinSAppConfigBitcoinFixtureNotStarted
    with CachedTor {

  behavior of "BitcoinSServerMain"

  it must "start our app server with bitcoind as a backend with tor" in {
    config: BitcoinSAppConfig =>
      val server = new BitcoinSServerMain(ServerArgParser.empty)(system, config)

      val cliConfig: Config = Config(rpcPortOpt = Some(config.rpcPort))

      for {
        _ <- torF
        _ <- server.start()
        // Await RPC server started
        _ <- BitcoinSServer.startedF

        info = ConsoleCli.exec(CliCommand.WalletInfo, cliConfig)
        balance = ConsoleCli.exec(CliCommand.GetBalance(isSats = true),
                                  cliConfig)
        addr = ConsoleCli.exec(CliCommand.GetNewAddress(labelOpt = None),
                               cliConfig)
        blockHash = ConsoleCli.exec(CliCommand.GetBestBlockHash, cliConfig)
      } yield {
        assert(info.isSuccess)
        assert(balance.isSuccess)
        assert(balance.get == "0 sats")
        assert(addr.isSuccess)
        assert(blockHash.isSuccess)
      }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    BitcoinSServer.reset()
  }
}
