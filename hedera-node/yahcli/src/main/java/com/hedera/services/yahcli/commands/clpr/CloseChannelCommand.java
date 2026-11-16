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
            description = "Close this side immediately and examine up to 16 queued message IDs per call. "
                    + "Repeat for remaining batches; SUCCESS also covers an already-complete no-op. "
                    + "The peer administrator must separately close and clean up its side.")
    boolean forceClose;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var op = clprCloseChannel()
                .channelId(ClprArgs.requiredBytes("channel-id", channelIdHex))
                .forceClose(forceClose);
        final var delegate = new ClprTxnSuite(config, "ClprCloseChannel", op);
        delegate.runSuiteSync();
        final var successAction = forceClose
                ? "force-close cleanup request accepted; SUCCESS does not confirm cleanup is complete. "
                        + "Check node cleanup logs for complete=true; repeat if more batches remain. "
                        + "The peer administrator must separately close and clean up its side"
                : "requested graceful CLPR channel closure";
        return ClprOutcome.reportTxn(config, delegate, op, successAction, "could not close CLPR channel");
    }
}
