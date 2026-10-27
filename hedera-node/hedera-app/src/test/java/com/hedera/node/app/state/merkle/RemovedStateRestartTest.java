// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_EQUALITY_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_EXPIRY_SEC_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_ID_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULED_COUNTS_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULED_ORDERS_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULED_USAGES_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema.SCHEDULE_ID_BY_EQUALITY_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema;
import com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema;
import com.hedera.node.app.services.MigrationStateChanges;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.state.test.fixtures.merkle.TestStateUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig_;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.state.test.fixtures.RandomSignedStateGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins what restarting does to a state that a later schema removes, using the one production case:
 * {@link V0570ScheduleSchema} removes two of the states {@link V0490ScheduleSchema} creates. Since
 * every schema is applied on every startup, a restart registers those states again and then
 * unregisters them; as that only touches in-memory metadata, it must leave the saved state as it was.
 */
@ExtendWith(MockitoExtension.class)
class RemovedStateRestartTest extends MerkleTestBase {
    private static final ScheduleID SCHEDULE_ID =
            ScheduleID.newBuilder().scheduleNum(1234L).build();
    private static final Schedule SCHEDULE = Schedule.newBuilder()
            .scheduleId(SCHEDULE_ID)
            .memo("saved before the restart")
            .build();

    @Mock
    private MigrationStateChanges migrationStateChanges;

    @Mock
    private StartupNetworks startupNetworks;

    private Configuration config;

    @BeforeEach
    void setUp() {
        setupConstructableRegistry();
        config = new TestConfigBuilder()
                .withSource(
                        new SimpleConfigSource().withValue(VirtualMapConfig_.COPY_FLUSH_CANDIDATE_THRESHOLD, 1 + ""))
                .withConfigDataType(VirtualMapConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(CryptoConfig.class)
                .getOrCreateConfig();
    }

    @ParameterizedTest(name = "software version 0.{0}.0")
    @ValueSource(ints = {1, 79})
    @DisplayName("Restarting from a saved state re-applies a removal without changing the state")
    void restartFromSavedStateLeavesItUnchanged(final int softwareMinorVersion) throws IOException {
        // Schema registration and data preservation must also work below every schema version.
        final var softwareVersion = new SemanticVersion(0, softwareMinorVersion, 0, "", "");
        // Given a genesis state with the schedule service's schemas applied and a schedule saved
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(new NoOpMetrics(), new FakeTime(), config, FILE_SYSTEM_MANAGER);
        stateLifecycleManager.initWithState(
                new RandomSignedStateGenerator().setRound(1).build().getState());
        final var initialState = stateLifecycleManager.getLatestImmutableState();
        final var genesisState = stateLifecycleManager.getMutableState();
        initialState.release();
        applyScheduleSchemas(genesisState, null, softwareVersion, InitTrigger.GENESIS);
        final var writableStates = genesisState.getWritableStates(ScheduleService.NAME);
        writableStates.<ScheduleID, Schedule>get(SCHEDULES_BY_ID_STATE_ID).put(SCHEDULE_ID, SCHEDULE);
        ((CommittableWritableStates) writableStates).commit();

        // And a snapshot of that state saved to disk
        stateLifecycleManager.copyMutableState().release();
        final var savedState = stateLifecycleManager.getLatestImmutableState();
        savedState.computeHash();
        final var savedHash = savedState.getHash();
        final var snapshotDir = FILE_SYSTEM_MANAGER.resolveNewTemp();
        Files.createDirectories(snapshotDir);
        stateLifecycleManager.createSnapshot(savedState, snapshotDir);
        savedState.release();

        // When a node restarts from that snapshot
        assertThat(stateLifecycleManager.loadSnapshot(snapshotDir)).isEqualTo(savedHash);
        final var restartedState = stateLifecycleManager.getMutableState();
        applyScheduleSchemas(restartedState, softwareVersion, softwareVersion, InitTrigger.RESTART);

        // Then the removed states are unregistered again, and the surviving states are intact
        final var scheduleStates = restartedState.getReadableStates(ScheduleService.NAME);
        assertThat(scheduleStates.stateIds())
                .containsExactlyInAnyOrder(
                        SCHEDULES_BY_ID_STATE_ID,
                        SCHEDULE_ID_BY_EQUALITY_STATE_ID,
                        SCHEDULED_ORDERS_STATE_ID,
                        SCHEDULED_COUNTS_STATE_ID,
                        SCHEDULED_USAGES_STATE_ID);
        assertThat(scheduleStates.contains(SCHEDULES_BY_EXPIRY_SEC_STATE_ID)).isFalse();
        assertThat(scheduleStates.contains(SCHEDULES_BY_EQUALITY_STATE_ID)).isFalse();
        assertThat(scheduleStates
                        .<ScheduleID, Schedule>get(SCHEDULES_BY_ID_STATE_ID)
                        .get(SCHEDULE_ID))
                .isEqualTo(SCHEDULE);

        // And the restarted state hashes exactly as the saved one did, so nothing was written
        stateLifecycleManager.copyMutableState().release();
        final var rehashedState = stateLifecycleManager.getLatestImmutableState();
        rehashedState.computeHash();
        assertThat(rehashedState.getHash()).isEqualTo(savedHash);
        rehashedState.release();
        TestStateUtils.destroyStateLifecycleManager(stateLifecycleManager);
    }

    private void applyScheduleSchemas(
            final VirtualMapState state,
            @Nullable final SemanticVersion previousVersion,
            final SemanticVersion currentVersion,
            final InitTrigger trigger) {
        final var registry = new MerkleSchemaRegistry(ScheduleService.NAME, new SchemaApplications());
        registry.register(new V0490ScheduleSchema());
        registry.register(new V0570ScheduleSchema());
        registry.migrate(
                state,
                previousVersion,
                currentVersion,
                config,
                config,
                new HashMap<>(),
                migrationStateChanges,
                startupNetworks,
                trigger);
    }
}
