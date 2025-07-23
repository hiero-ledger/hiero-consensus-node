// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.config.data.BlockStreamConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures the state changes that occur during a migration.
 */
public class MigrationStateChanges {
    private final List<List<StateChange>> stateChanges = new ArrayList<>();
    private final ImmediateStateChangeListener immediateStateChangeListener = new ImmediateStateChangeListener();
    private final BoundaryStateChangeListener roundStateChangeListener;
    private final State state;

    /**
     * Constructs a new instance of {@link MigrationStateChanges} based on migration
     * changes to the given state.
     *
     * @param state  The state to track changes on
     * @param config The configuration for the state
     */
    public MigrationStateChanges(
            @NonNull final State state,
            @NonNull final Configuration config,
            @NonNull final StoreMetricsService storeMetricsService) {
        requireNonNull(config);
        requireNonNull(storeMetricsService);

        this.state = requireNonNull(state);
        this.roundStateChangeListener = new BoundaryStateChangeListener(storeMetricsService, () -> config);
        if (config.getConfigData(BlockStreamConfig.class).streamMode() != RECORDS) {
            state.registerCommitListener(immediateStateChangeListener);
            state.registerCommitListener(roundStateChangeListener);
        }
    }

    /**
     * If any key/value changes have been made since the last call, inserts a {@link BlockItem}
     * boundary into the state changes, necessary so that block nodes can commit the same
     * transactional units into {@link com.swirlds.state.spi.WritableKVState} instances.
     */
    public void trackCommit() {
        final var maybeKvChanges = immediateStateChangeListener.getKvStateChanges();
        if (!maybeKvChanges.isEmpty()) {
            stateChanges.add(new ArrayList<>(maybeKvChanges));
            immediateStateChangeListener.resetKvStateChanges(null);
        }
    }

    /**
     * Returns the state changes that occurred during the migration, in the form
     * of {@link StateChanges} builders that represent transactional units.
     * @return the state changes that occurred during the migration
     */
    public List<StateChanges.Builder> getStateChanges() {
        final var roundChanges = roundStateChangeListener.allStateChanges();
        if (!roundChanges.isEmpty()) {
            stateChanges.add(roundChanges);
        }
        state.unregisterCommitListener(immediateStateChangeListener);
        state.unregisterCommitListener(roundStateChangeListener);

        return stateChanges.stream()
                .map(changes -> StateChanges.newBuilder().stateChanges(changes))
                .toList();
    }
}
