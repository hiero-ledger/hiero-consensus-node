// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery;

import static com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils.randomHash;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.base.utility.test.fixtures.RandomUtils.randomPositiveLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.recovery.emergencyfile.EmergencyRecoveryFile;
import com.swirlds.platform.recovery.internal.StreamedRound;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.RunningHash;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventRecoveryWorkflowTests {

    @TempDir
    Path tmpDir;

    private final StateConfig stateConfig =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(StateConfig.class);

    @Test
    @DisplayName("isFreezeState() Test")
    void isFreezeStateTest() {

        // no freeze time
        assertFalse(
                EventRecoveryWorkflow.isFreezeState(Instant.ofEpochSecond(0), Instant.ofEpochSecond(0), null),
                "unexpected freeze behavior");

        // previous before, current before
        assertFalse(
                EventRecoveryWorkflow.isFreezeState(
                        Instant.ofEpochSecond(0), Instant.ofEpochSecond(1), Instant.ofEpochSecond(100)),
                "unexpected freeze behavior");

        // previous before, current equal
        assertTrue(
                EventRecoveryWorkflow.isFreezeState(
                        Instant.ofEpochSecond(0), Instant.ofEpochSecond(100), Instant.ofEpochSecond(100)),
                "unexpected freeze behavior");

        // previous before, current after
        assertTrue(
                EventRecoveryWorkflow.isFreezeState(
                        Instant.ofEpochSecond(0), Instant.ofEpochSecond(101), Instant.ofEpochSecond(100)),
                "unexpected freeze behavior");

        // previous equal, current after
        assertFalse(
                EventRecoveryWorkflow.isFreezeState(
                        Instant.ofEpochSecond(100), Instant.ofEpochSecond(101), Instant.ofEpochSecond(100)),
                "unexpected freeze behavior");

        // previous after, current after
        assertFalse(
                EventRecoveryWorkflow.isFreezeState(
                        Instant.ofEpochSecond(101), Instant.ofEpochSecond(102), Instant.ofEpochSecond(100)),
                "unexpected freeze behavior");
    }

    @Test
    @DisplayName("applyTransactions() Test")
    void applyTransactionsTest() {
        final List<ConsensusEvent> events = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            events.add(mock(PlatformEvent.class));
        }

        final Round round = mock(Round.class);
        when(round.iterator()).thenReturn(events.iterator());

        final List<PlatformEvent> preHandleList = new ArrayList<>();
        final AtomicBoolean roundHandled = new AtomicBoolean(false);

        final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler =
                mock(ConsensusStateEventHandler.class);
        final MerkleNodeState immutableState = mock(MerkleNodeState.class);
        doAnswer(invocation -> {
                    assertFalse(roundHandled.get(), "round should not have been handled yet");
                    preHandleList.add(invocation.getArgument(0));
                    return null;
                })
                .when(consensusStateEventHandler)
                .onPreHandle(any(), same(immutableState), any());
        doAnswer(invocation -> {
                    fail("mutable state should handle transactions");
                    return null;
                })
                .when(consensusStateEventHandler)
                .onHandleConsensusRound(any(), same(immutableState), any());

        final MerkleNodeState mutableState = mock(MerkleNodeState.class);
        doAnswer(invocation -> {
                    fail("immutable state should pre-handle transactions");
                    return null;
                })
                .when(consensusStateEventHandler)
                .onPreHandle(any(), same(mutableState), any());
        doAnswer(invocation -> {
                    assertFalse(roundHandled.get(), "round should only be handled once");
                    assertSame(round, invocation.getArgument(0), "unexpected round");
                    roundHandled.set(true);
                    return null;
                })
                .when(consensusStateEventHandler)
                .onHandleConsensusRound(any(), same(mutableState), any());

        EventRecoveryWorkflow.applyTransactions(consensusStateEventHandler, immutableState, mutableState, round);

        assertEquals(events.size(), preHandleList.size(), "incorrect number of pre-handle calls");
        for (int index = 0; index < events.size(); index++) {
            assertSame(events.get(index), preHandleList.get(index));
        }
        assertTrue(roundHandled.get(), "round not handled");
    }

    @Test
    @DisplayName("getRoundTimestamp() Test")
    void getRoundTimestampTest() {
        final int eventCount = 100;

        final List<ConsensusEvent> events = new ArrayList<>();
        for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
            final PlatformEvent event = mock(PlatformEvent.class);
            when(event.getConsensusTimestamp()).thenReturn(Instant.ofEpochSecond(eventIndex));
            events.add(event);
        }

        final Round round = mock(Round.class);
        when(round.iterator()).thenReturn(events.iterator());

        final Instant roundTimestamp = EventRecoveryWorkflow.getRoundTimestamp(round);

        assertEquals(Instant.ofEpochSecond(eventCount - 1), roundTimestamp, "unexpected timestamp");
    }

    private CesEvent buildEventWithRunningHash(final Hash eventHash) {
        final CesEvent event = mock(CesEvent.class);
        when(event.getHash()).thenReturn(eventHash);
        final RunningHash runningHash = new RunningHash();
        when(event.getRunningHash()).thenReturn(runningHash);
        return event;
    }

    /**
     * The running hash implementation is quite bad -- hashing the same object twice is not deterministic since hashing
     * leaves behind metadata. To work around this, this method creates a fresh copy of an event list that does not
     * share a metadata link.
     */
    private List<ConsensusEvent> copyRunningHashEvents(final List<ConsensusEvent> original) {
        final List<ConsensusEvent> copy = new ArrayList<>();
        original.forEach(event -> copy.add(buildEventWithRunningHash((((CesEvent) event).getHash()))));
        return copy;
    }

    private StreamedRound buildMockRound(final List<ConsensusEvent> events) {
        final StreamedRound round = mock(StreamedRound.class);
        when(round.iterator()).thenReturn(events.iterator());
        return round;
    }

    @Test
    @DisplayName("getHashEventConsTest")
    void getHashEventConsTest() {
        final Random random = getRandomPrintSeed();
        final int eventCount = 100;

        final Hash initialHash1 = randomHash(random);

        final List<ConsensusEvent> events1 = new ArrayList<>();
        for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
            final CesEvent event = buildEventWithRunningHash(randomHash(random));
            events1.add(event);
        }
        final Hash hash1 = EventRecoveryWorkflow.getHashEventsCons(initialHash1, buildMockRound(events1));

        // Hash should be deterministic
        assertEquals(
                hash1,
                EventRecoveryWorkflow.getHashEventsCons(initialHash1, buildMockRound(copyRunningHashEvents(events1))),
                "hash should be deterministic");

        // Different starting hash
        final Hash initialHash2 = randomHash(random);
        assertNotEquals(
                hash1,
                EventRecoveryWorkflow.getHashEventsCons(initialHash2, buildMockRound(copyRunningHashEvents(events1))),
                "hash should have changed");

        // add another event
        final List<ConsensusEvent> events2 = copyRunningHashEvents(events1);
        final CesEvent newEvent = buildEventWithRunningHash(randomHash(random));
        events2.add(newEvent);
        assertNotEquals(
                hash1,
                EventRecoveryWorkflow.getHashEventsCons(initialHash1, buildMockRound(events2)),
                "hash should have changed");

        // remove an event
        final List<ConsensusEvent> events3 = copyRunningHashEvents(events1);
        events3.remove(events3.size() - 1);
        assertNotEquals(
                hash1,
                EventRecoveryWorkflow.getHashEventsCons(initialHash1, buildMockRound(events3)),
                "hash should have changed");

        // replace an event
        final List<ConsensusEvent> events4 = copyRunningHashEvents(events1);
        final CesEvent replacementEvent = buildEventWithRunningHash(randomHash(random));
        events4.set(0, replacementEvent);
        assertNotEquals(
                hash1,
                EventRecoveryWorkflow.getHashEventsCons(initialHash1, buildMockRound(events4)),
                "hash should have changed");
    }

    @Test
    void testUpdateEmergencyRecoveryFile() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();
        final Hash hash = randomHash(random);
        final long round = randomPositiveLong(random);
        final Instant stateTimestamp = Instant.ofEpochMilli(randomPositiveLong(random));

        final EmergencyRecoveryFile recoveryFile = new EmergencyRecoveryFile(round, hash, stateTimestamp);
        recoveryFile.write(tmpDir);

        final Instant bootstrapTime = Instant.ofEpochMilli(randomPositiveLong(random));

        EventRecoveryWorkflow.updateEmergencyRecoveryFile(stateConfig, tmpDir, bootstrapTime);

        // Verify the contents of the updated recovery file
        final EmergencyRecoveryFile updatedRecoveryFile = EmergencyRecoveryFile.read(stateConfig, tmpDir);
        assertNotNull(updatedRecoveryFile, "Updated recovery file should not be null");
        assertEquals(round, updatedRecoveryFile.round(), "round does not match");
        assertEquals(hash, updatedRecoveryFile.hash(), "hash does not match");
        assertEquals(stateTimestamp, updatedRecoveryFile.timestamp(), "state timestamp does not match");
        assertNotNull(updatedRecoveryFile.recovery().bootstrap(), "bootstrap should not be null");
        assertEquals(
                bootstrapTime,
                updatedRecoveryFile.recovery().bootstrap().timestamp(),
                "bootstrap timestamp does not match");

        // Verify the contents of the backup recovery file (copy of the original)
        final EmergencyRecoveryFile backupFile = EmergencyRecoveryFile.read(stateConfig, tmpDir.resolve("backup"));
        assertNotNull(backupFile, "Updated recovery file should not be null");
        assertEquals(round, backupFile.round(), "round does not match");
        assertEquals(hash, backupFile.hash(), "hash does not match");
        assertEquals(stateTimestamp, backupFile.timestamp(), "state timestamp does not match");
        assertNull(backupFile.recovery().bootstrap(), "No bootstrap information should exist in the backup");
    }

    // FUTURE WORK reapplyTransactions() test
    // FUTURE WORK recoverState() test
}
