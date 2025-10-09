// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.CONFIGURATION;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public class MigrationTestingToolState extends VirtualMapState<MigrationTestingToolState> implements MerkleNodeState {

    //super(new NoOpMetrics(), Time.getCurrent(), MerkleCryptographyFactory.create(CONFIGURATION));
    public MigrationTestingToolState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        super(configuration, metrics);
    }

    public MigrationTestingToolState(@NonNull final VirtualMap virtualMap) {
        super(virtualMap);
    }

    private MigrationTestingToolState(final MigrationTestingToolState that) {
        super(that);
    }

    @Override
    protected MigrationTestingToolState copyingConstructor() {
        return new MigrationTestingToolState(this);
    }

    @Override
    protected MigrationTestingToolState newInstance(@NonNull VirtualMap virtualMap) {
        return new MigrationTestingToolState(virtualMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected long getRound() {
        return DEFAULT_PLATFORM_STATE_FACADE.roundOf(this);
    }
}
