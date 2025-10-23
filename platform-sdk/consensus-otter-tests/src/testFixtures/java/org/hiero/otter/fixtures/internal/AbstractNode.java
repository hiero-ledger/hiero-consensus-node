// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.logging.legacy.payload.IssPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionFactory;
import org.hiero.otter.fixtures.app.OtterTransaction;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;

/**
 * Base implementation of the {@link Node} interface that provides common functionality.
 */
public abstract class AbstractNode implements Node {

    static final long UNSET_WEIGHT = -1;

    /**
     * Represents the lifecycle states of a node.
     */
    public enum LifeCycle {
        /** The node is initializing. */
        INIT,

        /** The node is running. */
        RUNNING,

        /** The node was shut down, but can be started again. */
        SHUTDOWN,

        /** The node was destroyed and cannot be started again. */
        DESTROYED
    }

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    protected final NodeId selfId;
    protected final KeysAndCerts keysAndCerts;

    private Roster roster;
    private long weight = UNSET_WEIGHT;

    /**
     * The current state of the node's life cycle. Volatile because it is set by the test thread and read by the
     * container callback thread.
     */
    protected volatile LifeCycle lifeCycle = LifeCycle.INIT;

    /** Current software version of the platform */
    protected SemanticVersion version = Node.DEFAULT_VERSION;

    /** Saved state directory */
    protected Path savedStateDirectory;

    /**
     * The current state of the platform. Volatile because it is set by the container callback thread and read by the
     * test thread.
     */
    @Nullable
    protected volatile PlatformStatus platformStatus = null;

    /**
     * Constructor for the AbstractNode class.
     *
     * @param selfId the unique identifier for this node
     * @param keysAndCerts the cryptographic keys and certificates for this node
     */
    protected AbstractNode(@NonNull final NodeId selfId, @NonNull final KeysAndCerts keysAndCerts) {
        this.selfId = requireNonNull(selfId);
        this.keysAndCerts = requireNonNull(keysAndCerts);
    }

    /**
     * Gets the time manager associated with this node.
     *
     * @return the time manager
     */
    @NonNull
    protected abstract TimeManager timeManager();

    /**
     * Gets a random number generator associated with this node.
     *
     * @return the random number generator
     */
    @NonNull
    protected abstract Random random();

    /**
     * Gets the roster associated with this node.
     *
     * @return the roster
     */
    protected Roster roster() {
        return roster;
    }

    /**
     * Sets the roster for this node. If the weight for this node in the roster does not match the weight set for this
     * node, an {@link IllegalArgumentException} is thrown.
     *
     * @param roster the roster to set
     */
    protected void roster(@NonNull final Roster roster) {
        this.roster = requireNonNull(roster);
        final long rosterWeight = roster.rosterEntries().stream()
                .filter(r -> r.nodeId() == selfId.id())
                .findFirst()
                .map(RosterEntry::weight)
                .orElseThrow(() -> new IllegalStateException("Node ID " + selfId.id() + " not found in roster"));
        if (weight != UNSET_WEIGHT && weight != rosterWeight) {
            throw new IllegalStateException("Node weight " + weight + " does not match roster weight " + rosterWeight);
        }
        weight = rosterWeight;
    }

    /**
     * Gets the gossip CA certificate for this node.
     *
     * @return the gossip CA certificate
     */
    protected X509Certificate gossipCaCertificate() {
        return keysAndCerts.sigCert();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformStatus platformStatus() {
        return platformStatus;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public NodeId selfId() {
        return selfId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long weight() {
        return weight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void weight(final long weight) {
        throwIfInLifecycle(LifeCycle.RUNNING, "Cannot set weight while the node is running");
        throwIfInLifecycle(LifeCycle.DESTROYED, "Cannot set weight after the node has been destroyed");
        if (weight < 0) {
            throw new IllegalArgumentException("Weight must be non-negative");
        }
        this.weight = weight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SemanticVersion version() {
        return version;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void version(@NonNull final SemanticVersion version) {
        throwIfInLifecycle(LifeCycle.RUNNING, "Cannot set version while the node is running");
        throwIfInLifecycle(LifeCycle.DESTROYED, "Cannot set version after the node has been destroyed");

        this.version = requireNonNull(version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startFromSavedState(@NonNull final Path savedStateDirectory) {
        throwIfInLifecycle(LifeCycle.RUNNING, "Cannot set saved state directory while the node is running");
        throwIfInLifecycle(LifeCycle.DESTROYED, "Cannot set saved state directory after the node has been destroyed");

        this.savedStateDirectory = OtterSavedStateUtils.findSaveState(requireNonNull(savedStateDirectory));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        throwIfInLifecycle(LifeCycle.RUNNING, "Cannot bump version while the node is running");
        throwIfInLifecycle(LifeCycle.DESTROYED, "Cannot bump version after the node has been destroyed");

        int newBuildNumber;
        try {
            newBuildNumber = Integer.parseInt(version.build()) + 1;
        } catch (final NumberFormatException e) {
            newBuildNumber = 1;
        }
        this.version = this.version.copyBuilder().build("" + newBuildNumber).build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        doStart(DEFAULT_TIMEOUT);
    }

    /**
     * The actual implementation of the start logic, to be provided by subclasses.
     *
     * @param timeout the maximum duration to wait for the node to start
     */
    protected abstract void doStart(@NonNull Duration timeout);

    /**
     * {@inheritDoc}
     */
    @Override
    public void killImmediately() {
        doKillImmediately(DEFAULT_TIMEOUT);
    }

    /**
     * The actual implementation of the kill logic, to be provided by subclasses.
     *
     * @param timeout the maximum duration to wait for the node to stop
     */
    protected abstract void doKillImmediately(@NonNull Duration timeout);

    /**
     * Submit a transaction to the node.
     *
     * @param transaction the transaction to submit
     */
    protected abstract void submitTransaction(@NonNull OtterTransaction transaction);

    /**
     * {@inheritDoc}
     */
    @Override
    public void startSyntheticBottleneck(@NonNull final Duration delayPerRound) {
        doStartSyntheticBottleneck(delayPerRound, DEFAULT_TIMEOUT);
    }

    /**
     * The actual implementation of the synthetic bottleneck logic, to be provided by subclasses.
     *
     * @param delayPerRound the artificial delay to introduce per consensus round
     * @param timeout the maximum duration to wait for the bottleneck to start
     */
    protected abstract void doStartSyntheticBottleneck(@NonNull Duration delayPerRound, @NonNull Duration timeout);

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopSyntheticBottleneck() {
        doStopSyntheticBottleneck(DEFAULT_TIMEOUT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void triggerSelfIss() {
        doTriggerSelfIss(DEFAULT_TIMEOUT);
    }

    private void doTriggerSelfIss(@NonNull final Duration timeout) {
        throwIsNotInLifecycle(LifeCycle.RUNNING, "Node must be running to trigger a self ISS.");

        log.info("Sending Self ISS triggering transaction...");
        final Instant start = timeManager().now();
        final OtterTransaction issTransaction =
                TransactionFactory.createSelfIssTransaction(random().nextLong(), selfId);

        submitTransaction(issTransaction);
        final Duration elapsed = Duration.between(start, timeManager().now());

        log.debug("Waiting for Self ISS to trigger...");

        final AtomicBoolean found =
                newLogResult().onNextMatch(logEntry -> logEntry.message().contains(IssPayload.class.getName()));

        timeManager()
                .waitForCondition(found::get, timeout.minus(elapsed), "Did not find the IssPayload log before timeout");

        log.info("Self ISS triggered");
    }

    /**
     * {@inheritDoc}
     */
    public AsyncNodeActions withTimeout(@NonNull final Duration timeout) {
        return new AsyncNodeActionsImpl(timeout);
    }

    /**
     * The actual implementation of the stop synthetic bottleneck logic, to be provided by subclasses.
     *
     * @param timeout the maximum duration to wait for the bottleneck to stop
     */
    protected abstract void doStopSyntheticBottleneck(@NonNull Duration timeout);

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
        throwIsNotInLifecycle(RUNNING, "Can send quiescence commands only while the node is running");
        doSendQuiescenceCommand(command, DEFAULT_TIMEOUT);
    }

    /**
     * The actual implementation of sending the quiescence command, to be provided by subclasses.
     *
     * @param command the quiescence command to send
     * @param timeout the maximum duration to wait for the command to be processed
     */
    protected abstract void doSendQuiescenceCommand(@NonNull QuiescenceCommand command, @NonNull Duration timeout);

    /**
     * Throws an {@link IllegalStateException} if the node is in the specified lifecycle state.
     *
     * @param expected throw if the node is in this lifecycle state
     * @param message the message for the exception
     */
    protected void throwIfInLifecycle(@NonNull final LifeCycle expected, @NonNull final String message) {
        if (lifeCycle == expected) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Throws an {@link IllegalStateException} if the node is not in the specified lifecycle state.
     *
     * @param expected throw if the lifecycle is not in this state
     * @param message the message for the exception
     */
    protected void throwIsNotInLifecycle(@NonNull final LifeCycle expected, @NonNull final String message) {
        if (lifeCycle != expected) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "Node{id=" + selfId.id() + '}';
    }

    private class AsyncNodeActionsImpl implements AsyncNodeActions {

        private final Duration timeout;

        private AsyncNodeActionsImpl(@NonNull final Duration timeout) {
            this.timeout = requireNonNull(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() {
            doStart(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void killImmediately() {
            doKillImmediately(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void startSyntheticBottleneck(@NonNull final Duration delayPerRound) {
            doStartSyntheticBottleneck(delayPerRound, timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void stopSyntheticBottleneck() {
            doStopSyntheticBottleneck(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void triggerSelfIss() {
            doTriggerSelfIss(timeout);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
            throwIsNotInLifecycle(RUNNING, "Can send quiescence commands only while the node is running");
            doSendQuiescenceCommand(command, timeout);
        }
    }
}
