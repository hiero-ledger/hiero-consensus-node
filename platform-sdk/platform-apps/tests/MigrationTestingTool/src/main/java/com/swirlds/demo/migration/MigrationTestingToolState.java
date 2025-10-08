// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public class MigrationTestingToolState extends VirtualMapState<MigrationTestingToolState> implements MerkleNodeState {

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
}
