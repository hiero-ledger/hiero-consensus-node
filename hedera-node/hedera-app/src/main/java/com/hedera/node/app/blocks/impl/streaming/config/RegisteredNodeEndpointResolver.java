// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Looks up a {@link RegisteredNode} from the latest visible state on behalf of
 * {@link com.hedera.node.app.blocks.impl.streaming.BlockNodeConfigService}, which uses it to resolve block-node
 * endpoints referenced by {@code registeredNodeId} in {@code block-nodes.json}.
 */
@FunctionalInterface
public interface RegisteredNodeEndpointResolver {

    /**
     * Resolver that always returns {@code null}; used when no state-backed resolver is available
     * (e.g. during early startup or in unit tests that do not exercise registered-node resolution).
     */
    RegisteredNodeEndpointResolver NO_OP = registeredNodeId -> null;

    /**
     * @param registeredNodeId the id to look up
     * @return the registered node currently in state, or {@code null} if it does not exist or state
     *         is not yet available
     */
    @Nullable
    RegisteredNode resolve(long registeredNodeId);

    /**
     * @return {@code true} if this resolver is the {@link #NO_OP} placeholder
     */
    default boolean isNoOp() {
        return this == NO_OP;
    }

    /**
     * Marker used to indicate the resolver could not consult state at all (vs. consulted state and found nothing).
     * The default implementation distinguishes this via {@link #isNoOp()}; custom resolvers should override
     * {@link #isStateAvailable()} when they may transiently lack state.
     */
    default boolean isStateAvailable() {
        return !isNoOp();
    }

    @NonNull
    static RegisteredNodeEndpointResolver noOp() {
        return NO_OP;
    }
}
