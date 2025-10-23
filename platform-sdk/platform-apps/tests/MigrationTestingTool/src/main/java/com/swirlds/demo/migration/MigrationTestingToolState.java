// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public class MigrationTestingToolState extends VirtualMapState<MigrationTestingToolState> implements MerkleNodeState {

    public MigrationTestingToolState(
            @NonNull final Configuration configuration, @NonNull final Metrics metrics, @NonNull final Time time) {
        super(configuration, metrics, time);
    }

    public MigrationTestingToolState(
            @NonNull final VirtualMap virtualMap, @NonNull final Metrics metrics, @NonNull final Time time) {
        super(virtualMap, metrics, time);
    }

    private MigrationTestingToolState(final MigrationTestingToolState that) {
        super(that);
    }

    @Override
    protected MigrationTestingToolState copyingConstructor() {
        return new MigrationTestingToolState(this);
    }

    @Override
    protected MigrationTestingToolState newInstance(
            @NonNull final VirtualMap virtualMap, @NonNull final Metrics metrics, @NonNull final Time time) {
        return new MigrationTestingToolState(virtualMap, metrics, time);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected long getRound() {
        return DEFAULT_PLATFORM_STATE_FACADE.roundOf(this);
    }
}
