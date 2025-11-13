// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapUtils.CONFIGURATION;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A test implementation of {@link State} backed by a single Virtual Map.
 */
public class TestVirtualMapState extends VirtualMapState<TestVirtualMapState> implements MerkleNodeState {

    public TestVirtualMapState() {
        super(CONFIGURATION, new NoOpMetrics(), new FakeTime());
    }

    public TestVirtualMapState(@NonNull final VirtualMap virtualMap) {
        super(virtualMap, new NoOpMetrics(), new FakeTime());
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
    protected TestVirtualMapState newInstance(
            @NonNull final VirtualMap virtualMap, @NonNull final Metrics metrics, @NonNull final Time time) {
        return new TestVirtualMapState(virtualMap);
    }

    public static TestVirtualMapState createInstanceWithVirtualMapLabel(@NonNull final String virtualMapLabel) {
        final var virtualMap = VirtualMapUtils.createVirtualMap(CONFIGURATION, virtualMapLabel);
        return new TestVirtualMapState(virtualMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected long getRound() {
        return 0; // genesis round
    }
}
