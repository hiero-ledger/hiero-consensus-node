// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hedera.services.yahcli.Yahcli;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ParentCommand;

/**
 * Parent command for the {@code clpr} subcommand family. Routes to subcommands that submit
 * Cross-Ledger Protocol (CLPR) HAPI transactions and queries against a target network — the
 * full channel/connector lifecycle (register/complete/close), bundle submission and
 * redaction, ledger-configuration read/write, identity generation helpers, and a thin
 * {@code send-message} wrapper for the source-application sendMessage entry point.
 *
 * <p>Network targeting is inherited from the top-level {@code yahcli} options ({@code -n},
 * {@code -i}, {@code -a}); a CLPR run against two ledgers therefore reuses standard yahcli
 * config switching rather than introducing a CLPR-specific notion of "the other side".
 */
@CommandLine.Command(
        name = "clpr",
        subcommands = {
            HelpCommand.class,
            RegisterChannelCommand.class,
            CompleteChannelCommand.class,
            CloseChannelCommand.class,
            RegisterConnectorCommand.class,
            CompleteConnectorCommand.class,
            DeregisterConnectorCommand.class,
            SubmitBundleCommand.class,
            RedactMessageCommand.class,
            UpdateLedgerConfigurationCommand.class,
            GetLedgerConfigurationCommand.class,
            GenerateChannelIdentityCommand.class,
            GenerateConnectorIdentityCommand.class,
            SendMessageCommand.class,
        },
        description = "Submits Cross-Ledger Protocol (CLPR) gRPC transactions. "
                + "Target a different network with the top-level `-n`, `-i`, and `-a` options.")
public class ClprCommand implements Callable<Integer> {
    @ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws CommandLine.ParameterException {
        throw new CommandLine.ParameterException(yahcli.getSpec().commandLine(), "Please specify a clpr subcommand!");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }
}
