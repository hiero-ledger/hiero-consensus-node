// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.yahcli.suites.ClprTxnSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Submits Phase 1 (Commit) of the two-phase connector registration protocol — a
 * {@code ClprRegisterConnector} HAPI transaction that locks in an ownership commitment
 * without yet revealing the connector identity. Pair with {@link CompleteConnectorCommand}
 * (Phase 2: Reveal) to finish registration; see the commit/reveal design in
 * {@code docs/superpowers/specs/connector-registration-redesign.md}.
 */
@Command(
        name = "register-connector",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprRegisterConnector (Phase 1: Commit) transaction.")
public class RegisterConnectorCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--commitment"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded connector commitment (32 bytes)")
    String commitmentHex;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var op = clprRegisterConnector().commitment(ClprArgs.requiredBytes("commitment", commitmentHex));
        final var delegate = new ClprTxnSuite(config, "ClprRegisterConnector", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(
                config,
                delegate,
                op,
                "registered CLPR connector commitment",
                "could not register CLPR connector commitment");
    }
}
