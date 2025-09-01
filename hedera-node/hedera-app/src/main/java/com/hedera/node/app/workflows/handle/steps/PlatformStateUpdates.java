// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.FREEZE_TIME_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.impl.ReadableNodeStoreImpl;
import com.hedera.node.app.service.networkadmin.FreezeService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableStakingInfoStoreImpl;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.WritablePlatformStateStore;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.WritableRosterStore;

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
                case UNKNOWN_FREEZE_TYPE, TELEMETRY_UPGRADE -> {
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
                case PREPARE_UPGRADE -> {
                    final var networkAdminConfig = config.getConfigData(NetworkAdminConfig.class);
                    // Even if using the roster lifecycle, we only set the candidate roster at PREPARE_UPGRADE if
                    // TSS machinery is not creating candidate rosters and keying them at stake period boundaries
                    final var addressBookConfig = config.getConfigData(AddressBookConfig.class);
                    final var entityIdStore =
                            new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
                    if (addressBookConfig.createCandidateRosterOnPrepareUpgrade()) {
                        final var nodeStore = new ReadableNodeStoreImpl(
                                state.getReadableStates(AddressBookService.NAME), entityIdStore);
                        final var rosterStore = new WritableRosterStore(state.getWritableStates(RosterService.NAME));
                        final var entityCounters =
                                new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
                        final var stakingInfoStore = new ReadableStakingInfoStoreImpl(
                                state.getReadableStates(TokenService.NAME), entityCounters);

                        // Get a candidate roster with the latest weights
                        var candidateRoster = nodeStore.snapshotOfFutureRoster(stakingInfoStore.weightFunction());
                        // Ensure we don't have a candidate roster with all zero weights by preserving
                        // weights from the current roster when no HBAR is staked to any node
                        if (hasZeroWeight(candidateRoster)) {
                            candidateRoster =
                                    assignWeights(candidateRoster, requireNonNull(rosterStore.getActiveRoster()));
                        }
                        logger.info("Candidate roster with updated weights is {}", candidateRoster);
                        boolean rosterAccepted = false;
                        try {
                            rosterStore.putCandidateRoster(candidateRoster);
                            rosterAccepted = true;
                        } catch (Exception e) {
                            logger.warn("Candidate roster was rejected", e);
                        }
                        if (rosterAccepted) {
                            // If the candidate roster needs to be exported, export the file
                            if (networkAdminConfig.exportCandidateRoster()) {
                                doExport(candidateRoster, networkAdminConfig);
                            }
                        }
                    }
                }
            }
        }
    }

    private Roster assignWeights(@NonNull final Roster to, @NonNull final Roster from) {
        final Map<Long, Long> fromWeights =
                from.rosterEntries().stream().collect(Collectors.toMap(RosterEntry::nodeId, RosterEntry::weight));
        return new Roster(to.rosterEntries().stream()
                .map(entry -> entry.copyBuilder()
                        .weight(fromWeights.getOrDefault(entry.nodeId(), 0L))
                        .build())
                .toList());
    }

    private boolean hasZeroWeight(@NonNull final Roster roster) {
        return roster.rosterEntries().stream().mapToLong(RosterEntry::weight).sum() == 0L;
    }

    private void doExport(@NonNull final Roster candidateRoster, @NonNull final NetworkAdminConfig networkAdminConfig) {
        final var exportPath = Paths.get(networkAdminConfig.candidateRosterExportFile());
        logger.info("Exporting candidate roster after PREPARE_UPGRADE to '{}'", exportPath.toAbsolutePath());
        rosterExportHelper.accept(candidateRoster, exportPath);
    }
}
