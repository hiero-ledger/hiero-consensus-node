// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapUtils.CONFIGURATION;

import com.swirlds.config.api.Configuration;
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

    public TestVirtualMapState(@NonNull final Metrics metrics) {
        super(CONFIGURATION, metrics);
    }

    public TestVirtualMapState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        super(configuration, metrics);
    }

    public TestVirtualMapState() {
        this(VirtualMapUtils.createVirtualMap(VM_LABEL));
    }

    public TestVirtualMapState(@NonNull final Configuration configuration) {
        this(VirtualMapUtils.createVirtualMap(configuration, VM_LABEL));
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
        final var virtualMap = VirtualMapUtils.createVirtualMap(CONFIGURATION, virtualMapLabel);
        return new TestVirtualMapState(virtualMap);
    }

    public static TestVirtualMapState createInstanceWithVirtualMapLabel(
            @NonNull final Configuration configuration, @NonNull final String virtualMapLabel) {
        final var virtualMap = VirtualMapUtils.createVirtualMap(configuration, virtualMapLabel);
        return new TestVirtualMapState(virtualMap);
    }
}
