// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

/**
 * A virtual terminal for running bash commands.
 */
public final class VirtualTerminal {

    private boolean printStdout;
    private boolean printStderr;
    private boolean printCommand;
    private boolean printExitCode;
    private boolean throwOnError;
    private boolean progressIndicatorEnabled;

    private final ProgressIndicator progressIndicator = new ProgressIndicator(1);

    public VirtualTerminal() {}

    /**
     * Set if stdout should be printed to System.out.
     *
     * @param printStdout if true, stdout will be printed to System.out
     * @return this
     */
    @NonNull
    public VirtualTerminal setPrintStdout(boolean printStdout) {
        this.printStdout = printStdout;
        return this;
    }

    /**
     * Set if stderr should be printed to System.err.
     *
     * @param printStderr if true, stderr will be printed to System.err
     * @return this
     */
    @NonNull
    public VirtualTerminal setPrintStderr(boolean printStderr) {
        this.printStderr = printStderr;
        return this;
    }

    /**
     * Set if the command should be printed to System.out.
     *
     * @param printCommand if true, the command will be printed to System.out
     * @return this
     */
    @NonNull
    public VirtualTerminal setPrintCommand(boolean printCommand) {
        this.printCommand = printCommand;
        return this;
    }

    /**
     * Set if the exit code should be printed to System.out.
     *
     * @param printExitCode if true, the exit code will be printed to System.out
     * @return this
     */
    @NonNull
    public VirtualTerminal setPrintExitCode(boolean printExitCode) {
        this.printExitCode = printExitCode;
        return this;
    }

    /**
     * Set if an exception should be thrown if the command fails.
     *
     * @param throwOnError if true, an exception will be thrown if the command fails
     * @return this
     */
    @NonNull
    public VirtualTerminal setThrowOnError(boolean throwOnError) {
        this.throwOnError = throwOnError;
        return this;
    }

    /**
     * Set if the progress indicator should be incremented after each command.
     *
     * @param progressIndicatorEnabled if true, the progress indicator will be incremented after each command
     * @return this
     */
    @NonNull
    public VirtualTerminal setProgressIndicatorEnabled(boolean progressIndicatorEnabled) {
        this.progressIndicatorEnabled = progressIndicatorEnabled;
        return this;
    }

    /**
     * Get the progress indicator.
     *
     * @return the progress indicator
     */
    @NonNull
    public ProgressIndicator getProgressIndicator() {
        return progressIndicator;
    }

    /**
     * Run a shell command and return the result.
     *
     * @param command the command to run
     * @return the result of the command
     */
    @NonNull
    public CommandResult run(@NonNull final String... command) {
        try {

            if (printCommand) {
                System.out.println("VirtualTerminal > " + String.join(" ", command));
            }

            final StringBuilder in = new StringBuilder();
            final StringBuilder err = new StringBuilder();

            final int exitCode =
                    run(s -> in.append(s).append("\n"), s -> err.append(s).append("\n"), command);

            final String outString = in.toString();
            final String errString = err.toString();

            if (printStdout && !outString.isEmpty()) {
                System.out.println(outString);
            }
            if (printStderr && !errString.isEmpty()) {
                System.err.println(errString);
            }
            if (printExitCode) {
                System.out.println("Exit code: " + exitCode);
            }

            if (throwOnError && exitCode != 0) {
                throw new RuntimeException("Command failed: " + String.join(" ", command));
            }

            return new CommandResult(exitCode, outString, errString);
        } finally {
            if (progressIndicatorEnabled) {
                progressIndicator.increment();
            }
        }
    }

    /**
     * Run a shell command and return the result.
     *
     * @param stdoutHandler each line of stdout will be passed to this handler as it is received
     * @param stderrHandler each line of stderr will be passed to this handler, stderr is not read until stdout has been
     *                      fully read
     * @param command       the command to run
     * @return the result of the command
     */
    public int run(
            @NonNull final Consumer<String> stdoutHandler,
            @NonNull final Consumer<String> stderrHandler,
            @NonNull final String... command) {

        try {
            if (printCommand) {
                System.out.println("VirtualTerminal > " + String.join(" ", command));
            }

            final Runtime runtime = Runtime.getRuntime();
            final Process process = runtime.exec(command);

            final BufferedReader stdin = new BufferedReader(new InputStreamReader(process.getInputStream()));
            final BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = stdin.readLine()) != null) {
                stdoutHandler.accept(line);
            }

            while ((line = stderr.readLine()) != null) {
                stderrHandler.accept(line);
            }

            while (process.isAlive()) {
                MILLISECONDS.sleep(1);
            }

            final int exitCode = process.exitValue();
            if (printExitCode) {
                System.out.println("Exit code: " + exitCode);
            }

            if (throwOnError && exitCode != 0) {
                throw new RuntimeException("Command failed: " + String.join(" ", command));
            }

            return exitCode;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while running command", e);
        } finally {
            if (progressIndicatorEnabled) {
                progressIndicator.increment();
            }
        }
    }
}
