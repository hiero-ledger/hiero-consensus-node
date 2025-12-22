// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.FREEZE_TIME_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.networkadmin.FreezeService;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.WritablePlatformStateStore;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple facility that notifies interested parties when the freeze state is updated.
 */
@Singleton
public class PlatformStateUpdates {

    private static final Logger logger = LogManager.getLogger(PlatformStateUpdates.class);

    private final BiConsumer<Roster, Path> rosterExportHelper;

    /**
     * Creates a new instance of this class.
     */
    @Inject
    public PlatformStateUpdates(@NonNull final BiConsumer<Roster, Path> rosterExportHelper) {
        this.rosterExportHelper = requireNonNull(rosterExportHelper);
    }

    /**
     * Checks whether the given transaction body is a freeze transaction and eventually
     * notifies the registered facility.
     *
     * @param state  the current state
     * @param txBody the transaction body
     * @param config the configuration
     */
    public void handleTxBody(
            @NonNull final State state, @NonNull final TransactionBody txBody, @NonNull final Configuration config) {
        requireNonNull(state, "state must not be null");
        requireNonNull(txBody, "txBody must not be null");
        requireNonNull(config, "config must not be null");

        if (txBody.hasFreeze()) {
            final var freezeType = txBody.freezeOrThrow().freezeType();
            final var platformStateStore =
                    new WritablePlatformStateStore(state.getWritableStates(PlatformStateService.NAME));
            switch (freezeType) {
                case UNKNOWN_FREEZE_TYPE, TELEMETRY_UPGRADE, PREPARE_UPGRADE -> {
                    // No-op
                }
                case FREEZE_UPGRADE, FREEZE_ONLY -> {
                    logger.info("Transaction freeze of type {} detected", freezeType);
                    // Copy freeze time to platform state
                    final var states = state.getReadableStates(FreezeService.NAME);
                    final ReadableSingletonState<Timestamp> freezeTimeState = states.getSingleton(FREEZE_TIME_STATE_ID);
                    final var freezeTime = requireNonNull(freezeTimeState.get());
                    final var freezeTimeInstant = Instant.ofEpochSecond(freezeTime.seconds(), freezeTime.nanos());
                    logger.info("Freeze time will be {}", freezeTimeInstant);
                    platformStateStore.setFreezeTime(freezeTimeInstant);
                }
                case FREEZE_ABORT -> {
                    logger.info("Aborting freeze");
                    platformStateStore.setFreezeTime(null);
                }
            }
        }
    }
}
