// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.virtualmap.VirtualMapUtils;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public class TestVirtualMapMerkleNodeState extends VirtualMapState<TestVirtualMapMerkleNodeState>
        implements MerkleNodeState {

    public TestVirtualMapMerkleNodeState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        super(configuration, metrics);
    }

    public TestVirtualMapMerkleNodeState(@NonNull final VirtualMap virtualMap) {
        super(virtualMap);
    }

    protected TestVirtualMapMerkleNodeState(@NonNull final TestVirtualMapMerkleNodeState from) {
        super(from);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    protected TestVirtualMapMerkleNodeState copyingConstructor() {
        return new TestVirtualMapMerkleNodeState(this);
    }

    @Override
    protected TestVirtualMapMerkleNodeState newInstance(@NonNull final VirtualMap virtualMap) {
        return new TestVirtualMapMerkleNodeState(virtualMap);
    }

    public static TestVirtualMapMerkleNodeState createInstanceWithVirtualMapLabel(
            @NonNull final String virtualMapLabel) {
        final var virtualMap = VirtualMapUtils.createVirtualMap(virtualMapLabel);
        return new TestVirtualMapMerkleNodeState(virtualMap);
    }
}
