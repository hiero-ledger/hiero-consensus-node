// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.INIT;
import static org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle.RUNNING;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.X509Certificate;
import java.time.Duration;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.AsyncMiniNodeActions;
import org.hiero.otter.fixtures.MiniNode;
import org.hiero.otter.fixtures.ProfilerEvent;

/**
 * Base implementation of the {@link MiniNode} interface that provides common functionality.
 */
public abstract class AbstractMiniNode implements MiniNode {

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

    /** The weight value indicating that the weight has not been set. */
    protected static final long UNSET_WEIGHT = -1;

    /** The default timeout duration for node operations. */
    protected static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    /** The unique identifier for this node. */
    protected final NodeId selfId;

    /** The roster of nodes in the network. */
    private Roster roster;

    /** The cryptographic keys and certificates for this node. */
    protected KeysAndCerts keysAndCerts;

    /** The weight of this node. */
    private long weight = UNSET_WEIGHT;

    /**
     * The current state of the node's life cycle. Volatile because it is set by the test thread and read by the
     * container callback thread.
     */
    protected volatile LifeCycle lifeCycle = INIT;

    /**
     * Constructor for the {@code AbstractMiniNode} class.
     *
     * @param selfId the unique identifier for this node
     * @param keysAndCerts the cryptographic keys and certificates for this node
     */
    protected AbstractMiniNode(
            @NonNull final NodeId selfId,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final NetworkConfiguration networkConfiguration) {
        this.selfId = requireNonNull(selfId);
        this.keysAndCerts = requireNonNull(keysAndCerts);
        if (networkConfiguration.weight() != UNSET_WEIGHT) {
            weight(networkConfiguration.weight());
        }
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
                .orElseThrow(() -> new IllegalArgumentException("Node ID " + selfId.id() + " not found in roster"));
        if (weight != UNSET_WEIGHT && weight != rosterWeight) {
            throw new IllegalArgumentException(
                    "Node weight " + weight + " does not match roster weight " + rosterWeight);
        }
        weight = rosterWeight;
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
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public AsyncMiniNodeActions withTimeout(@NonNull final Duration timeout) {
        return new AsyncMiniNodeActionsImpl(timeout);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
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
        throwIfInLifecycle(RUNNING, "Cannot set weight while the node is running");
        throwIfInLifecycle(DESTROYED, "Cannot set weight after the node has been destroyed");
        if (weight < 0) {
            throw new IllegalArgumentException("Weight must be non-negative");
        }
        this.weight = weight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void keysAndCerts(@NonNull final KeysAndCerts keysAndCerts) {
        throwIsNotInLifecycle(INIT, "KeysAndCerts can only be set during initialization");
        this.keysAndCerts = requireNonNull(keysAndCerts);
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
    public boolean isAlive() {
        return lifeCycle == RUNNING;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startProfiling(
            @NonNull final String outputFile,
            @NonNull final Duration samplingInterval,
            @NonNull final ProfilerEvent... events) {
        throw new UnsupportedOperationException("Profiling is not supported in this environment");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopProfiling() {
        throw new UnsupportedOperationException("Profiling is not supported in this environment");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String dumpThreads() {
        throw new UnsupportedOperationException("Thread dumping is not supported in this environment");
    }

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
     * Implementation of the {@link AsyncMiniNodeActions} interface that provides asynchronous actions for the node.
     */
    protected class AsyncMiniNodeActionsImpl implements AsyncMiniNodeActions {

        /** The timeout duration for the asynchronous actions. */
        protected final Duration timeout;

        /**
         * Constructor for the {@code AsyncMiniNodeActionsImpl} class.
         *
         * @param timeout the timeout duration for the asynchronous actions
         */
        protected AsyncMiniNodeActionsImpl(@NonNull final Duration timeout) {
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
    }
}
