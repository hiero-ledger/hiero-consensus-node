// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.yahcli.suites.ClprTxnSuite;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Submits a {@code ClprSubmitBundle} HAPI transaction carrying a verifier-checked
 * payload of cross-ledger messages for a given channel. The payload can be supplied as
 * a hex string ({@code --bundle-payload-hex}) or as raw bytes loaded from a file
 * ({@code --bundle-payload-file}); an optional {@code --endpoint-node-id} tags the
 * submitting endpoint for relay accounting.
 *
 * <p>This is the operator-facing manual counterpart to the in-process bundle submission
 * done automatically by {@code ClprChannelManager.performSync}.
 */
@Command(
        name = "submit-bundle",
        subcommands = {HelpCommand.class},
        description = "Submits a ClprSubmitBundle transaction.")
public class SubmitBundleCommand implements Callable<Integer> {

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--channel-id"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded channel id")
    String channelIdHex;

    @Option(
            names = {"--bundle-payload"},
            paramLabel = "<hex>",
            description = "Hex-encoded bundle payload bytes (mutually exclusive with --bundle-payload-file)")
    String bundlePayloadHex;

    @Option(
            names = {"--bundle-payload-file"},
            paramLabel = "<path>",
            description = "Path to a binary file with the bundle payload bytes")
    String bundlePayloadFile;

    @Option(
            names = {"--endpoint-node-id"},
            paramLabel = "<node>",
            defaultValue = "0",
            description = "Endpoint node id that produced the bundle")
    long endpointNodeId;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());

        final var fileProvided = bundlePayloadFile != null && !bundlePayloadFile.isBlank();
        final var hexProvided = bundlePayloadHex != null && !bundlePayloadHex.isBlank();
        if (fileProvided && hexProvided) {
            throw new IllegalArgumentException(
                    "Cannot specify both --bundle-payload and --bundle-payload-file; pick one.");
        }
        final byte[] payload;
        if (fileProvided) {
            payload = ClprArgs.readBytesFile(Path.of(bundlePayloadFile));
        } else if (hexProvided) {
            payload = ClprArgs.parseHex(bundlePayloadHex);
        } else {
            throw new IllegalArgumentException("Must supply --bundle-payload or --bundle-payload-file");
        }

        final var op = clprSubmitBundle()
                .channelId(ClprArgs.requiredBytes("channel-id", channelIdHex))
                .bundlePayload(payload)
                .endpointNodeId(endpointNodeId);

        final var delegate = new ClprTxnSuite(config, "ClprSubmitBundle", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(
                config,
                delegate,
                op,
                "submitted CLPR bundle (" + payload.length + " bytes)",
                "could not submit CLPR bundle");
    }
}
