// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils.randomHash;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;
import static com.swirlds.platform.test.fixtures.PlatformStateUtils.randomPlatformState;
import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.nextLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.state.State;
import com.swirlds.state.spi.EmptyReadableStates;
import java.time.Instant;
import org.hiero.consensus.utility.test.fixtures.RandomUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlatformStateFacadeTest {

    private static TestPlatformStateFacade platformStateFacade;
    private static MerkleNodeState state;
    private static MerkleNodeState emptyState;
    private static PlatformStateModifier platformStateModifier;

    @BeforeAll
    static void beforeAll() {
        state = new TestMerkleStateRoot();
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initPlatformState(state);
        emptyState = new TestMerkleStateRoot();
        platformStateFacade = new TestPlatformStateFacade();
        platformStateModifier = randomPlatformState(state, platformStateFacade);
    }

    @Test
    void isInFreezePeriodTest() {

        final Instant t1 = Instant.now();
        final Instant t2 = t1.plusSeconds(1);
        final Instant t3 = t2.plusSeconds(1);
        final Instant t4 = t3.plusSeconds(1);

        // No freeze time set
        assertFalse(PlatformStateFacade.isInFreezePeriod(t1, null, null));

        // No freeze time set, previous freeze time set
        assertFalse(PlatformStateFacade.isInFreezePeriod(t2, null, t1));

        // Freeze time is in the future, never frozen before
        assertFalse(PlatformStateFacade.isInFreezePeriod(t2, t3, null));

        // Freeze time is in the future, frozen before
        assertFalse(PlatformStateFacade.isInFreezePeriod(t2, t3, t1));

        // Freeze time is in the past, never frozen before
        assertTrue(PlatformStateFacade.isInFreezePeriod(t2, t1, null));

        // Freeze time is in the past, frozen before at an earlier time
        assertTrue(PlatformStateFacade.isInFreezePeriod(t3, t2, t1));

        // Freeze time in the past, already froze at that exact time
        assertFalse(PlatformStateFacade.isInFreezePeriod(t3, t2, t2));
    }

    @Test
    void testCreationSoftwareVersionOf() {
        assertEquals(
                platformStateModifier.getCreationSoftwareVersion(),
                platformStateFacade.creationSoftwareVersionOf(state));
    }

    @Test
    void testCreationSoftwareVersionOf_null() {
        assertNull(platformStateFacade.creationSoftwareVersionOf(emptyState));
    }

    @Test
    void testRoundOf() {
        assertEquals(platformStateModifier.getRound(), platformStateFacade.roundOf(state));
    }

    @Test
    void testPlatformStateOf_noPlatformState() {
        final TestMerkleStateRoot noPlatformState = new TestMerkleStateRoot();
        noPlatformState.getReadableStates(PlatformStateService.NAME);
        assertSame(UNINITIALIZED_PLATFORM_STATE, platformStateFacade.platformStateOf(noPlatformState));
    }

    @Test
    void testPlatformStateOf_unexpectedRootInstance() {
        final State rootOfUnexpectedType = Mockito.mock(State.class);
        when(rootOfUnexpectedType.getReadableStates(PlatformStateService.NAME))
                .thenReturn(EmptyReadableStates.INSTANCE);

        final PlatformState platformState = platformStateFacade.platformStateOf(rootOfUnexpectedType);
        assertSame(UNINITIALIZED_PLATFORM_STATE, platformState);
    }

    @Test
    void testLegacyRunningEventHashOf() {
        assertEquals(
                platformStateModifier.getLegacyRunningEventHash(), platformStateFacade.legacyRunningEventHashOf(state));
    }

    @Test
    void testAncientThresholdOf() {
        assertEquals(platformStateModifier.getAncientThreshold(), platformStateFacade.ancientThresholdOf(state));
    }

    @Test
    void testConsensusSnapshotOf() {
        assertEquals(platformStateModifier.getSnapshot(), platformStateFacade.consensusSnapshotOf(state));
    }

    @Test
    void testFirstVersionInBirthRoundModeOf() {
        assertEquals(
                platformStateModifier.getFirstVersionInBirthRoundMode(),
                platformStateFacade.firstVersionInBirthRoundModeOf(state));
    }

    @Test
    void testLastRoundBeforeBirthRoundModeOf() {
        assertEquals(
                platformStateModifier.getLastRoundBeforeBirthRoundMode(),
                platformStateFacade.lastRoundBeforeBirthRoundModeOf(state));
    }

    @Test
    void testLowestJudgeGenerationBeforeBirthRoundModeOf() {
        assertEquals(
                platformStateModifier.getLowestJudgeGenerationBeforeBirthRoundMode(),
                platformStateFacade.lowestJudgeGenerationBeforeBirthRoundModeOf(state));
    }

    @Test
    void testConsensusTimestampOf() {
        assertEquals(platformStateModifier.getConsensusTimestamp(), platformStateFacade.consensusTimestampOf(state));
    }

    @Test
    void testFreezeTimeOf() {
        assertEquals(platformStateModifier.getFreezeTime(), platformStateFacade.freezeTimeOf(state));
    }

    @Test
    void testUpdateLastFrozenTime() {
        final Instant newFreezeTime = Instant.now();
        platformStateFacade.bulkUpdateOf(state, v -> {
            v.setFreezeTime(newFreezeTime);
        });
        platformStateFacade.updateLastFrozenTime(state);
        assertEquals(newFreezeTime, platformStateModifier.getLastFrozenTime());
        assertEquals(newFreezeTime, platformStateFacade.lastFrozenTimeOf(state));
    }

    @Test
    void testBulkUpdateOf() {
        final Instant newFreezeTime = Instant.now();
        final Instant lastFrozenTime = Instant.now();
        final long round = nextLong();
        platformStateFacade.bulkUpdateOf(state, v -> {
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
        TestMerkleStateRoot randomState = new TestMerkleStateRoot();
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initPlatformState(randomState);
        PlatformStateModifier randomPlatformState = randomPlatformState(randomState, platformStateFacade);
        final var newSnapshot = randomPlatformState.getSnapshot();
        platformStateFacade.setSnapshotTo(state, newSnapshot);
        assertEquals(newSnapshot, platformStateModifier.getSnapshot());
    }

    @Test
    void testSetLegacyRunningEventHashTo() {
        final var newLegacyRunningEventHash = randomHash();
        platformStateFacade.setLegacyRunningEventHashTo(state, newLegacyRunningEventHash);
        assertEquals(newLegacyRunningEventHash, platformStateModifier.getLegacyRunningEventHash());
    }

    @Test
    void testSetCreationSoftwareVersionTo() {
        final var newCreationSoftwareVersion =
                SemanticVersion.newBuilder().major(RandomUtils.nextInt()).build();

        platformStateFacade.setCreationSoftwareVersionTo(state, newCreationSoftwareVersion);
        assertEquals(newCreationSoftwareVersion, platformStateModifier.getCreationSoftwareVersion());
    }

    @Test
    void testGetInfoString() {
        final var infoString = platformStateFacade.getInfoString(state, 1);
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
                .contains("Root hash:")
                .contains("First BR Version:")
                .contains("Last round before BR:")
                .contains("Lowest Judge Gen before BR")
                .contains("Lowest Judge Gen before BR")
                .contains("SingletonNode")
                .contains("PlatformStateService.PLATFORM_STATE");
    }
}
