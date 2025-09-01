// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
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

    /**
     * Constructs a {@link HederaVirtualMapState}.
     *
     * @param configuration the platform configuration instance to use when creating the new instance of state
     * @param metrics       the platform metric instance to use when creating the new instance of state
     * @param time          the time instance to use when creating the new instance of state
     */
    public HederaVirtualMapState(
            @NonNull final Configuration configuration, @NonNull final Metrics metrics, @NonNull final Time time) {
        super(configuration, metrics, time);
    }

    /**
     * Constructs a {@link HederaVirtualMapState} using the specified {@link VirtualMap}.
     *
     * @param virtualMap    the virtual map whose metrics must already be registered
     * @param configuration the platform configuration instance to use when creating the new instance of state
     * @param metrics       the platform metric instance to use when creating the new instance of state
     * @param time          the time instance to use when creating the new instance of state
     */
    public HederaVirtualMapState(
            @NonNull final VirtualMap virtualMap,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time) {
        super(virtualMap, configuration, metrics, time);
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
     * @param virtualMap    the virtual map whose metrics must already be registered
     * @param configuration the platform configuration instance to use when creating the new instance of state
     * @param metrics       the platform metric instance to use when creating the new instance of state
     * @param time          the time instance to use when creating the new instance of state
     * @return a new instance of {@link HederaVirtualMapState}
     */
    @Override
    protected HederaVirtualMapState newInstance(
            @NonNull final VirtualMap virtualMap,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time) {
        return new HederaVirtualMapState(virtualMap, configuration, metrics, time);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRound() {
        return DEFAULT_PLATFORM_STATE_FACADE.roundOf(this);
    }
}
