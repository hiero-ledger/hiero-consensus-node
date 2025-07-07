// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.Node;

/**
 * Base implementation of the {@link Node} interface that provides common functionality.
 */
public abstract class AbstractNode implements Node {

    /**
     * Represents the lifecycle states of a node.
     */
    public enum LifeCycle {
        INIT,
        RUNNING,
        SHUTDOWN,
        DESTROYED
    }

    protected final NodeId selfId;
    protected final long weight;

    protected LifeCycle lifeCycle = LifeCycle.INIT;
    protected SemanticVersion version = Node.DEFAULT_VERSION;

    @Nullable
    protected PlatformStatus platformStatus = null;

    /**
     * Constructor for the AbstractNode class.
     *
     * @param selfId the unique identifier for this node
     */
    protected AbstractNode(@NonNull final NodeId selfId, final long weight) {
        this.selfId = selfId;
        this.weight = weight;
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
    public void setVersion(@NonNull final SemanticVersion version) {
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
     * Throws an {@link IllegalStateException} if the node is in the specified lifecycle state.
     *
     * @param expected the expected lifecycle state
     * @param message  the message for the exception
     */
    protected void throwIfIn(@NonNull final LifeCycle expected, @NonNull final String message) {
        if (lifeCycle == expected) {
            throw new IllegalStateException(message);
        }
    }
}
