// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.yahcli.suites.ClprTxnSuite;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Invokes the {@code sendMessage(bytes32,bytes32,bytes,bytes)} entry point on a deployed
 * connector wrapper contract (see {@code ClprSendMessage.sol}). The wrapper forwards
 * to the CLPR system contract precompile at {@code 0x16e}, which enqueues an outbound
 * CLPR message on the channel.
 *
 * <p>The wrapper contract must itself be the {@code connector_contract} on an active
 * Connector for the given channel — otherwise the precompile rejects the call.
 */
@Command(
        name = "send-message",
        subcommands = {HelpCommand.class},
        description = "Calls sendMessage(bytes32,bytes32,bytes,bytes) on a deployed connector wrapper "
                + "contract, which forwards to the CLPR system contract precompile.")
public class SendMessageCommand implements Callable<Integer> {

    /**
     * JSON ABI for {@code sendMessage(bytes32,bytes32,bytes,bytes) returns (uint64)} —
     * fed to Headlong's {@code Function.fromJson} by the BDD encoder.
     */
    private static final String SEND_MESSAGE_ABI = "{\"type\":\"function\",\"name\":\"sendMessage\","
            + "\"inputs\":["
            + "{\"name\":\"channelId\",\"type\":\"bytes32\"},"
            + "{\"name\":\"connectorId\",\"type\":\"bytes32\"},"
            + "{\"name\":\"targetApplication\",\"type\":\"bytes\"},"
            + "{\"name\":\"messageData\",\"type\":\"bytes\"}"
            + "],"
            + "\"outputs\":[{\"name\":\"messageId\",\"type\":\"uint64\"}]}";

    @ParentCommand
    ClprCommand clprCommand;

    @Option(
            names = {"--contract"},
            paramLabel = "<shard.realm.num>",
            required = true,
            description = "The deployed connector wrapper contract id "
                    + "(must be registered as the connector_contract for this channel).")
    String contract;

    @Option(
            names = {"--channel-id"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded channel id (must be exactly 32 bytes).")
    String channelIdHex;

    @Option(
            names = {"--connector-id"},
            paramLabel = "<hex>",
            required = true,
            description = "Hex-encoded connector id.")
    String connectorIdHex;

    @Option(
            names = {"--target-application"},
            paramLabel = "<hex>",
            description = "Hex-encoded target application bytes on the peer ledger "
                    + "(mutually exclusive with --target-application-file).")
    String targetApplicationHex;

    @Option(
            names = {"--target-application-file"},
            paramLabel = "<path>",
            description = "Path to a binary file containing the target application bytes.")
    String targetApplicationFile;

    @Option(
            names = {"--message-data"},
            paramLabel = "<hex>",
            description = "Hex-encoded message payload bytes (mutually exclusive with --message-data-file).")
    String messageDataHex;

    @Option(
            names = {"--message-data-file"},
            paramLabel = "<path>",
            description = "Path to a binary file containing the message payload bytes.")
    String messageDataFile;

    @Option(
            names = {"--gas"},
            paramLabel = "<gas>",
            defaultValue = "300000",
            description = "Gas limit for the contract call (default 300000).")
    long gas;

    @Override
    public Integer call() throws Exception {
        final var config = configFrom(clprCommand.getYahcli());

        final byte[] channelId = ClprArgs.requiredBytes("channel-id", channelIdHex);
        if (channelId.length != 32) {
            throw new IllegalArgumentException("--channel-id must be 32 bytes (got " + channelId.length + ")");
        }
        final byte[] connectorId = ClprArgs.requiredBytes("connector-id", connectorIdHex);
        if (connectorId.length != 32) {
            throw new IllegalArgumentException("--connector-id must be 32 bytes (got " + connectorId.length + ")");
        }

        final byte[] targetApplication =
                resolveBytes("target-application", targetApplicationHex, targetApplicationFile);
        final byte[] messageData = resolveBytes("message-data", messageDataHex, messageDataFile);

        final var op = contractCallWithFunctionAbi(
                        contract, SEND_MESSAGE_ABI, channelId, connectorId, targetApplication, messageData)
                .gas(gas);

        final var delegate = new ClprTxnSuite(config, "ClprSendMessage", op);
        delegate.runSuiteSync();
        return ClprOutcome.reportTxn(
                config,
                delegate,
                op,
                "sent CLPR message via " + contract + " (" + messageData.length + " bytes payload)",
                "could not send CLPR message via " + contract);
    }

    private static byte[] resolveBytes(final String name, final String hex, final String file) throws Exception {
        final boolean hasHex = hex != null && !hex.isBlank();
        final boolean hasFile = file != null && !file.isBlank();
        if (hasHex && hasFile) {
            throw new IllegalArgumentException("Specify only one of --" + name + " or --" + name + "-file");
        }
        if (hasFile) {
            return ClprArgs.readBytesFile(Path.of(file));
        }
        if (hasHex) {
            return ClprArgs.parseHex(hex);
        }
        throw new IllegalArgumentException("Must supply --" + name + " or --" + name + "-file");
    }
}
