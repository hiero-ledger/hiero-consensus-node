// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.remote;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.sloth.fixtures.ProfilerEvent;

/**
 * A helper class that manages Java Flight Recorder (JFR) profiling of a consensus node running on a remote host via
 * SSH. Mirrors the functionality of {@code ContainerProfiler} but executes commands remotely.
 */
public class RemoteProfiler {

    private static final Logger log = LogManager.getLogger();

    private static final ProfilerEvent[] DEFAULT_PROFILER_EVENTS = {ProfilerEvent.CPU, ProfilerEvent.ALLOCATION};

    private final NodeId selfId;
    private final SshExecutor sshExecutor;
    private final Path localOutputDirectory;
    private final String remoteWorkDir;

    private String profilingOutputFilename;
    private Duration samplingInterval;
    private ProfilerEvent[] profilerEvents;
    private String pid;

    /**
     * Constructs a RemoteProfiler for the specified node.
     *
     * @param selfId the NodeId of the node being profiled
     * @param sshExecutor the SSH executor for the remote host
     * @param localOutputDirectory the local base directory for storing profiling results
     * @param remoteWorkDir the working directory of this node on the remote host
     */
    public RemoteProfiler(
            @NonNull final NodeId selfId,
            @NonNull final SshExecutor sshExecutor,
            @NonNull final Path localOutputDirectory,
            @NonNull final String remoteWorkDir) {
        this.selfId = requireNonNull(selfId);
        this.sshExecutor = requireNonNull(sshExecutor);
        this.localOutputDirectory = requireNonNull(localOutputDirectory);
        this.remoteWorkDir = requireNonNull(remoteWorkDir);
    }

    /**
     * Starts profiling with the specified output filename, sampling interval, and events.
     *
     * @param outputFilename the output filename for profiling results
     * @param samplingInterval the sampling interval for timed events
     * @param profilerEvents the profiling events to enable
     */
    public void startProfiling(
            @NonNull final String outputFilename,
            @NonNull final Duration samplingInterval,
            @NonNull final ProfilerEvent... profilerEvents) {
        if (profilingOutputFilename != null) {
            throw new IllegalStateException("Profiling was already started.");
        }
        this.profilingOutputFilename = requireNonNull(outputFilename);
        this.samplingInterval = requireNonNull(samplingInterval);
        this.profilerEvents = profilerEvents.length == 0 ? DEFAULT_PROFILER_EVENTS : profilerEvents;

        // Get the Java process PID
        final String getPidCommand = "jps | grep \"ConsensusNodeMain\" | head -1 | awk '{print $1}'";
        final SshExecutor.ExecResult pidResult = sshExecutor.exec("sh", "-c", getPidCommand);
        if (pidResult.exitCode() != 0 || pidResult.stdout().trim().isEmpty()) {
            fail("Failed to find ConsensusNodeMain process on node " + selfId.id());
        }
        pid = pidResult.stdout().trim();
        log.info("Found ConsensusNodeMain process with PID {} on node {}", pid, selfId.id());

        // Ensure profiling directory exists
        sshExecutor.exec("mkdir", "-p", "/tmp/profiling");

        // Generate and write JFC configuration
        final String jfcContent = generateJfcConfiguration();
        final String writeJfcCommand =
                String.format("cat > /tmp/profiling/sloth.jfc << 'JFCEOF'\n%s\nJFCEOF", jfcContent);
        sshExecutor.exec("sh", "-c", writeJfcCommand);

        // Start JFR
        final String startJfrCommand =
                String.format("jcmd %s JFR.start name=sloth-profile settings=/tmp/profiling/sloth.jfc", pid);
        final SshExecutor.ExecResult result = sshExecutor.exec("sh", "-c", startJfrCommand);
        if (result.exitCode() != 0) {
            fail("Failed to start JFR profiling on node " + selfId.id() + ": " + result.stderr());
        }
        log.info("JFR.start output: {}", result.stdout());

        log.info(
                "Started JFR profiling on node {} with {}ms sampling and events {} -> {}",
                selfId.id(),
                this.samplingInterval.toMillis(),
                Stream.of(this.profilerEvents).map(Enum::name).collect(Collectors.joining(", ")),
                outputFilename);
    }

    /**
     * Stops profiling and downloads the profiling results from the remote host.
     */
    public void stopProfiling() {
        if (profilingOutputFilename == null) {
            throw new IllegalStateException("Profiling was not started. Call startProfiling() first.");
        }

        try {
            final String remotePath = "/tmp/profiling/" + profilingOutputFilename;

            // Dump the recording
            final String dumpCommand =
                    String.format("jcmd %s JFR.dump name=sloth-profile filename=%s", pid, remotePath);
            final SshExecutor.ExecResult dumpResult = sshExecutor.exec("sh", "-c", dumpCommand);
            if (dumpResult.exitCode() != 0 || dumpResult.stdout().contains("Dump failed")) {
                fail("Failed to dump JFR profiling on node " + selfId.id() + ": " + dumpResult.stdout());
            }

            // Stop the recording
            final String stopCommand = String.format("jcmd %s JFR.stop name=sloth-profile", pid);
            final SshExecutor.ExecResult result = sshExecutor.exec("sh", "-c", stopCommand);
            if (result.exitCode() != 0) {
                fail("Failed to stop JFR profiling on node " + selfId.id() + ": " + result.stderr());
            }
            log.info("Stopped JFR profiling on node {}", selfId.id());

            // Download results
            final Path hostPath = localOutputDirectory.resolve(profilingOutputFilename);
            Files.createDirectories(hostPath.getParent());
            sshExecutor.download(remotePath, hostPath);
            log.info("Downloaded JFR profiling result from node {} to {}", selfId.id(), hostPath.toAbsolutePath());

            profilingOutputFilename = null;
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            fail("Failed to stop profiling and download results from node " + selfId.id(), e);
        }
    }

    @SuppressWarnings("StringConcatenationInsideStringBufferAppend")
    private String generateJfcConfiguration() {
        final StringBuilder jfc = new StringBuilder();
        jfc.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        jfc.append(
                "<configuration version=\"2.0\" label=\"sloth Custom Profile\" description=\"Custom JFR configuration for sloth tests\" provider=\"sloth\">\n");

        final Stream<String> jfrEventNames = Stream.of(this.profilerEvents)
                .flatMap(event -> event.getJfrEventNames().stream())
                .distinct();

        jfrEventNames.forEach(eventName -> {
            jfc.append("  <event name=\"").append(eventName).append("\">\n");
            jfc.append("    <setting name=\"enabled\">true</setting>\n");

            switch (eventName) {
                case "jdk.ExecutionSample", "jdk.NativeMethodSample" ->
                    jfc.append("    <setting name=\"period\">" + this.samplingInterval.toMillis() + " ms</setting>\n");
                case "jdk.ObjectAllocationSample" -> {
                    jfc.append("    <setting name=\"throttle\">150/s</setting>\n");
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
                }
                case "jdk.JavaMonitorEnter",
                        "jdk.JavaMonitorWait",
                        "jdk.ThreadPark",
                        "jdk.FileRead",
                        "jdk.FileWrite",
                        "jdk.SocketRead",
                        "jdk.SocketWrite",
                        "jdk.ThreadSleep" -> {
                    jfc.append("    <setting name=\"threshold\">10 ms</setting>\n");
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
                }
                case "jdk.JavaExceptionThrow",
                        "jdk.CompilerFailure",
                        "jdk.Deoptimization",
                        "jdk.ObjectAllocationInNewTLAB",
                        "jdk.ObjectAllocationOutsideTLAB",
                        "jdk.MetaspaceGCThreshold",
                        "jdk.MetaspaceAllocationFailure",
                        "jdk.MetaspaceOOM",
                        "jdk.BiasedLockRevocation",
                        "jdk.BiasedLockClassRevocation" ->
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
                case "jdk.Compilation" -> jfc.append("    <setting name=\"threshold\">100 ms</setting>\n");
                case "jdk.TLSHandshake" -> jfc.append("    <setting name=\"threshold\">10 ms</setting>\n");
                default -> {
                    // Use default settings for other events
                }
            }

            jfc.append("  </event>\n");
        });

        jfc.append("  <!-- Essential JVM metadata events -->\n");
        jfc.append("  <event name=\"jdk.ActiveRecording\"><setting name=\"enabled\">true</setting></event>\n");
        jfc.append("  <event name=\"jdk.ActiveSetting\"><setting name=\"enabled\">true</setting></event>\n");
        jfc.append("  <event name=\"jdk.JVMInformation\"><setting name=\"enabled\">true</setting></event>\n");
        jfc.append("  <event name=\"jdk.OSInformation\"><setting name=\"enabled\">true</setting></event>\n");
        jfc.append("  <event name=\"jdk.CPUInformation\"><setting name=\"enabled\">true</setting></event>\n");
        jfc.append(
                "  <event name=\"jdk.CPULoad\"><setting name=\"enabled\">true</setting><setting name=\"period\">1 s</setting></event>\n");
        jfc.append(
                "  <event name=\"jdk.ThreadCPULoad\"><setting name=\"enabled\">true</setting><setting name=\"period\">1 s</setting></event>\n");

        jfc.append("</configuration>");
        return jfc.toString();
    }
}
