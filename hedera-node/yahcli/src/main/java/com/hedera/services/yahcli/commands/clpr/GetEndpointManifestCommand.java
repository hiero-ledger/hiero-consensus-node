// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetEndpointManifest;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.suites.ClprQuerySuite;
import com.hederahashgraph.api.proto.java.ClprEndpointManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Runs a {@code ClprGetEndpointManifest} query against the target network and prints the
 * ledger's current {@link ClprEndpointManifest} (the endpoints its peers dial for CLPR sync)
 * as proto3 JSON. Output goes to stdout by default or to a file via {@code --out <path>}. The
 * response's state-proof bytes are always surfaced (base64-encoded) under
 * {@code manifestStateProof} in the JSON; pass {@code --proof-path <path>} to also write the
 * raw serialized proof bytes to disk, for use as the peer's
 * {@code complete-channel --endpoint-manifest-proof}.
 *
 * <p>The manifest version and endpoint count are always printed, and the JSON carries a
 * top-level {@code finalized} flag. Genesis seeds a version-1 manifest with no endpoints, so a
 * manifest with {@code version >= 2} and at least one endpoint means the endpoint-manifest
 * reconciler has finalized a real one. The manifest is read from the same block-proven
 * snapshot as the proof, so the printed version is the one the proof attests.
 */
@Command(
        name = "get-endpoint-manifest",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprGetEndpointManifest query and prints the current endpoint manifest as JSON.")
public class GetEndpointManifestCommand implements Callable<Integer> {

    /** Genesis seeds manifest version 1; a version at or above this one was built by the reconciler. */
    static final long FINALIZED_MANIFEST_MIN_VERSION = 2L;

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--json"},
            description = "Always print the manifest as JSON to stdout (default if --out is not given)")
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
            description = "If set, writes the raw serialized manifest_state_proof bytes to this file. "
                    + "The same bytes are also surfaced (base64) under manifestStateProof in the JSON output. "
                    + "Suitable as input to the peer's complete-channel --endpoint-manifest-proof, which the "
                    + "Hiero TSS verifier (0.0.366) requires.")
    String proofPath;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());
        final var captured = new AtomicReference<ClprEndpointManifest>();
        final var capturedProof = new AtomicReference<ByteString>(ByteString.EMPTY);
        final var op =
                clprGetEndpointManifest().exposingManifestTo(captured::set).exposingProofTo(capturedProof::set);
        final var delegate = new ClprQuerySuite(config, "ClprGetEndpointManifest", op);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() != HapiSpec.SpecStatus.PASSED) {
            config.output().warn("FAILED - could not query CLPR endpoint manifest");
            return 1;
        }
        final var current = captured.get();
        if (current == null) {
            config.output().warn("FAILED - query succeeded but response had no endpoint manifest");
            return 1;
        }

        // Write raw proof bytes to disk if requested. Empty bytes means the ledger hasn't
        // produced a signed block snapshot yet — surface that as a warning rather than failing.
        final var proof = capturedProof.get();
        if (proofPath != null && !proofPath.isBlank()) {
            Files.write(Path.of(proofPath), proof.toByteArray());
            if (proof.isEmpty()) {
                config.output().warn("WARNING - manifest_state_proof was empty; wrote 0 bytes to " + proofPath);
            } else {
                config.output().info("SUCCESS - wrote " + proof.size() + " proof bytes to " + proofPath);
            }
        }

        final var finalized = isFinalized(current);
        final var summary =
                "endpoint manifest version=" + current.getVersion() + " endpoints=" + current.getEndpointsCount();
        if (finalized) {
            config.output().info(summary + " (finalized)");
        } else {
            config.output()
                    .warn("WARNING - " + summary + " is not finalized yet (genesis seeds version 1 with no "
                            + "endpoints); wait for the endpoint-manifest reconciler and retry");
        }

        var printer = JsonFormat.printer().preservingProtoFieldNames();
        if (includeDefaults) {
            printer = printer.alwaysPrintFieldsWithNoPresence();
        }
        final var manifestJson = printer.print(current);

        // Emit a top-level JSON object containing the finalized flag, the manifest, and the proof
        // (base64-encoded per proto3 JSON for bytes), mirroring get-ledger-configuration.
        final var combinedJson = """
                {
                  "finalized": %s,
                  "manifest": %s,
                  "manifestStateProof": "%s"
                }
                """.formatted(
                        finalized,
                        GetLedgerConfigurationCommand.indent(manifestJson, 2),
                        Base64.getEncoder().encodeToString(proof.toByteArray()));

        if (outFile != null && !outFile.isBlank()) {
            Files.writeString(Path.of(outFile), combinedJson);
            config.output().info("SUCCESS - wrote CLPR endpoint manifest to " + outFile);
        } else {
            System.out.println(combinedJson);
        }
        return 0;
    }

    /**
     * Whether {@code manifest} was finalized by the endpoint-manifest reconciler, as opposed to
     * being the version-1, endpoint-less manifest seeded at genesis.
     */
    static boolean isFinalized(final ClprEndpointManifest manifest) {
        return manifest.getVersion() >= FINALIZED_MANIFEST_MIN_VERSION && manifest.getEndpointsCount() >= 1;
    }
}
