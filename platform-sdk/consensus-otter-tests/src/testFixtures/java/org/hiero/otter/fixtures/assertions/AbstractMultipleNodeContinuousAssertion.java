// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.assertj.core.api.AbstractAssert;
import org.hiero.otter.fixtures.Node;

/**
 * Abstract implementation of {@link ContinuousAssertion} for results from multiple nodes that provides common functionality.
 *
 * @param <SELF>   the type of the concrete assertion class
 * @param <ACTUAL> the type of the actual object being asserted
 */
@SuppressWarnings({"unused", "java:S119"}) // java:S119 enforces one letter type names
public abstract class AbstractMultipleNodeContinuousAssertion<SELF extends AbstractAssert<SELF, ACTUAL>, ACTUAL>
        extends AbstractContinuousAssertion<SELF, ACTUAL> {

    protected final Set<NodeId> suppressedNodeIds = ConcurrentHashMap.newKeySet();

    /**
     * Constructor of {@link AbstractMultipleNodeContinuousAssertion}
     *
     * @param actual the actual object to assert
     * @param selfType the class type of the concrete assertion class
     */
    public AbstractMultipleNodeContinuousAssertion(
            @Nullable final ACTUAL actual, @NonNull final Class<? extends SELF> selfType) {
        super(actual, selfType);
    }

    /**
     * Suppresses the given node from the assertions.
     *
     * @param nodeId the id of the node to suppress
     * @return this assertion object for method chaining
     */
    @NonNull
    public SELF startSuppressingNode(@NonNull final NodeId nodeId) {
        suppressedNodeIds.add(nodeId);
        //noinspection unchecked
        return (SELF) this;
    }

    /**
     * Suppresses the given node from the assertions.
     *
     * @param node the {@link Node} to suppress
     * @return this assertion object for method chaining
     */
    @NonNull
    public SELF startSuppressingNode(@NonNull final Node node) {
        return startSuppressingNode(node.selfId());
    }

    /**
     * Stops suppressing the given node from the assertions.
     *
     * @param nodeId the id of the node
     * @return this assertion object for method chaining
     */
    @NonNull
    public SELF stopSuppressingNode(@NonNull final NodeId nodeId) {
        suppressedNodeIds.remove(nodeId);
        //noinspection unchecked
        return (SELF) this;
    }

    /**
     * Stops suppressing the given node from the assertions.
     *
     * @param node the {@link Node}
     * @return this assertion object for method chaining
     */
    @NonNull
    public SELF stopSuppressingNode(@NonNull final Node node) {
        return stopSuppressingNode(node.selfId());
    }
}
