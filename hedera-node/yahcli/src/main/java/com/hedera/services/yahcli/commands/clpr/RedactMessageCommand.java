// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRedactMessage;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.yahcli.suites.ClprTxnSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Submits a {@code ClprRedactMessage} HAPI transaction to redact a previously sent CLPR
 * message. Required inputs are the hex-encoded {@code --channel-id} and the numeric
 * {@code --message-id}; after redaction the slot is delivered to the peer as an empty
 * one-of and the receiving side emits a {@code REDACTED} reply for that slot.
 */
@Command(
        name = "redact-message",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprRedactMessage transaction.")
public class RedactMessageCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--channel-id"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded channel id")
    String channelIdHex;

    @Option(
            names = {"--message-id"},
            paramLabel = "<id>",
            required = true,
            description = "Message id (long) to redact")
    long messageId;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var op = clprRedactMessage()
                .channelId(ClprArgs.requiredBytes("channel-id", channelIdHex))
                .messageId(messageId);
        final var delegate = new ClprTxnSuite(config, "ClprRedactMessage", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(
                config,
                delegate,
                op,
                "redacted CLPR message " + messageId,
                "could not redact CLPR message " + messageId);
    }
}
