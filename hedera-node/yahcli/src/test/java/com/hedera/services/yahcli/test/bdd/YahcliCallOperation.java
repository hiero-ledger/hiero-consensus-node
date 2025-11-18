// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.bdd;

import static com.hedera.services.yahcli.test.bdd.YahcliVerbs.prepend;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.yahcli.Yahcli;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import picocli.CommandLine;

/**
 * Executes a Yahcli command with the provided arguments against
 * the {@link SubProcessNetwork} targeted by the containing spec.
 */
public class YahcliCallOperation extends AbstractYahcliOperation<YahcliCallOperation> {
    static final Logger log = LogManager.getLogger(YahcliCallOperation.class);

    private final String[] args;

    @Nullable
    private Consumer<String> outputCb;

    @Nullable
    private Consumer<String> stderrCb;

    private String payer;

    private String nodeAccount;

    private boolean schedule = false;

    private boolean expectFail = false;

    public YahcliCallOperation(@NonNull final String[] args) {
        this.args = requireNonNull(args);
    }

    public YahcliCallOperation exposingOutputTo(@NonNull final Consumer<String> outputCb) {
        this.outputCb = requireNonNull(outputCb);
        return this;
    }

    /**
     * Configures the Yahcli command to expose stderr output to a callback.
     * This is useful for capturing validation errors and other error messages
     * that are printed to stderr (e.g., CommandLine.ParameterException).
     *
     * @param stderrCb the callback to receive stderr content
     * @return this operation instance for method chaining
     */
    public YahcliCallOperation exposingStderrTo(@NonNull final Consumer<String> stderrCb) {
        this.stderrCb = requireNonNull(stderrCb);
        return this;
    }

    /**
     * Configures the Yahcli command to use the specified account as the payer.
     * This adds the "-p" option to the command with the provided account ID.
     *
     * @param payer the account ID to be used as the transaction payer
     * @return this operation instance for method chaining
     */
    public YahcliCallOperation payingWith(String payer) {
        this.payer = payer;
        return this;
    }

    /**
     * Configures the Yahcli command to use the specified node account.
     * This adds the "-a" or "--node-account" option to the command with the provided account ID.
     *
     * @param nodeAccount the node account ID to use
     * @return this operation instance for method chaining
     */
    public YahcliCallOperation withNodeAccount(String nodeAccount) {
        this.nodeAccount = nodeAccount;
        return this;
    }

    /**
     * Configures the Yahcli command to be executed as a scheduled transaction.
     * This adds the "--schedule" flag to the command.
     *
     * @return this operation instance for method chaining
     */
    public YahcliCallOperation schedule() {
        this.schedule = true;
        return this;
    }

    /**
     * Indicates that the Yahcli command is expected to fail when executed.
     * If set, a non-zero exit code will be treated as a valid outcome rather than a test failure.
     *
     * @return this {@link YahcliCallOperation} instance for method chaining
     */
    public YahcliCallOperation expectFail() {
        this.expectFail = true;
        return this;
    }

    @Override
    protected YahcliCallOperation self() {
        return this;
    }

    @Override
    public Optional<Throwable> execFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        final var commandLine = new CommandLine(new Yahcli());
        var finalizedArgs = args;
        if (!workingDirProvidedViaArgs()) {
            final var w = workingDirOrThrow();
            finalizedArgs = prepend(finalizedArgs, "-w", w);
        }
        if (!configProvidedViaArgs()) {
            final var c = configLocOrThrow();
            finalizedArgs = prepend(finalizedArgs, "-c", c);
        }
        if (schedule) {
            finalizedArgs = prepend(finalizedArgs, "--schedule");
        }
        if (payer != null) {
            finalizedArgs = prepend(finalizedArgs, "-p", payer);
        }
        if (nodeAccount != null) {
            finalizedArgs = prepend(finalizedArgs, "-a", nodeAccount);
        }

        Path outputPath = null;
        Path errPath = null;
        final var originalErrStream = commandLine.getErr();
        try {
            if (outputCb != null) {
                outputPath = Files.createTempFile(TxnUtils.randomUppercase(8), ".out");
                finalizedArgs =
                        prepend(finalizedArgs, "-o", outputPath.toAbsolutePath().toString());
            }

            // Capture stderr if callback is provided
            if (stderrCb != null) {
                errPath = executeCommandWithStderrCapture(commandLine, finalizedArgs);
            } else {
                executeCommand(commandLine, finalizedArgs);
            }

            if (outputPath != null) {
                final var output = Files.readString(outputPath);
                outputCb.accept(output);
            }

            if (errPath != null) {
                final var stderr = Files.readString(errPath);
                stderrCb.accept(stderr);
            }
        } catch (Throwable t) {
            return Optional.of(t);
        } finally {
            // Restore original error stream
            commandLine.setErr(originalErrStream);

            if (outputPath != null) {
                try {
                    Files.deleteIfExists(outputPath);
                } catch (Exception e) {
                    log.warn("Failed to delete temporary output file: {}", outputPath, e);
                }
            }
            if (errPath != null) {
                try {
                    Files.deleteIfExists(errPath);
                } catch (Exception e) {
                    log.warn("Failed to delete temporary error file: {}", errPath, e);
                }
            }
        }
        return Optional.empty();
    }

    private void executeCommand(final CommandLine commandLine, final String[] finalizedArgs) {
        final int rc = commandLine.execute(finalizedArgs);
        if (rc != 0) {
            final var msg = "Yahcli command <<" + String.join(" ", finalizedArgs) + ">> failed with exit code " + rc;
            if (expectFail) {
                log.error(msg);
            } else {
                Assertions.fail(msg);
            }
        }
    }

    private Path executeCommandWithStderrCapture(final CommandLine commandLine, final String[] finalizedArgs)
            throws IOException {
        final var originalErr = System.err;
        final Path errPath = Files.createTempFile(TxnUtils.randomUppercase(8), ".err");
        try (final var fileWriter = new PrintWriter(Files.newBufferedWriter(errPath), true);
                PrintWriter errorPrintWriter =
                        new PrintWriter(new StderrCaptureWriter(fileWriter, originalErr), true)) {
            commandLine.setErr(errorPrintWriter);
            executeCommand(commandLine, finalizedArgs);
        }
        return errPath;
    }

    private boolean workingDirProvidedViaArgs() {
        return argsInclude("-w") || argsInclude("--working-dir");
    }

    private boolean configProvidedViaArgs() {
        return argsInclude("-c") || argsInclude("--config");
    }

    private boolean argsInclude(String s) {
        for (final var arg : args) {
            if (arg.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A Writer that captures stderr output to a file while simultaneously writing to the original System.err,
     * allowing stderr to be captured for inspection while still being visible in the console.
     */
    private static class StderrCaptureWriter extends Writer {
        private final PrintWriter fileWriter;
        private final PrintStream originalErr;

        StderrCaptureWriter(PrintWriter fileWriter, PrintStream originalErr) {
            this.fileWriter = requireNonNull(fileWriter);
            this.originalErr = requireNonNull(originalErr);
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            final var str = new String(cbuf, off, len);
            fileWriter.print(str);
            originalErr.print(str);
            originalErr.flush(); // Immediate flush to console
        }

        @Override
        public void flush() {
            fileWriter.flush();
            originalErr.flush();
        }

        @Override
        public void close() {
            fileWriter.close();
        }
    }
}
