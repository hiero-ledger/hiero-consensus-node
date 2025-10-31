package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.ProfilerEvent;
import org.testcontainers.containers.Container.ExecResult;

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

    /** The PID of the Java process being profiled */
    private String pid;

    /**
     *  Constructs a ContainerProfiler for the specified node container.
     *
     * @param selfId the NodeId of the node being profiled
     * @param container the container running the consensus node
     * @param localOutputDirectory the local base directory for storing profiling results
     */
    public ContainerProfiler(@NonNull final NodeId selfId, @NonNull final ContainerImage container, @NonNull final Path localOutputDirectory) {
        this.selfId = requireNonNull(selfId);
        this.container = requireNonNull(container);
        this.localOutputDirectory = requireNonNull(localOutputDirectory);
    }

    /**
     * Starts profiling with the specified output filename, sampling interval, and events.
     *
     * @param outputFilename the output filename for profiling results
     * @param samplingInterval the sampling interval for timed events, or null for default
     * @param events the profiling events to enable
     */
    public void startProfiling(
            @NonNull final String outputFilename,
            @Nullable final Duration samplingInterval,
            @NonNull final ProfilerEvent... events) {
        if (profilingOutputFilename != null) {
            throw new IllegalStateException("Profiling was already started.");
        }
        this.profilingOutputFilename = requireNonNull(outputFilename);
        requireNonNull(events);

        // Default to CPU and ALLOCATION if no events specified
        final ProfilerEvent[] eventsToEnable =
                events.length == 0 ? DEFAULT_PROFILER_EVENTS : events;

        try {
            // Get the Java process PID
            final String getPidCommand = "jps | grep \"ConsensusNodeMain\" | head -1 | awk '{print $1}'";
            final ExecResult pidResult = container.execInContainer("sh", "-c", getPidCommand);
            if (pidResult.getExitCode() != 0 || pidResult.getStdout().trim().isEmpty()) {
                fail("Failed to find ConsensusNodeMain process on node " + selfId.id());
            }
            pid = pidResult.getStdout().trim();
            log.info("Found ConsensusNodeMain process with PID {} on node {}", pid, selfId.id());

            // Create a minimal custom JFC configuration for CPU profiling
            // The built-in presets in containers may not enable ExecutionSample correctly
            final long periodMs = (samplingInterval != null) ? samplingInterval.toMillis() : 10;  // Default: 10ms = 100Hz
            final String jfcContent = generateMinimalJfcConfiguration(periodMs);

            // Ensure the profiling directory exists
            final ExecResult mkdirResult = container.execInContainer("sh", "-c", "mkdir -p /tmp/profiling && chmod 777 /tmp/profiling");
            if (mkdirResult.getExitCode() != 0) {
                throw new IOException("Failed to create profiling directory on node " + selfId.id());
            }

            // Write the custom JFC file to the container
            final String writeJfcCommand = String.format(
                    "cat > /tmp/profiling/otter.jfc << 'EOF'\n%s\nEOF", jfcContent);
            final ExecResult writeResult = container.execInContainer("sh", "-c", writeJfcCommand);
            if (writeResult.getExitCode() != 0) {
                throw new IOException("Failed to write JFC configuration on node " + selfId.id());
            }

            // Start JFR with our custom configuration
            final String startJfrCommand = String.format(
                    "jcmd %s JFR.start name=otter-profile settings=/tmp/profiling/otter.jfc", pid);

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
                    "Started JFR profiling on node {} with {} CPU sampling ({}Hz) and events {} -> {}",
                    selfId.id(),
                    samplingInterval == null ? "10ms" : samplingInterval.toMillis() + "ms",
                    samplingInterval == null ? 100 : 1000 / samplingInterval.toMillis(),
                    Stream.of(eventsToEnable).map(Enum::name).collect(Collectors.joining(", ")),
                    outputFilename);
        } catch (final IOException e) {
            fail("Failed to start profiling on node " + selfId.id(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while starting profiling on node " + selfId.id(), e);
        }
    }

    /**
     * Generates a minimal but complete JFC configuration with specified CPU sampling period.
     * This ensures ExecutionSample is enabled and configured correctly.
     *
     * @param periodMs the sampling period in milliseconds (e.g., 10ms = 100Hz)
     * @return the JFC XML configuration as a string
     */
    private String generateMinimalJfcConfiguration(final long periodMs) {
        return String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration version="2.0" label="Otter Profile" description="Optimized for CPU and allocation profiling" provider="Otter">
              <!-- CPU Sampling: Java methods -->
              <event name="jdk.ExecutionSample">
                <setting name="enabled">true</setting>
                <setting name="period">%d ms</setting>
              </event>

              <!-- CPU Sampling: Native methods -->
              <event name="jdk.NativeMethodSample">
                <setting name="enabled">true</setting>
                <setting name="period">%d ms</setting>
              </event>

              <!-- Allocation profiling -->
              <event name="jdk.ObjectAllocationSample">
                <setting name="enabled">true</setting>
                <setting name="throttle">150/s</setting>
              </event>

              <!-- Essential JVM events for valid recordings -->
              <event name="jdk.ActiveRecording"><setting name="enabled">true</setting></event>
              <event name="jdk.ActiveSetting"><setting name="enabled">true</setting></event>
              <event name="jdk.JVMInformation"><setting name="enabled">true</setting></event>
              <event name="jdk.OSInformation"><setting name="enabled">true</setting></event>
              <event name="jdk.CPUInformation"><setting name="enabled">true</setting></event>
              <event name="jdk.CPULoad"><setting name="enabled">true</setting><setting name="period">1 s</setting></event>
              <event name="jdk.ThreadCPULoad"><setting name="enabled">true</setting><setting name="period">1 s</setting></event>
              <event name="jdk.GarbageCollection"><setting name="enabled">true</setting></event>
              <event name="jdk.GCHeapSummary"><setting name="enabled">true</setting></event>
              <event name="jdk.ThreadStart"><setting name="enabled">true</setting></event>
              <event name="jdk.ThreadEnd"><setting name="enabled">true</setting></event>
            </configuration>
            """, periodMs, periodMs);
    }

    /**
     * Generates a JFC (Java Flight Recorder Configuration) XML file content based on the specified
     * sampling rate and enabled events.
     *
     * @param samplingRate the sampling interval for timed events
     * @param events the profiling events to enable
     * @return the JFC XML configuration as a string
     */
    private String generateJfcConfiguration(@Nullable final Duration samplingRate, @NonNull final ProfilerEvent[] events) {
        final StringBuilder jfc = new StringBuilder();
        jfc.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        jfc.append(
                "<configuration version=\"2.0\" label=\"Otter Custom Profile\" description=\"Custom JFR configuration for Otter tests\" provider=\"Otter\">\n");

        // Collect all JFR event names from the enabled ProfilerEvents
        final List<String> jfrEventNames = Stream.of(events)
                .flatMap(event -> event.getJfrEventNames().stream())
                .toList();

        // Generate event configuration for each JFR event
        for (final String eventName : jfrEventNames) {
            jfc.append("  <event name=\"").append(eventName).append("\">\n");
            jfc.append("    <setting name=\"enabled\">true</setting>\n");

            // Add period setting for sampling-based events (CPU profiling)
            if (eventName.contains("Sample") && samplingRate != null) {
                jfc.append("    <setting name=\"period\">")
                        .append(samplingRate.toMillis())
                        .append(" ms</setting>\n");
            }

            // Add stackTrace setting for allocation events
            if (eventName.contains("Allocation")) {
                jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
            }

            // Add threshold settings for I/O and lock events (0 = record all)
            if (eventName.contains("Read")
                    || eventName.contains("Write")
                    || eventName.contains("Monitor")
                    || eventName.contains("Park")) {
                jfc.append("    <setting name=\"threshold\">0 ms</setting>\n");
                jfc.append("    <setting name=\"stackTrace\">true</setting>\n");
            }

            jfc.append("  </event>\n");
        }

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
            final ExecResult mkdirResult = container.execInContainer("sh", "-c", "mkdir -p /tmp/profiling && chmod 777 /tmp/profiling");
            if (mkdirResult.getExitCode() != 0) {
                throw new IOException("Failed to create profiling directory on node " + selfId.id());
            }

            // Dump the recording to file
            final String containerPath = "/tmp/profiling/" + profilingOutputFilename;

            final String dumpJfrCommand = String.format("jcmd %s JFR.dump name=otter-profile filename=%s", pid, containerPath);
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
