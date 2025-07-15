// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.constructable.ConstructableIgnored;

/**
 * This class sole purpose is to extend the {@link VirtualMapState} class and implement the {@link MerkleNodeState}.
 * Technically, {@link VirtualMapState} is already implementing {@link State} and {@link MerkleNode} but it does not
 * implement the {@link MerkleNodeState} interface. This class is merely a connector of these two interfaces.
 */
@ConstructableIgnored
public class HederaVirtualMapState extends VirtualMapState<HederaVirtualMapState> implements MerkleNodeState {

    public HederaVirtualMapState(@NonNull final PlatformContext platformContext) {
        super(platformContext);
    }

    /**
     * Constructs a {@link HederaVirtualMapState} using the specified {@link VirtualMap}.
     *
     * @param virtualMap      the virtual map whose metrics must already be registered
     * @param platformContext
     */
    public HederaVirtualMapState(@NonNull final VirtualMap virtualMap, PlatformContext platformContext) {
        super(virtualMap, platformContext);
    }

    protected HederaVirtualMapState(@NonNull final HederaVirtualMapState from) {
        super(from);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    protected HederaVirtualMapState copyingConstructor() {
        return new HederaVirtualMapState(this);
    }

    /**
     * Creates a new instance of {@link HederaVirtualMapState} with the specified {@link VirtualMap}.
     *
     * @param virtualMap      the virtual map whose metrics must already be registered
     * @param platformContext
     * @return a new instance of {@link HederaVirtualMapState}
     */
    @Override
    protected HederaVirtualMapState newInstance(@NonNull final VirtualMap virtualMap, PlatformContext platformContext) {
        return new HederaVirtualMapState(virtualMap, platformContext);
    }
}
