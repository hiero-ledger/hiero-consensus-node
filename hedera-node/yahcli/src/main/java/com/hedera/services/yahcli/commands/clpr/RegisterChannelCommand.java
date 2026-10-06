// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.yahcli.suites.ClprTxnSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Submits Phase 1 (Commit) of the two-phase channel registration protocol — a
 * {@code ClprRegisterChannel} HAPI transaction that locks in an ownership commitment for
 * a new cross-ledger channel without revealing its identity yet. Pair with
 * {@link CompleteChannelCommand} (Phase 2: Reveal) to finish registration.
 */
@Command(
        name = "register-channel",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprRegisterChannel (Phase 1: Commit) transaction.")
public class RegisterChannelCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--commitment"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded ownership commitment (32 bytes)")
    String commitmentHex;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var op = clprRegisterChannel().ownershipCommitment(ClprArgs.requiredBytes("commitment", commitmentHex));
        final var delegate = new ClprTxnSuite(config, "ClprRegisterChannel", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(
                config,
                delegate,
                op,
                "registered CLPR channel commitment",
                "could not register CLPR channel commitment");
    }
}
