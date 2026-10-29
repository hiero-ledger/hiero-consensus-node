// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

/**
 * Interface representing a minimal node in the network that is stripped down to the essential functionality needed
 * for producing a realistic load.
 */
public interface MiniNode {
    /**
     * The default software version of the node when no specific version is set for the node.
     */
    SemanticVersion DEFAULT_VERSION = SemanticVersion.newBuilder().major(1).build();

    /**
     * Start the node.
     *
     * <p>The method will wait for a environment-specific timeout before throwing an exception if the node cannot be
     * started. The default can be overridden by calling {@link #withTimeout(Duration)}.
     */
    void start();

    /**
     * Kill the node without prior cleanup.
     *
     * <p>This method simulates a sudden failure of the node. No attempt to finish ongoing work,
     * preserve the current state, or any other similar operation is made.
     *
     * <p>The method will wait for a environment-specific timeout before throwing an exception if the nodes cannot be
     * killed. The default can be overridden by calling {@link #withTimeout(Duration)}.
     */
    void killImmediately();

    /**
     * Overrides the default timeout for node operations.
     *
     * @param timeout the duration to wait before considering the operation as failed
     * @return an instance of {@link AsyncMiniNodeActions} that can be used to perform node actions
     */
    @NonNull
    AsyncMiniNodeActions withTimeout(@NonNull Duration timeout);

    /**
     * Gets the configuration of the node. The returned object can be used to evaluate the current configuration, but
     * also for modifications.
     *
     * @return the configuration of the node
     */
    @NonNull
    NodeConfiguration configuration();

    /**
     * Gets the self id of the node. This value can be used to identify a node.
     *
     * @return the self id
     */
    @NonNull
    NodeId selfId();

    /**
     * Gets the weight of the node. This value is always non-negative.
     *
     * @return the weight
     */
    long weight();

    /**
     * Sets the weight of the node. This method can only be called while the node is not running.
     *
     * @param weight the new weight. Must be non-negative.
     */
    void weight(long weight);

    /**
     * Sets the keys and certificates of the node. These signing certificates will become part of the new roster. This
     * method can only be called while the node has not been started yet.
     *
     * @param keysAndCerts the new keys and certificates
     */
    void keysAndCerts(@NonNull KeysAndCerts keysAndCerts);

    /**
     * Checks if the node is currently running.
     *
     * @return true if the node is running, false otherwise
     */
    boolean isAlive();

    /**
     * Gets the latest event window of the node.
     *
     * @return the latest event window
     */
    @NonNull
    EventWindow latestEventWindow();

    /**
     * Starts Java Flight Recorder (JFR) profiling on this node with default settings.
     * Uses 10 ms sampling rate and enables CPU and allocation profiling by default.
     * The profiler runs in the background until {@link #stopProfiling} is called.
     * <p>
     * <b>Warning:</b> Please keep in mind that Otter tests run in an artificial environment. Results obtained from
     * profiling in such environments may not accurately reflect real-world performance characteristics.
     * <p>
     * <b>Note:</b> This feature is not supported in all environments.
     * Calling this on unsupported environments will throw {@link UnsupportedOperationException}.
     *
     * @param outputFile filename for the profile output (must end with ".jfr")
     * @param events the profiling events to enable (e.g., ProfilerEvent.CPU, ProfilerEvent.ALLOCATION).
     *               If empty, defaults to CPU and ALLOCATION profiling.
     * @throws UnsupportedOperationException if profiling is not supported in this environment
     */
    default void startProfiling(@NonNull final String outputFile, @NonNull final ProfilerEvent... events) {
        startProfiling(outputFile, Duration.ofMillis(10L), events);
    }

    /**
     * Starts Java Flight Recorder (JFR) profiling on this node with custom settings.
     * The profiler runs in the background until {@link #stopProfiling} is called.
     * <p>
     * <b>Warning:</b> Please keep in mind that Otter tests run in an artificial environment. Results obtained from
     * profiling in such environments may not accurately reflect real-world performance characteristics.
     * <p>
     * <b>Note:</b> This feature is not supported in all environments.
     * Calling this on unsupported environments will throw {@link UnsupportedOperationException}.
     *
     * @param outputFile filename for the profile output (must end with ".jfr")
     * @param samplingInterval sampling interval for timed events (e.g., Duration.ofMillis(1) for 1ms sampling)
     * @param events the profiling events to enable (e.g., ProfilerEvent.CPU, ProfilerEvent.ALLOCATION).
     *               If empty, defaults to CPU and ALLOCATION profiling.
     * @throws UnsupportedOperationException if profiling is not supported in this environment
     */
    void startProfiling(
            @NonNull String outputFile, @NonNull Duration samplingInterval, @NonNull ProfilerEvent... events);

    /**
     * Stops Java Flight Recorder profiling and automatically downloads the profiling results to the host machine.
     * The file is saved to {@code build/container/node-<selfId>/<filename>} where filename was
     * specified in {@link #startProfiling}.
     * <p>
     * <b>Note:</b> This feature is not supported in all environments.
     * Calling this on unsupported environments will throw {@link UnsupportedOperationException}.
     *
     * @throws UnsupportedOperationException if profiling is not supported in this environment
     */
    void stopProfiling();

    /**
     * Captures a JVM thread dump of the node for diagnostics, typically to investigate a node that is stuck.
     *
     * @return the thread dump as text
     * @throws UnsupportedOperationException if thread dumps are not supported in this environment
     */
    @NonNull
    String dumpThreads();
}
