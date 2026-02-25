// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeEventStreamResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

/**
 * Interface representing a node in the network.
 *
 * <p>This interface provides methods to control the state of the node, such as killing and reviving it.
 */
@SuppressWarnings("unused")
public interface Node {

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
     * Start a synthetic bottleneck on the node.
     *
     * <p>This method simulates a delay in processing rounds of consensus, which can be used to test the node's
     * behavior when the handle thread cannot keep up.
     *
     * <p>Equivalent to calling {@link #startSyntheticBottleneck(Duration)} with a delay of 100 milliseconds.
     *
     * @see #startSyntheticBottleneck(Duration)
     */
    default void startSyntheticBottleneck() {
        startSyntheticBottleneck(Duration.ofMillis(100));
    }

    /**
     * Start a synthetic bottleneck on the node.
     *
     * <p>This method simulates a delay in processing rounds of consensus, which can be used to test the node's
     * behavior when the handle thread cannot keep up.
     *
     * @param delayPerRound the duration to sleep on the handle thread after processing each round
     * @see #startSyntheticBottleneck()
     */
    void startSyntheticBottleneck(@NonNull Duration delayPerRound);

    /**
     * Stop the synthetic bottleneck on the node.
     *
     * <p>This method stops the delay in processing rounds of consensus that was started by
     * {@link #startSyntheticBottleneck(Duration)}.
     *
     * @see #startSyntheticBottleneck(Duration)
     * @see #startSyntheticBottleneck()
     */
    void stopSyntheticBottleneck();

    /**
     * Triggers a self-ISS on this node. The node will be able to recover from the ISS by restarting. This type of ISS
     * simulates a bug where a transaction updates the state based on data in memory that is different on other nodes
     * (due to the bug).
     */
    void triggerSelfIss();

    /**
     * Sets the quiescence command of the node.
     *
     * <p>The default command is {@link QuiescenceCommand#DONT_QUIESCE}.
     *
     * @param command the new quiescence command
     */
    void sendQuiescenceCommand(@NonNull QuiescenceCommand command);

    /**
     * Submits a transaction to the node.
     *
     * @param transaction the transaction to submit
     */
    default void submitTransaction(@NonNull OtterTransaction transaction) {
        submitTransactions(List.of(transaction));
    }

    /**
     * Submits transactions to the node.
     *
     * @param transactions the list of transactions to submit
     */
    void submitTransactions(@NonNull List<OtterTransaction> transactions);

    /**
     * Overrides the default timeout for node operations.
     *
     * @param timeout the duration to wait before considering the operation as failed
     * @return an instance of {@link AsyncNodeActions} that can be used to perform node actions
     */
    AsyncNodeActions withTimeout(@NonNull Duration timeout);

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
     * Returns the status of the platform while the node is running or {@code null} if not.
     *
     * @return the status of the platform
     */
    @Nullable
    PlatformStatus platformStatus();

    /**
     * Checks if the node's {@link PlatformStatus} is {@link PlatformStatus#ACTIVE}.
     *
     * @return {@code true} if the node is active, {@code false} otherwise
     */
    default boolean isActive() {
        return isInStatus(PlatformStatus.ACTIVE);
    }

    /**
     * Checks if the node's {@link PlatformStatus} is {@link PlatformStatus#CHECKING}.
     *
     * @return {@code true} if the node is checking, {@code false} otherwise
     */
    default boolean isChecking() {
        return isInStatus(PlatformStatus.CHECKING);
    }

    /**
     * Checks if the node's {@link PlatformStatus} is {@link PlatformStatus#BEHIND}.
     *
     * @return {@code true} if the node is behind, {@code false} otherwise
     */
    default boolean isBehind() {
        return isInStatus(PlatformStatus.BEHIND);
    }

    /**
     * Checks if the node's {@link PlatformStatus} is {@code status}.
     *
     * @param status the status to check against
     * @return {@code true} if the node is in the supplied status, {@code false} otherwise
     */
    default boolean isInStatus(@NonNull final PlatformStatus status) {
        return platformStatus() == status;
    }

    /**
     * Gets the software version of the node.
     *
     * @return the software version of the node
     */
    @NonNull
    SemanticVersion version();

    /**
     * Sets the software version of the node.
     *
     * <p>If no version is set, {@link #DEFAULT_VERSION} will be used. This method can only be called while the node is
     * not running.
     *
     * @param version the software version to set for the node
     */
    void version(@NonNull SemanticVersion version);

    /**
     * Enables GC logging for the consensus node process. Must be called before the node is started.
     *
     * <p><b>Note:</b> This feature is not supported in all environments. Calling this on unsupported environments
     * will throw {@link UnsupportedOperationException}.
     *
     * @throws UnsupportedOperationException if GC logging is not supported in this environment
     */
    default void withGcLogging() {
        throw new UnsupportedOperationException("GC logging is not supported in this environment");
    }

    /**
     * This method updates the version to trigger a "config only upgrade" on the next restart. This method can only be
     * called while the node is not running.
     */
    void bumpConfigVersion();

    /**
     * Creates a new result with all the consensus rounds of the node.
     *
     * @return the consensus rounds of the node
     */
    @NonNull
    SingleNodeConsensusResult newConsensusResult();

    /**
     * Creates a new result with all the log results of this node.
     *
     * @return the log results of this node
     */
    @NonNull
    SingleNodeLogResult newLogResult();

    /**
     * Creates a new result with all the status progression results of the node.
     *
     * @return the status progression result of the node
     */
    @NonNull
    SingleNodePlatformStatusResult newPlatformStatusResult();

    /**
     * Creates a new result with all the results related to PCES files.
     *
     * @return the PCES files created by the node
     */
    @NonNull
    SingleNodePcesResult newPcesResult();

    /**
     * Creates a new result with all the reconnects this node performed.
     *
     * @return the reconnect results of the node
     */
    @NonNull
    SingleNodeReconnectResult newReconnectResult();

    /**
     * Creates a new result with all the event streams created by this node.
     *
     * @return the event stream results of this node
     */
    @NonNull
    SingleNodeEventStreamResult newEventStreamResult();

    /**
     * Sets the source directory of the saved state directory. The directory is either relative to
     * {@code platform-sdk/consensus-otter-tests/saved-states} or an absolute path
     *
     * <p>If no directory is set, genesis state will be generated. This method can only be called while the node is
     * not running.
     *
     * @param savedStateDirectory the software version to set for the node
     */
    void startFromSavedState(@NonNull final Path savedStateDirectory);

    /**
     * Checks if the consensus node is currently running.
     *
     * @return true if the node is running, false otherwise
     */
    boolean isAlive();

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
}
