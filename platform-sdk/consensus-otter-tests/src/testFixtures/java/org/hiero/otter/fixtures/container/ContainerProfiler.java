// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.ProfilerEvent;
import org.testcontainers.containers.Container.ExecResult;

/**
 * A helper class that manages Java Flight Recorder (JFR) profiling the consensus node running inside a container.
 */
public class ContainerProfiler {

    private static final Logger log = LogManager.getLogger();

    private static final ProfilerEvent[] DEFAULT_PROFILER_EVENTS = {ProfilerEvent.CPU, ProfilerEvent.ALLOCATION};

    /** The NodeId of the node being profiled. */
    private final NodeId selfId;

    /** The image used to run the consensus node. */
    private final ContainerImage container;

    /** The local base directory where artifacts copied from the container will be stored. */
    private final Path localOutputDirectory;

    /** The output filename for profiling results, set when profiling is started */
    private String profilingOutputFilename;

    /** The sampling interval for timed events */
    private Duration samplingInterval;

    /** The profiling events being recorded */
    private ProfilerEvent[] profilerEvents;

    /** The PID of the Java process being profiled */
    private String pid;

    /**
     *  Constructs a ContainerProfiler for the specified node container.
     *
     * @param selfId the NodeId of the node being profiled
     * @param container the container running the consensus node
     * @param localOutputDirectory the local base directory for storing profiling results
     */
    public ContainerProfiler(
            @NonNull final NodeId selfId,
            @NonNull final ContainerImage container,
            @NonNull final Path localOutputDirectory) {
        this.selfId = requireNonNull(selfId);
        this.container = requireNonNull(container);
        this.localOutputDirectory = requireNonNull(localOutputDirectory);
    }

    /**
     * Starts profiling with the specified output filename, sampling interval, and events.
     *
     * @param outputFilename the output filename for profiling results
     * @param samplingInterval the sampling interval for timed events, or null for default
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

        try {
            // Get the Java process PID
            final String getPidCommand = "jps | grep \"ConsensusNodeMain\" | head -1 | awk '{print $1}'";
            final ExecResult pidResult = container.execInContainer("sh", "-c", getPidCommand);
            if (pidResult.getExitCode() != 0 || pidResult.getStdout().trim().isEmpty()) {
                fail("Failed to find ConsensusNodeMain process on node " + selfId.id());
            }
            pid = pidResult.getStdout().trim();
            log.info("Found ConsensusNodeMain process with PID {} on node {}", pid, selfId.id());

            // Generate custom JFC configuration based on requested events
            final String jfcContent = generateJfcConfiguration();

            // Ensure the profiling directory exists
            final ExecResult mkdirResult =
                    container.execInContainer("sh", "-c", "mkdir -p /tmp/profiling && chmod 777 /tmp/profiling");
            if (mkdirResult.getExitCode() != 0) {
                throw new IOException("Failed to create profiling directory on node " + selfId.id());
            }

            // Write the custom JFC file to the container
            final String writeJfcCommand =
                    String.format("cat > /tmp/profiling/otter.jfc << 'EOF'\n%s\nEOF", jfcContent);
            final ExecResult writeResult = container.execInContainer("sh", "-c", writeJfcCommand);
            if (writeResult.getExitCode() != 0) {
                throw new IOException("Failed to write JFC configuration on node " + selfId.id());
            }

            // Start JFR with our custom configuration
            final String startJfrCommand =
                    String.format("jcmd %s JFR.start name=otter-profile settings=/tmp/profiling/otter.jfc", pid);

            final ExecResult result = container.execInContainer("sh", "-c", startJfrCommand);
            if (result.getExitCode() != 0) {
                fail("Failed to start JFR profiling on node " + selfId.id() + ": " + result.getStderr());
            }
            log.info("JFR.start output: {}", result.getStdout());

            // Check what settings are actually being used
            final String checkCommand = String.format("jcmd %s JFR.check", pid);
            final ExecResult checkResult = container.execInContainer("sh", "-c", checkCommand);
            log.info("JFR.check output: {}", checkResult.getStdout());

            log.info(
                    "Started JFR profiling on node {} with {}ms sampling ({}Hz) and events {} -> {}",
                    selfId.id(),
                    this.samplingInterval.toMillis(),
                    1000 / this.samplingInterval.toMillis(),
                    Stream.of(this.profilerEvents).map(Enum::name).collect(Collectors.joining(", ")),
                    outputFilename);
        } catch (final IOException e) {
            fail("Failed to start profiling on node " + selfId.id(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while starting profiling on node " + selfId.id(), e);
        }
    }

    /**
     * Generates a JFC (Java Flight Recorder Configuration) XML file content based on the specified
     * sampling rate and enabled events.
     *
     * @return the JFC XML configuration as a string
     */
    private String generateJfcConfiguration() {
        final StringBuilder jfc = new StringBuilder();
        jfc.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        jfc.append(
                "<configuration version=\"2.0\" label=\"Otter Custom Profile\" description=\"Custom JFR configuration for Otter tests\" provider=\"Otter\">\n");

        // Collect all JFR event names from the enabled ProfilerEvents
        final Stream<String> jfrEventNames = Stream.of(this.profilerEvents)
                .flatMap(event -> event.getJfrEventNames().stream())
                .distinct();

        // Generate event configuration for each JFR event
        jfrEventNames.forEach(eventName -> {
            jfc.append("  <event name=\"").append(eventName).append("\">\n");
            jfc.append("    <setting name=\"enabled\">true</setting>\n");

            // CPU sampling events: ExecutionSample, NativeMethodSample
            switch (eventName) {
                case "jdk.ExecutionSample", "jdk.NativeMethodSample" ->
                    jfc.append("    <setting name=\"period\">")
                            .append(this.samplingInterval.toMillis())
                            .append(" ms</setting>\n");

                // Allocation events: ObjectAllocationSample uses throttle instead of period
                case "jdk.ObjectAllocationSample" -> {
                    jfc.append("    <setting name=\"throttle\">150/s</setting>\n");
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
                }

                // Lock events: JavaMonitorEnter, JavaMonitorWait, ThreadPark
                case "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark" -> {
                    jfc.append("    <setting name=\"threshold\">10 ms</setting>\n");
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
                }

                // I/O events: FileRead, FileWrite, SocketRead, SocketWrite
                case "jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite" -> {
                    jfc.append("    <setting name=\"threshold\">10 ms</setting>\n");
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
                }

                // Exception events
                case "jdk.JavaExceptionThrow" -> jfc.append("    <setting name=\"stackTrace\">true</setting>\n");

                // Thread sleep event
                case "jdk.ThreadSleep" -> {
                    jfc.append("    <setting name=\"threshold\">10 ms</setting>\n");
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
                }

                // Compiler events - basic compilation
                case "jdk.Compilation" -> jfc.append("    <setting name=\"threshold\">100 ms</setting>\n");

                // Compiler detailed events - failures and deoptimization
                case "jdk.CompilerFailure", "jdk.Deoptimization" ->
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");

                // Detailed allocation events (high overhead, all allocations not sampled)
                case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" ->
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");

                // Metaspace events
                case "jdk.MetaspaceGCThreshold", "jdk.MetaspaceAllocationFailure", "jdk.MetaspaceOOM" ->
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");

                // TLS handshake - might want to limit based on duration
                case "jdk.TLSHandshake" -> jfc.append("    <setting name=\"threshold\">10 ms</setting>\n");

                // Biased locking - only record actual revocations (not every lock operation)
                case "jdk.BiasedLockRevocation", "jdk.BiasedLockClassRevocation" ->
                    jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
            }

            // For all other events (SafePoint, GC detailed, Thread lifecycle, Class loading, etc.),
            // just enable them with default settings

            jfc.append("  </event>\n");
        });

        // Always add essential JVM metadata events (required for valid JFR files)
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

    /**
     * Stops profiling and downloads the profiling results from the container to the host.
     */
    public void stopProfiling() {
        if (profilingOutputFilename == null) {
            throw new IllegalStateException("Profiling was not started. Call startProfiling() first.");
        }

        try {
            // Ensure the profiling directory exists with proper permissions
            final ExecResult mkdirResult =
                    container.execInContainer("sh", "-c", "mkdir -p /tmp/profiling && chmod 777 /tmp/profiling");
            if (mkdirResult.getExitCode() != 0) {
                throw new IOException("Failed to create profiling directory on node " + selfId.id());
            }

            // Dump the recording to file
            final String containerPath = "/tmp/profiling/" + profilingOutputFilename;

            final String dumpJfrCommand =
                    String.format("jcmd %s JFR.dump name=otter-profile filename=%s", pid, containerPath);
            final ExecResult dumpResult = container.execInContainer("sh", "-c", dumpJfrCommand);
            if (dumpResult.getExitCode() != 0 || dumpResult.getStdout().contains("Dump failed")) {
                throw new IOException(
                        "Failed to dump JFR profiling on node " + selfId.id() + ": " + dumpResult.getStdout());
            }

            // Stop JFR recording
            final String stopJfrCommand = String.format("jcmd %s JFR.stop name=otter-profile", pid);
            final ExecResult result = container.execInContainer("sh", "-c", stopJfrCommand);
            if (result.getExitCode() != 0) {
                throw new IOException(
                        "Failed to stop JFR profiling on node " + selfId.id() + ": " + result.getStderr());
            }

            log.info("Stopped JFR profiling on node {}", selfId.id());

            // Download the profiling result to build/container/node-{selfId}/{filename}
            final Path hostPath = localOutputDirectory.resolve(profilingOutputFilename);

            // Ensure parent directory exists
            Files.createDirectories(hostPath.getParent());

            // Copy the file from container to host
            container.copyFileFromContainer(containerPath, hostPath.toString());

            log.info("Downloaded JFR profiling result from node {} to {}", selfId.id(), hostPath.toAbsolutePath());

            // Clear the filename
            profilingOutputFilename = null;
        } catch (final IOException e) {
            fail("Failed to stop profiling and download results from node " + selfId.id(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while stopping profiling and downloading results from node " + selfId.id(), e);
        }
    }
}
