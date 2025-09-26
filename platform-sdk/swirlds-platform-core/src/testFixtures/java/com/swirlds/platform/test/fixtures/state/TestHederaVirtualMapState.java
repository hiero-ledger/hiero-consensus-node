// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.virtualmap.VirtualMapUtils;
import com.swirlds.state.State;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A test implementation of {@link State} backed by a single Virtual Map.
 */
public class TestHederaVirtualMapState extends VirtualMapState<TestHederaVirtualMapState> implements MerkleNodeState {

    public TestHederaVirtualMapState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        super(configuration, metrics);
    }

    public TestHederaVirtualMapState(@NonNull final VirtualMap virtualMap) {
        super(virtualMap);
    }

    protected TestHederaVirtualMapState(@NonNull final TestHederaVirtualMapState from) {
        super(from);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    protected TestHederaVirtualMapState copyingConstructor() {
        return new TestHederaVirtualMapState(this);
    }

    @Override
    protected TestHederaVirtualMapState newInstance(@NonNull final VirtualMap virtualMap) {
        return new TestHederaVirtualMapState(virtualMap);
    }

    public static TestHederaVirtualMapState createInstanceWithVirtualMapLabel(@NonNull final String virtualMapLabel) {
        final var virtualMap = VirtualMapUtils.createVirtualMap(virtualMapLabel);
        return new TestHederaVirtualMapState(virtualMap);
    }
}
