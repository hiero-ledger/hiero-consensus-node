// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCloseChannel;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.yahcli.suites.ClprTxnSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Submits a {@code ClprCloseChannel} HAPI transaction to initiate graceful closure
 * or explicitly force-close a cross-ledger channel.
 */
@Command(
        name = "close-channel",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprCloseChannel transaction.")
public class CloseChannelCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--channel-id"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded channel id (32 bytes)")
    String channelIdHex;

    @Option(
            names = {"--force-close"},
            description = "Close immediately and discard a batch of queued messages. Repeat to continue cleanup.")
    boolean forceClose;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var op = clprCloseChannel()
                .channelId(ClprArgs.requiredBytes("channel-id", channelIdHex))
                .forceClose(forceClose);
        final var delegate = new ClprTxnSuite(config, "ClprCloseChannel", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(config, delegate, op, "closed CLPR channel", "could not close CLPR channel");
    }
}
