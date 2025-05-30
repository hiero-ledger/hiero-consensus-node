// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.merkle.NewStateRoot;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.constructable.ConstructableIgnored;

/**
 * This class sole purpose is to extend the {@link NewStateRoot} class and implement the {@link MerkleNodeState}.
 * Technically, {@link NewStateRoot} is already implementing {@link State} and {@link MerkleNode} but it does not
 * implement the {@link MerkleNodeState} interface. This class is merely a connector of these two interfaces.
 */
@ConstructableIgnored
public class HederaNewStateRoot extends NewStateRoot<HederaNewStateRoot> implements MerkleNodeState {

    public HederaNewStateRoot(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        super(configuration, metrics);
    }

    /**
     * Constructs a {@link HederaNewStateRoot} using the specified {@link VirtualMap}.
     *
     * @param virtualMap the virtual map whose metrics must already be registered
     */
    public HederaNewStateRoot(@NonNull final VirtualMap virtualMap) {
        super(virtualMap);
    }

    protected HederaNewStateRoot(@NonNull final HederaNewStateRoot from) {
        super(from);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    protected HederaNewStateRoot copyingConstructor() {
        return new HederaNewStateRoot(this);
    }

    /**
     * Creates a new instance of {@link HederaNewStateRoot} with the specified {@link VirtualMap}.
     *
     * @param virtualMap the virtual map whose metrics must already be registered
     * @return a new instance of {@link HederaNewStateRoot}
     */
    @Override
    protected HederaNewStateRoot newInstance(@NonNull final VirtualMap virtualMap) {
        return new HederaNewStateRoot(virtualMap);
    }
}
