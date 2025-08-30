// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.test.fixtures.virtualmap.VirtualMapUtils;
import com.swirlds.state.State;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A test implementation of {@link State} backed by a single Virtual Map.
 */
public class TestHederaVirtualMapState extends VirtualMapState<TestHederaVirtualMapState> implements MerkleNodeState {

    public TestHederaVirtualMapState(
            @NonNull final Configuration configuration, @NonNull final Metrics metrics, @NonNull final Time time) {
        super(configuration, metrics, time);
    }

    public TestHederaVirtualMapState(
            @NonNull final VirtualMap virtualMap,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time) {
        super(virtualMap, configuration, metrics, time);
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
    protected TestHederaVirtualMapState newInstance(
            @NonNull final VirtualMap virtualMap,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time) {
        return new TestHederaVirtualMapState(virtualMap, configuration, metrics, time);
    }

    public static TestHederaVirtualMapState createInstanceWithVirtualMapLabel(
            @NonNull final String virtualMapLabel,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time) {
        final var virtualMap = VirtualMapUtils.createVirtualMap(virtualMapLabel);
        return new TestHederaVirtualMapState(virtualMap, configuration, metrics, time);
    }

    @Override
    protected long getRound() {
        final ConsensusSnapshot consensusSnapshot = TEST_PLATFORM_STATE_FACADE.consensusSnapshotOf(this);
        return consensusSnapshot == null ? PlatformStateAccessor.GENESIS_ROUND : consensusSnapshot.round();
    }
}
