// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.contracts;

import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.suites.ContractCreateSuite;
import com.hederahashgraph.api.proto.java.ContractID;
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
        name = "create",
        subcommands = {HelpCommand.class},
        description = "Creates a smart contract on the target network from a solc-style "
                + "hex-encoded init-code (.bin) file.")
public class CreateCommand implements Callable<Integer> {

    @ParentCommand
    ContractsCommand contractsCommand;

    @Option(
            names = {"--init-code-file"},
            paramLabel = "<path>",
            required = true,
            description = "Path to a solc-style .bin file (hex-encoded ASCII bytecode)")
    String initCodeFile;

    @Option(
            names = {"--gas"},
            paramLabel = "<amount>",
            defaultValue = "100000",
            description = "Gas to use for the contract create (default 100000)")
    long gas;

    @Option(
            names = {"--initial-balance"},
            paramLabel = "<tinybars>",
            defaultValue = "0",
            description = "Initial balance for the new contract in tinybars (default 0)")
    long initialBalance;

    @Option(
            names = {"--memo"},
            paramLabel = "<string>",
            description = "Entity memo for the new contract")
    String memo;

    @Option(
            names = {"--auto-renew-secs"},
            paramLabel = "<seconds>",
            description = "Auto-renew period for the new contract, in seconds")
    Long autoRenewSecs;

    @Option(
            names = {"--immutable"},
            description = "Create the contract without an admin key (immutable)")
    boolean immutable;

    @Option(
            names = {"--constructor-args"},
            paramLabel = "<hex>",
            description = "Hex-encoded ABI-encoded constructor args, appended to the init code. "
                    + "Mutually exclusive with --constructor-args-file.")
    String constructorArgsHex;

    @Option(
            names = {"--constructor-args-file"},
            paramLabel = "<path>",
            description = "Path to a binary file containing the ABI-encoded constructor args, "
                    + "appended to the init code. Mutually exclusive with --constructor-args.")
    String constructorArgsFile;

    @Override
    public Integer call() throws Exception {
        final var yahcli = contractsCommand.getYahcli();
        final var config = configFrom(yahcli);

        final var path = Path.of(initCodeFile);
        if (!Files.isRegularFile(path)) {
            throw new ParameterException(
                    yahcli.getSpec().commandLine(),
                    "--init-code-file must point to an existing regular file: " + initCodeFile);
        }

        final byte[] initCode = Files.readAllBytes(path);
        final byte[] constructorArgs = readConstructorArgs(yahcli);

        final var delegate = new ContractCreateSuite(
                config, initCode, gas, initialBalance, memo, autoRenewSecs, immutable, constructorArgs);
        delegate.runSuiteSync();

        final var spec = delegate.getFinalSpecs().getFirst();
        if (spec.getStatus() == HapiSpec.SpecStatus.PASSED) {
            final ContractID id = delegate.getCreatedContractId();
            final var idDesc = id == null
                    ? "(no id captured)"
                    : id.getShardNum() + "." + id.getRealmNum() + "." + id.getContractNum();
            config.output().info("SUCCESS - created contract " + idDesc + " from " + initCodeFile);
            return 0;
        }
        config.output()
                .warn("FAILED - could not create contract from " + initCodeFile + " "
                        + ContractCreateDiagnostics.describe(delegate)
                        + " (rerun with `-v INFO` for per-op detail)");
        return 1;
    }

    private byte[] readConstructorArgs(final Yahcli yahcli) throws IOException {
        final boolean hasHex = constructorArgsHex != null && !constructorArgsHex.isBlank();
        final boolean hasFile = constructorArgsFile != null && !constructorArgsFile.isBlank();
        if (hasHex && hasFile) {
            throw new ParameterException(
                    yahcli.getSpec().commandLine(),
                    "Pass only one of --constructor-args or --constructor-args-file, not both");
        }
        if (hasFile) {
            final var argsPath = Path.of(constructorArgsFile);
            if (!Files.isRegularFile(argsPath)) {
                throw new ParameterException(
                        yahcli.getSpec().commandLine(),
                        "--constructor-args-file must point to an existing regular file: " + constructorArgsFile);
            }
            return Files.readAllBytes(argsPath);
        }
        if (hasHex) {
            return parseHex(constructorArgsHex);
        }
        return null;
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
