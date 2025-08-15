// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.virtualmap.VirtualMapUtils;
import com.swirlds.state.State;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;

/**
 * A test implementation of {@link State} backed by a single Virtual Map.
 */
public class TestVirtualMapState extends VirtualMapState<TestVirtualMapState> implements MerkleNodeState {

    public TestVirtualMapState(
            @NonNull final PlatformContext platformContext,
            @NonNull final Function<VirtualMapState<TestVirtualMapState>, Long> extractRoundFromState) {
        super(platformContext, extractRoundFromState);
    }

    public TestVirtualMapState(
            @NonNull final VirtualMap virtualMap,
            @NonNull final PlatformContext platformContext,
            @NonNull final Function<VirtualMapState<TestVirtualMapState>, Long> extractRoundFromState) {
        super(virtualMap, platformContext, extractRoundFromState);
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
            @NonNull final VirtualMap virtualMap,
            @NonNull final PlatformContext platformContext,
            @NonNull final Function<VirtualMapState<TestVirtualMapState>, Long> extractRoundFromState) {
        return new TestVirtualMapState(virtualMap, platformContext, extractRoundFromState);
    }

    public static TestVirtualMapState createInstanceWithVirtualMapLabel(
            @NonNull final String virtualMapLabel,
            @NonNull final PlatformContext platformContext,
            @NonNull final Function<VirtualMapState<TestVirtualMapState>, Long> extractRoundFromState) {
        final var virtualMap = VirtualMapUtils.createVirtualMap(virtualMapLabel);
        return new TestVirtualMapState(virtualMap, platformContext, extractRoundFromState);
    }
}
