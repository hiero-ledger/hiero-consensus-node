// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.virtualmap.VirtualMapUtils;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public class TestVirtualMapState extends VirtualMapState<TestVirtualMapState> implements MerkleNodeState {

    public TestVirtualMapState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        super(configuration, metrics);
    }

    public TestVirtualMapState(@NonNull final VirtualMap virtualMap) {
        super(virtualMap);
    }

    protected TestVirtualMapState(@NonNull final TestVirtualMapState from) {
        super(from);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    protected TestVirtualMapState copyingConstructor() {
        return new TestVirtualMapState(this);
    }

    @Override
    protected TestVirtualMapState newInstance(@NonNull final VirtualMap virtualMap) {
        return new TestVirtualMapState(virtualMap);
    }

    public static TestVirtualMapState createInstanceWithVirtualMapLabel(@NonNull final String virtualMapLabel) {
        final var virtualMap = VirtualMapUtils.createVirtualMap(virtualMapLabel);
        return new TestVirtualMapState(virtualMap);
    }
}
