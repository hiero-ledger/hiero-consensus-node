// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.google.protobuf.util.JsonFormat;
import com.hedera.services.yahcli.suites.ClprTxnSuite;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Submits a {@code ClprUpdateLedgerConfiguration} HAPI transaction built from a JSON
 * description of the new {@link ClprLedgerConfiguration}. Bytes fields
 * ({@code service_address}, {@code tls_certificate}, {@code ecdsa_signing_key},
 * {@code account_id}) must be base64-encoded per proto3 JSON conventions;
 * {@code protocol_version}, {@code chain_id}, and {@code timestamp} are ignored by the
 * handler and may be omitted.
 */
@Command(
        name = "update-ledger-configuration",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprUpdateLedgerConfiguration transaction. "
                + "Reads a JSON description of the ClprLedgerConfiguration. "
                + "Bytes fields (service_address, tls_certificate, ecdsa_signing_key, account_id) "
                + "MUST be base64-encoded per proto3 JSON. "
                + "protocol_version, chain_id, and timestamp are ignored by the handler.")
public class UpdateLedgerConfigurationCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--config-file"},
            paramLabel = "<path>",
            required = true,
            description = "Path to a JSON file describing the ClprLedgerConfiguration")
    String configFile;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var jsonText = Files.readString(Path.of(configFile));
        final var builder = ClprLedgerConfiguration.newBuilder();
        JsonFormat.parser().ignoringUnknownFields().merge(jsonText, builder);
        final var op = clprUpdateLedgerConfiguration().configuration(builder.build());

        final var delegate = new ClprTxnSuite(config, "ClprUpdateLedgerConfiguration", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(
                config,
                delegate,
                op,
                "updated CLPR ledger configuration",
                "could not update CLPR ledger configuration");
    }
}
