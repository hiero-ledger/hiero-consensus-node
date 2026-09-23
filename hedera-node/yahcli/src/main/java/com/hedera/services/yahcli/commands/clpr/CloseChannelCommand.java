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
 * Submits a {@code ClprCloseChannel} HAPI transaction to terminate an active
 * cross-ledger channel. Required input is the hex-encoded {@code --channel-id}; the
 * channel moves to {@code CLOSED} and stops accepting further bundles or messages.
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

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var op = clprCloseChannel().channelId(ClprArgs.requiredBytes("channel-id", channelIdHex));
        final var delegate = new ClprTxnSuite(config, "ClprCloseChannel", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(config, delegate, op, "closed CLPR channel", "could not close CLPR channel");
    }
}
