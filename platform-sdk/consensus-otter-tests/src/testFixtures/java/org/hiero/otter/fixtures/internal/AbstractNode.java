// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.cert.X509Certificate;
import java.time.Duration;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.app.OtterTransaction;

/**
 * Base implementation of the {@link Node} interface that provides common functionality.
 */
public abstract class AbstractNode implements Node {

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

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    protected final NodeId selfId;
    protected final KeysAndCerts keysAndCerts;

    private Roster roster;
    private long weight;

    /**
     * The current state of the node's life cycle. Volatile because it is set by the test thread and read by the
     * container callback thread.
     */
    protected volatile LifeCycle lifeCycle = LifeCycle.INIT;

    /** Current software version of the platform */
    protected SemanticVersion version = Node.DEFAULT_VERSION;

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
     * Gets the roster associated with this node.
     *
     * @return the roster
     */
    protected Roster roster() {
        return roster;
    }

    /**
     * Sets the roster for this node.
     *
     * @param roster the roster to set
     */
    protected void roster(@NonNull final Roster roster) {
        this.roster = requireNonNull(roster);
        this.weight = roster.rosterEntries().stream()
                .filter(r -> r.nodeId() == selfId.id())
                .findFirst()
                .map(RosterEntry::weight)
                .orElse(0L);
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
    @NonNull
    public SemanticVersion version() {
        return version;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void version(@NonNull final SemanticVersion version) {
        throwIfIn(LifeCycle.RUNNING, "Cannot set version while the node is running");
        throwIfIn(LifeCycle.DESTROYED, "Cannot set version after the node has been destroyed");

        this.version = requireNonNull(version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bumpConfigVersion() {
        throwIfIn(LifeCycle.RUNNING, "Cannot bump version while the node is running");
        throwIfIn(LifeCycle.DESTROYED, "Cannot bump version after the node has been destroyed");

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
        throwIfNotIn(RUNNING, "Can send quiescence commands only while the node is running");
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
     * {@inheritDoc}
     */
    @Override
    public AsyncNodeActions withTimeout(@NonNull final Duration timeout) {
        return new AsyncNodeActionsImpl(timeout);
    }

    /**
     * Throws an {@link IllegalStateException} if the node is in the specified lifecycle state.
     *
     * @param expected throw if the node is in this lifecycle state
     * @param message  the message for the exception
     */
    protected void throwIfIn(@NonNull final LifeCycle expected, @NonNull final String message) {
        if (lifeCycle == expected) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Throws an {@link IllegalStateException} if the node is not in the specified lifecycle state.
     *
     * @param expected throw if the lifecycle is not in this state
     * @param message  the message for the exception
     */
    protected void throwIfNotIn(@NonNull final LifeCycle expected, @NonNull final String message) {
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
        public void sendQuiescenceCommand(@NonNull final QuiescenceCommand command) {
            throwIfNotIn(RUNNING, "Can send quiescence commands only while the node is running");
            doSendQuiescenceCommand(command, timeout);
        }
    }
}
