// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
 */
@SuppressWarnings("unused")
public interface Node extends MiniNode {

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
     * {@inheritDoc}
     */
    @Override
    @NonNull
    AsyncNodeActions withTimeout(@NonNull Duration timeout);

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
     * <p>If no version is set, {@link MiniNode#DEFAULT_VERSION} will be used. This method can only be called while the node is
     * not running.
     *
     * @param version the software version to set for the node
     */
    void version(@NonNull SemanticVersion version);

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
}
