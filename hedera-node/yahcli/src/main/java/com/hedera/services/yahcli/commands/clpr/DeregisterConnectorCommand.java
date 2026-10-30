// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprDeregisterConnector;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.yahcli.suites.ClprTxnSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Submits a {@code ClprDeregisterConnector} HAPI transaction to remove a connector from a
 * channel and unlock its staked funds to the supplied {@code --stake-recipient}
 * account. Required inputs are {@code --channel-id}, {@code --connector-id}, and the
 * stake recipient.
 */
@Command(
        name = "deregister-connector",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprDeregisterConnector transaction.")
public class DeregisterConnectorCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--channel-id"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded channel id this connector serves")
    String channelIdHex;

    @Option(
            names = {"--connector-id"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded connector id")
    String connectorIdHex;

    @Option(
            names = {"--stake-recipient"},
            paramLabel = "<shard.realm.num>",
            required = true,
            description = "Account that receives the returned locked stake (must also sign)")
    String stakeRecipient;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var op = clprDeregisterConnector()
                .channelId(ClprArgs.requiredBytes("channel-id", channelIdHex))
                .connectorId(ClprArgs.requiredBytes("connector-id", connectorIdHex))
                .stakeRecipient(ClprArgs.parseAccountId(stakeRecipient));
        final var delegate = new ClprTxnSuite(config, "ClprDeregisterConnector", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(
                config, delegate, op, "deregistered CLPR connector", "could not deregister CLPR connector");
    }
}
