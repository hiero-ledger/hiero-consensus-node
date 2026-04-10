// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.remote;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class that wraps native {@code ssh} and {@code scp} commands via {@link ProcessBuilder}. All SSH
 * configuration (user, identity file, port, proxy, etc.) is expected to be defined in {@code ~/.ssh/config}.
 */
public class SshExecutor {

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final String host;

    /**
     * Creates a new SSH executor for the given host.
     *
     * @param host the SSH host name (must match an entry in {@code ~/.ssh/config} or be directly reachable)
     */
    public SshExecutor(@NonNull final String host) {
        this.host = requireNonNull(host, "host must not be null");
    }

    /**
     * Returns the host this executor connects to.
     *
     * @return the SSH host name
     */
    @NonNull
    public String host() {
        return host;
    }

    /**
     * Executes a command on the remote host and waits for completion.
     *
     * @param command the command parts to execute remotely
     * @return the result containing exit code, stdout, and stderr
     */
    @NonNull
    public ExecResult exec(@NonNull final String... command) {
        return exec(DEFAULT_TIMEOUT, command);
    }

    /**
     * Starts a command on the remote host in the background without waiting for it to complete. Uses SSH with
     * {@code -f} flag to request SSH to go to background just before command execution, after the connection is
     * established.
     *
     * @param command the shell command to execute remotely in the background
     */
    public void execBackground(@NonNull final String command) {
        final List<String> sshCommand = new ArrayList<>();
        sshCommand.add("ssh");
        sshCommand.add("-o");
        sshCommand.add("BatchMode=yes");
        sshCommand.add("-o");
        sshCommand.add("ConnectTimeout=10");
        sshCommand.add("-f");
        sshCommand.add(host);
        sshCommand.add(command);

        log.debug("SSH execBackground on {}: {}", host, command);
        try {
            final Process process = new ProcessBuilder(sshCommand).start();
            // ssh -f forks to background after connecting; wait for it to complete the fork
            final boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("SSH background exec failed to fork within 30s on " + host);
            }
            if (process.exitValue() != 0) {
                final String stderr = new String(process.getErrorStream().readAllBytes());
                throw new IOException(
                        "SSH background exec failed on " + host + " (exit " + process.exitValue() + "): " + stderr);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("SSH background exec failed on " + host, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SSH background exec interrupted on " + host, e);
        }
    }

    /**
     * Executes a command on the remote host with a timeout.
     *
     * @param timeout maximum duration to wait for the command
     * @param command the command parts to execute remotely
     * @return the result containing exit code, stdout, and stderr
     */
    @NonNull
    public ExecResult exec(@NonNull final Duration timeout, @NonNull final String... command) {
        final List<String> sshCommand = buildSshCommand();
        sshCommand.add(String.join(" ", command));

        log.debug("SSH exec on {}: {}", host, String.join(" ", command));
        try {
            final ProcessBuilder pb = new ProcessBuilder(sshCommand);
            final Process process = pb.start();
            // Read stdout and stderr concurrently to prevent pipe buffer deadlocks.
            // If we read them sequentially, a full stderr buffer can block the process
            // before stdout is drained, causing a deadlock.
            final var stderrCapture = new java.io.ByteArrayOutputStream();
            final Thread stderrThread = Thread.ofVirtual().start(() -> {
                try {
                    process.getErrorStream().transferTo(stderrCapture);
                } catch (final IOException ignored) {
                }
            });
            final byte[] stdout = process.getInputStream().readAllBytes();
            stderrThread.join(timeout.toMillis());
            final boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("SSH command timed out after " + timeout + " on " + host);
            }
            return new ExecResult(process.exitValue(), new String(stdout), stderrCapture.toString());
        } catch (final IOException e) {
            throw new UncheckedIOException("SSH exec failed on " + host, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SSH exec interrupted on " + host, e);
        }
    }

    /**
     * Uploads a local file to the remote host via {@code scp}.
     *
     * @param localPath the local file path
     * @param remotePath the destination path on the remote host
     */
    public void upload(@NonNull final Path localPath, @NonNull final String remotePath) {
        final List<String> command = List.of(
                "scp", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", localPath.toString(), host + ":" + remotePath);
        runProcess(command, "SCP upload to " + host);
    }

    /**
     * Uploads a local directory recursively to the remote host via {@code scp}.
     *
     * @param localDir the local directory path
     * @param remotePath the destination path on the remote host
     */
    public void uploadDirectory(@NonNull final Path localDir, @NonNull final String remotePath) {
        final List<String> command = List.of(
                "scp",
                "-r",
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=10",
                localDir.toString(),
                host + ":" + remotePath);
        runProcess(command, "SCP upload directory to " + host);
    }

    /**
     * Uploads a local directory to the remote host using {@code tar} piped through {@code ssh}. This is significantly
     * faster than {@code scp -r} for directories with many files because it uses a single SSH connection and stream.
     *
     * @param localBaseDir the local base directory (tar is run from here)
     * @param dirName the subdirectory name within {@code localBaseDir} to upload
     * @param remoteDestDir the destination directory on the remote host where the directory will be extracted
     */
    public void uploadViaTar(
            @NonNull final Path localBaseDir, @NonNull final String dirName, @NonNull final String remoteDestDir) {
        // tar cf - -C <localBaseDir> <dirName> | ssh host "tar xf - -C <remoteDestDir>"
        final String command = String.format(
                "tar cf - -C %s %s | ssh -o BatchMode=yes -o ConnectTimeout=10 %s 'tar xf - -C %s'",
                localBaseDir, dirName, host, remoteDestDir);
        log.info("Uploading {}/{} to {}:{} via tar+ssh", localBaseDir, dirName, host, remoteDestDir);
        runProcess(List.of("sh", "-c", command), "tar+ssh upload to " + host);
    }

    /**
     * Downloads a file from the remote host to a local path via {@code scp}.
     *
     * @param remotePath the source path on the remote host
     * @param localPath the local destination path
     */
    public void download(@NonNull final String remotePath, @NonNull final Path localPath) {
        final List<String> command = List.of(
                "scp", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host + ":" + remotePath, localPath.toString());
        runProcess(command, "SCP download from " + host);
    }

    /**
     * Downloads a directory recursively from the remote host to a local path via {@code scp}.
     *
     * @param remotePath the source path on the remote host
     * @param localPath the local destination path
     */
    public void downloadDirectory(@NonNull final String remotePath, @NonNull final Path localPath) {
        final List<String> command = List.of(
                "scp",
                "-r",
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=10",
                host + ":" + remotePath,
                localPath.toString());
        runProcess(command, "SCP download directory from " + host);
    }

    /**
     * Starts an SSH local port forward in the background. The returned {@link Process} must be destroyed by the
     * caller when the tunnel is no longer needed.
     *
     * @param localPort the local port to listen on
     * @param remotePort the remote port to forward to
     * @return the background SSH process managing the tunnel
     */
    @NonNull
    public Process startPortForward(final int localPort, final int remotePort) {
        final List<String> command = List.of(
                "ssh",
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=10",
                "-o",
                "ExitOnForwardFailure=yes",
                "-N",
                "-L",
                localPort + ":localhost:" + remotePort,
                host);

        log.info("Starting SSH port forward: localhost:{} -> {}:{}", localPort, host, remotePort);
        try {
            final Process process = new ProcessBuilder(command).start();
            // Give the tunnel a moment to establish
            Thread.sleep(500);
            if (!process.isAlive()) {
                final String stderr = new String(process.getErrorStream().readAllBytes());
                throw new IOException("SSH port forward failed (exit " + process.exitValue() + "): " + stderr);
            }
            return process;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to start SSH port forward to " + host, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while starting SSH port forward to " + host, e);
        }
    }

    /**
     * Verifies that SSH connectivity to the host works.
     *
     * @throws UncheckedIOException if the connection cannot be established
     */
    public void verifyConnectivity() {
        final ExecResult result = exec(Duration.ofSeconds(15), "echo", "ok");
        if (result.exitCode() != 0 || !result.stdout().trim().equals("ok")) {
            throw new UncheckedIOException(new IOException("SSH connectivity check failed for " + host + ": exit="
                    + result.exitCode() + " stderr=" + result.stderr()));
        }
        log.info("SSH connectivity verified for {}", host);
    }

    /**
     * Finds a free local port for port forwarding.
     *
     * @return an available local port number
     */
    public static int findFreePort() {
        try (final ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to find a free port", e);
        }
    }

    private List<String> buildSshCommand() {
        final List<String> command = new ArrayList<>();
        command.add("ssh");
        command.add("-o");
        command.add("BatchMode=yes");
        command.add("-o");
        command.add("ConnectTimeout=10");
        command.add(host);
        return command;
    }

    private void runProcess(@NonNull final List<String> command, @NonNull final String description) {
        log.debug("{}: {}", description, String.join(" ", command));
        try {
            final ProcessBuilder pb = new ProcessBuilder(command);
            // Merge stderr into stdout to prevent pipe buffer deadlocks when the child process
            // produces large amounts of stderr output (e.g. tar xattr warnings on macOS).
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            // Drain combined output to prevent buffer-full blocking
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            final boolean finished = process.waitFor(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException(description + " timed out after " + DEFAULT_TIMEOUT);
            }
            if (process.exitValue() != 0) {
                throw new IOException(description + " failed (exit " + process.exitValue() + ")");
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(description + " failed", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(description + " interrupted", e);
        }
    }

    /**
     * Result of executing a remote command.
     *
     * @param exitCode the process exit code
     * @param stdout the standard output content
     * @param stderr the standard error content
     */
    public record ExecResult(
            int exitCode, @NonNull String stdout, @NonNull String stderr) {}
}
