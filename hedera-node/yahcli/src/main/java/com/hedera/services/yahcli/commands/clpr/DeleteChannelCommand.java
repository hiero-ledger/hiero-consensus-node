// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprDeleteChannel;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.yahcli.suites.ClprTxnSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Submits a {@code ClprDeleteChannel} HAPI transaction to request deletion of a
 * cross-ledger channel. Required input is the hex-encoded {@code --channel-id}; the
 * channel drains outstanding messages before reaching {@code CLOSED}.
 */
@Command(
        name = "delete-channel",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprDeleteChannel transaction.")
public class DeleteChannelCommand implements Callable<Integer> {

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
        final var op = clprDeleteChannel().channelId(ClprArgs.requiredBytes("channel-id", channelIdHex));
        final var delegate = new ClprTxnSuite(config, "ClprDeleteChannel", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(
                config, delegate, op, "submitted CLPR channel deletion", "could not delete CLPR channel");
    }
}
