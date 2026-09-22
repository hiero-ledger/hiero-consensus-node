// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.contracts;

import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.suites.ContractCallSuite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "call",
        subcommands = {HelpCommand.class},
        description = "Submits a ContractCall transaction against an existing contract id with raw EVM call data.")
public class CallCommand implements Callable<Integer> {

    @ParentCommand
    ContractsCommand contractsCommand;

    @Option(
            names = {"--contract-id"},
            paramLabel = "<id>",
            required = true,
            description = "Target contract id, either as a 0.<realm>.<num> literal or a 40-char EVM hex address")
    String contractId;

    @Option(
            names = {"--call-data"},
            paramLabel = "<hex>",
            description = "Hex-encoded raw call data (selector + ABI-encoded args). "
                    + "Mutually exclusive with --call-data-file. May be empty for a no-arg fallback call.")
    String callDataHex;

    @Option(
            names = {"--call-data-file"},
            paramLabel = "<path>",
            description = "Path to a binary file containing the raw call data bytes")
    String callDataFile;

    @Option(
            names = {"--gas"},
            paramLabel = "<amount>",
            defaultValue = "100000",
            description = "Gas to use for the call (default 100000)")
    long gas;

    @Option(
            names = {"--amount"},
            paramLabel = "<tinybars>",
            defaultValue = "0",
            description = "msg.value to send with the call, in tinybars (default 0)")
    long amount;

    @Override
    public Integer call() throws Exception {
        final var yahcli = contractsCommand.getYahcli();
        final var config = configFrom(yahcli);

        if (callDataHex != null && !callDataHex.isBlank() && callDataFile != null && !callDataFile.isBlank()) {
            throw new ParameterException(
                    yahcli.getSpec().commandLine(), "Pass only one of --call-data or --call-data-file, not both");
        }

        final byte[] callData;
        if (callDataFile != null && !callDataFile.isBlank()) {
            final var path = Path.of(callDataFile);
            if (!Files.isRegularFile(path)) {
                throw new ParameterException(
                        yahcli.getSpec().commandLine(),
                        "--call-data-file must point to an existing regular file: " + callDataFile);
            }
            callData = readBytes(path);
        } else if (callDataHex != null) {
            callData = parseHex(callDataHex);
        } else {
            callData = new byte[0];
        }

        final var delegate = new ContractCallSuite(config, contractId, callData, gas, amount);
        delegate.runSuiteSync();

        final var spec = delegate.getFinalSpecs().getFirst();
        final var op = delegate.getOp();
        final var precheck = op.getActualPrecheck();
        final var status = op.getLastReceipt() != null ? op.getActualStatus().toString() : "n/a";
        if (spec.getStatus() == HapiSpec.SpecStatus.PASSED) {
            config.output()
                    .info("SUCCESS - called contract " + contractId
                            + " with " + callData.length + " bytes of call data"
                            + " (precheck=" + precheck + ", status=" + status + ")");
            return 0;
        }
        config.output()
                .warn("FAILED - could not call contract " + contractId + " (precheck=" + precheck + ", status=" + status
                        + ")");
        return 1;
    }

    private static byte[] readBytes(final Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    private static byte[] parseHex(final String raw) {
        var s = raw.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.isEmpty()) {
            return new byte[0];
        }
        if ((s.length() & 1) == 1) {
            throw new IllegalArgumentException("Hex string has an odd number of characters: " + raw);
        }
        final var out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(s.charAt(2 * i), 16);
            final int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex character in: " + raw);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
