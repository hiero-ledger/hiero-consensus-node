// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.platformstate;

import static com.swirlds.platform.test.fixtures.PlatformStateUtils.randomPlatformState;
import static com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils.createTestState;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.base.utility.test.fixtures.RandomUtils.nextLong;
import static org.hiero.consensus.platformstate.PlatformStateUtils.ancientThresholdOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.bulkUpdateOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.consensusSnapshotOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.consensusTimestampOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.creationSoftwareVersionOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.freezeTimeOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.getInfoString;
import static org.hiero.consensus.platformstate.PlatformStateUtils.lastFrozenTimeOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.legacyRunningEventHashOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.platformStateOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.roundOf;
import static org.hiero.consensus.platformstate.PlatformStateUtils.setCreationSoftwareVersionTo;
import static org.hiero.consensus.platformstate.PlatformStateUtils.setLegacyRunningEventHashTo;
import static org.hiero.consensus.platformstate.PlatformStateUtils.setSnapshotTo;
import static org.hiero.consensus.platformstate.PlatformStateUtils.updateLastFrozenTime;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.State;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.EmptyReadableStates;
import java.time.Instant;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlatformStateUtilsTest {

    private MerkleNodeState state;
    private MerkleNodeState emptyState;
    private PlatformStateModifier platformStateModifier;

    @BeforeEach
    void beforeEach() {
        state = createTestState();
        TestingAppStateInitializer.initPlatformState(state);
        emptyState = createTestState();
        platformStateModifier = randomPlatformState(state);
    }

    @AfterEach
    void tearDown() {
        state.release();
        emptyState.release();

        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @Test
    void isInFreezePeriodTest() {

        final Instant t1 = Instant.now();
        final Instant t2 = t1.plusSeconds(1);
        final Instant t3 = t2.plusSeconds(1);
        final Instant t4 = t3.plusSeconds(1);

        // No freeze time set
        assertFalse(PlatformStateUtils.isInFreezePeriod(t1, null, null));

        // No freeze time set, previous freeze time set
        assertFalse(PlatformStateUtils.isInFreezePeriod(t2, null, t1));

        // Freeze time is in the future, never frozen before
        assertFalse(PlatformStateUtils.isInFreezePeriod(t2, t3, null));

        // Freeze time is in the future, frozen before
        assertFalse(PlatformStateUtils.isInFreezePeriod(t2, t3, t1));

        // Freeze time is in the past, never frozen before
        assertTrue(PlatformStateUtils.isInFreezePeriod(t2, t1, null));

        // Freeze time is in the past, frozen before at an earlier time
        assertTrue(PlatformStateUtils.isInFreezePeriod(t3, t2, t1));

        // Freeze time in the past, already froze at that exact time
        assertFalse(PlatformStateUtils.isInFreezePeriod(t3, t2, t2));
    }

    @Test
    void testCreationSoftwareVersionOf() {
        assertEquals(platformStateModifier.getCreationSoftwareVersion(), creationSoftwareVersionOf(state));
    }

    @Test
    void testCreationSoftwareVersionOf_null() {
        assertNull(creationSoftwareVersionOf(emptyState));
    }

    @Test
    void testRoundOf() {
        assertEquals(platformStateModifier.getRound(), roundOf(state));
    }

    @Test
    void testPlatformStateOf_noPlatformState() {
        final var virtualMapLabel =
                "vm-" + PlatformStateUtilsTest.class.getSimpleName() + "-" + java.util.UUID.randomUUID();
        final VirtualMapState noPlatformState = createTestState();
        noPlatformState.getReadableStates(PlatformStateService.NAME);
        assertSame(UNINITIALIZED_PLATFORM_STATE, platformStateOf(noPlatformState));
        noPlatformState.release();
    }

    @Test
    void testPlatformStateOf_unexpectedRootInstance() {
        final State rootOfUnexpectedType = Mockito.mock(State.class);
        when(rootOfUnexpectedType.getReadableStates(PlatformStateService.NAME))
                .thenReturn(EmptyReadableStates.INSTANCE);

        final PlatformState platformState = platformStateOf(rootOfUnexpectedType);
        assertSame(UNINITIALIZED_PLATFORM_STATE, platformState);
    }

    @Test
    void testLegacyRunningEventHashOf() {
        assertEquals(platformStateModifier.getLegacyRunningEventHash(), legacyRunningEventHashOf(state));
    }

    @Test
    void testAncientThresholdOf() {
        assertEquals(platformStateModifier.getAncientThreshold(), ancientThresholdOf(state));
    }

    @Test
    void testConsensusSnapshotOf() {
        assertEquals(platformStateModifier.getSnapshot(), consensusSnapshotOf(state));
    }

    @Test
    void testConsensusTimestampOf() {
        assertEquals(platformStateModifier.getConsensusTimestamp(), consensusTimestampOf(state));
    }

    @Test
    void testFreezeTimeOf() {
        assertEquals(platformStateModifier.getFreezeTime(), freezeTimeOf(state));
    }

    @Test
    void testUpdateLastFrozenTime() {
        final Instant newFreezeTime = Instant.now();
        bulkUpdateOf(state, v -> {
            v.setFreezeTime(newFreezeTime);
        });
        updateLastFrozenTime(state);
        assertEquals(newFreezeTime, platformStateModifier.getLastFrozenTime());
        assertEquals(newFreezeTime, lastFrozenTimeOf(state));
    }

    @Test
    void testBulkUpdateOf() {
        final Instant newFreezeTime = Instant.now();
        final Instant lastFrozenTime = Instant.now();
        final long round = nextLong();
        bulkUpdateOf(state, v -> {
            v.setFreezeTime(newFreezeTime);
            v.setRound(round);
            v.setLastFrozenTime(lastFrozenTime);
        });
        assertEquals(newFreezeTime, platformStateModifier.getFreezeTime());
        assertEquals(lastFrozenTime, platformStateModifier.getLastFrozenTime());
        assertEquals(round, platformStateModifier.getRound());
    }

    @Test
    void testSetSnapshotTo() {
        final String virtualMapLabel =
                "vm-" + PlatformStateUtilsTest.class.getSimpleName() + "-" + java.util.UUID.randomUUID();
        final VirtualMapState randomState = createTestState();
        TestingAppStateInitializer.initPlatformState(randomState);
        PlatformStateModifier randomPlatformState = randomPlatformState(randomState);
        final var newSnapshot = randomPlatformState.getSnapshot();
        setSnapshotTo(state, newSnapshot);
        assertEquals(newSnapshot, platformStateModifier.getSnapshot());
        randomState.release();
    }

    @Test
    void testSetLegacyRunningEventHashTo() {
        final var newLegacyRunningEventHash = CryptoRandomUtils.randomHash();
        setLegacyRunningEventHashTo(state, newLegacyRunningEventHash);
        assertEquals(newLegacyRunningEventHash, platformStateModifier.getLegacyRunningEventHash());
    }

    @Test
    void testSetCreationSoftwareVersionTo() {
        final var newCreationSoftwareVersion =
                SemanticVersion.newBuilder().major(RandomUtils.nextInt()).build();

        setCreationSoftwareVersionTo(state, newCreationSoftwareVersion);
        assertEquals(newCreationSoftwareVersion, platformStateModifier.getCreationSoftwareVersion());
    }

    @Test
    void testGetInfoString() {
        final var infoString = getInfoString(state);
        System.out.println(infoString);
        assertThat(infoString)
                .contains("Round:")
                .contains("Timestamp:")
                .contains("Next consensus number:")
                .contains("Legacy running event hash:")
                .contains("Legacy running event mnemonic:")
                .contains("Rounds non-ancient:")
                .contains("Creation version:")
                .contains("Minimum judge hash code:")
                .contains("Root hash:");
    }
}
