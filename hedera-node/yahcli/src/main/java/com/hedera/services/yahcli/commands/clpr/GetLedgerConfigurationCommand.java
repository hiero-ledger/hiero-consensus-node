// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.suites.ClprQuerySuite;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Runs a {@code ClprGetLedgerConfiguration} query against the target network and prints
 * the current {@link ClprLedgerConfiguration} as proto3 JSON. Output goes to stdout by
 * default or to a file via {@code --out <path>}. The response's state-proof bytes are
 * always surfaced (base64-encoded) under {@code configurationStateProof} in the JSON;
 * pass {@code --proof-path <path>} to also write the raw serialized proof bytes to disk
 * so the caller can independently verify against the source ledger's TSS signature.
 */
@Command(
        name = "get-ledger-configuration",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprGetLedgerConfiguration query and prints the current configuration as JSON.")
public class GetLedgerConfigurationCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--json"},
            description = "Always print the configuration as JSON to stdout (default if --out is not given)")
    boolean json;

    @Option(
            names = {"--out"},
            paramLabel = "<path>",
            description = "If set, writes the JSON to this file instead of stdout")
    String outFile;

    @Option(
            names = {"--include-defaults"},
            description = "Include proto3 default-valued fields in the JSON output")
    boolean includeDefaults;

    @Option(
            names = {"--proof-path"},
            paramLabel = "<path>",
            description = "If set, writes the raw serialized configuration_state_proof bytes to this file. "
                    + "The same bytes are also surfaced (base64) under configurationStateProof in the JSON output. "
                    + "Suitable as input to complete-channel --config-proof when the peer ledger uses the "
                    + "verifier contract that accepts a StateProof (e.g. one that delegates to "
                    + "the CLPR system contract precompile's verifyConfig operation).")
    String proofPath;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var captured = new AtomicReference<ClprLedgerConfiguration>();
        final var capturedProof = new AtomicReference<ByteString>(ByteString.EMPTY);
        final var op = clprGetLedgerConfiguration().exposingTo(captured::set).exposingProofTo(capturedProof::set);
        final var delegate = new ClprQuerySuite(config, "ClprGetLedgerConfiguration", op);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() != HapiSpec.SpecStatus.PASSED) {
            config.output().warn("FAILED - could not query CLPR ledger configuration");
            return 1;
        }
        final var current = captured.get();
        if (current == null) {
            config.output().warn("FAILED - query succeeded but response had no configuration");
            return 1;
        }

        // Write raw proof bytes to disk if requested. Empty bytes means the peer hasn't
        // produced a signed block snapshot yet — surface that as a warning rather than failing.
        final var proof = capturedProof.get();
        if (proofPath != null && !proofPath.isBlank()) {
            Files.write(Path.of(proofPath), proof.toByteArray());
            if (proof.isEmpty()) {
                config.output().warn("WARNING - configuration_state_proof was empty; wrote 0 bytes to " + proofPath);
            } else {
                config.output().info("SUCCESS - wrote " + proof.size() + " proof bytes to " + proofPath);
            }
        }

        var printer = JsonFormat.printer().preservingProtoFieldNames();
        if (includeDefaults) {
            printer = printer.includingDefaultValueFields();
        }
        final var configJson = printer.print(current);

        // Emit a top-level JSON object containing both the configuration and the proof
        // (base64-encoded per proto3 JSON for bytes). Surfacing the proof here means a
        // single call gives the operator everything they need for register/complete.
        final var combinedJson = """
                {
                  "configuration": %s,
                  "configurationStateProof": "%s"
                }
                """.formatted(
                        indent(configJson, 2), java.util.Base64.getEncoder().encodeToString(proof.toByteArray()));

        if (outFile != null && !outFile.isBlank()) {
            Files.writeString(Path.of(outFile), combinedJson);
            config.output().info("SUCCESS - wrote CLPR ledger configuration to " + outFile);
        } else {
            System.out.println(combinedJson);
        }
        return 0;
    }

    /** Indents every line of {@code s} (except the first) by {@code spaces} spaces, so the
     *  nested JsonFormat output sits cleanly inside our outer object. */
    private static String indent(final String s, final int spaces) {
        final var pad = " ".repeat(spaces);
        return s.replace("\n", "\n" + pad).stripTrailing();
    }
}
