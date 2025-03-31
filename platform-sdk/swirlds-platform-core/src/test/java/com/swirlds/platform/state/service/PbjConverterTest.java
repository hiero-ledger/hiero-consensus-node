// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils.randomHash;
import static com.swirlds.platform.state.service.PbjConverter.toPbjPlatformState;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.utility.CommonUtils.fromPbjTimestamp;
import static org.hiero.consensus.model.utility.CommonUtils.toPbjTimestamp;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.nextInt;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.randomInstant;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.state.PlatformStateModifier;
import java.time.Instant;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PbjConverterTest {
    private Randotron randotron;

    @BeforeEach
    void setUp() {
        randotron = Randotron.create();
    }

    @Test
    void testToPbjPlatformState() {
        final PlatformStateModifier platformState = randomPlatformState(randotron);

        final com.hedera.hapi.platform.state.PlatformState pbjPlatformState =
                PbjConverter.toPbjPlatformState(platformState);

        assertEquals(platformState.getCreationSoftwareVersion(), pbjPlatformState.creationSoftwareVersion());
        assertEquals(platformState.getRoundsNonAncient(), pbjPlatformState.roundsNonAncient());
        assertEquals(
                platformState.getLastFrozenTime().getEpochSecond(),
                pbjPlatformState.lastFrozenTime().seconds());
        assertEquals(platformState.getLegacyRunningEventHash().getBytes(), pbjPlatformState.legacyRunningEventHash());
        assertEquals(
                platformState.getLowestJudgeGenerationBeforeBirthRoundMode(),
                pbjPlatformState.lowestJudgeGenerationBeforeBirthRoundMode());
        assertEquals(platformState.getFirstVersionInBirthRoundMode(), pbjPlatformState.firstVersionInBirthRoundMode());

        assertEquals(platformState.getSnapshot(), pbjPlatformState.consensusSnapshot());
    }

    @Test
    void testToPbjTimestamp_null() {
        assertNull(toPbjTimestamp(null));
    }

    @Test
    void testToPbjTimestamp() {
        final Instant instant = randomInstant(randotron);
        final Timestamp pbjTimestamp = toPbjTimestamp(instant);
        assertEquals(instant.getEpochSecond(), pbjTimestamp.seconds());
    }

    @Test
    void testFromPbjTimestamp_null() {
        assertNull(fromPbjTimestamp(null));
    }

    @Test
    void testFromPbjTimestamp() {
        final Instant instant = randomInstant(randotron);
        final Timestamp pbjTimestamp = toPbjTimestamp(instant);
        assertEquals(instant, fromPbjTimestamp(pbjTimestamp));
    }

    @Test
    void testToPbjPlatformState_acc_updateCreationSoftwareVersion() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.creationSoftwareVersion(),
                toPbjPlatformState(oldState, accumulator).creationSoftwareVersion());

        final var newValue = randomSoftwareVersion();

        accumulator.setCreationSoftwareVersion(newValue);

        assertEquals(newValue, toPbjPlatformState(oldState, accumulator).creationSoftwareVersion());
    }

    @Test
    void testToPbjPlatformState_acc_updateRoundsNonAncient() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.roundsNonAncient(),
                toPbjPlatformState(oldState, accumulator).roundsNonAncient());

        final var newValue = nextInt();

        accumulator.setRoundsNonAncient(newValue);

        assertEquals(newValue, toPbjPlatformState(oldState, accumulator).roundsNonAncient());
    }

    @Test
    void testToPbjPlatformState_acc_freezeTime() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.freezeTime(), toPbjPlatformState(oldState, accumulator).freezeTime());

        final var newValue = randomInstant(randotron);

        accumulator.setFreezeTime(newValue);

        assertEquals(
                toPbjTimestamp(newValue),
                toPbjPlatformState(oldState, accumulator).freezeTime());
    }

    @Test
    void testToPbjPlatformState_acc_lastFrozenTime() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.freezeTime(), toPbjPlatformState(oldState, accumulator).freezeTime());

        final var newValue = randomInstant(randotron);

        accumulator.setLastFrozenTime(newValue);

        assertEquals(
                toPbjTimestamp(newValue),
                toPbjPlatformState(oldState, accumulator).lastFrozenTime());
    }

    @Test
    void testToPbjPlatformState_acc_legacyRunningEventHash() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.legacyRunningEventHash(),
                toPbjPlatformState(oldState, accumulator).legacyRunningEventHash());

        final var newValue = randomHash();

        accumulator.setLegacyRunningEventHash(newValue);

        assertArrayEquals(
                newValue.copyToByteArray(),
                toPbjPlatformState(oldState, accumulator)
                        .legacyRunningEventHash()
                        .toByteArray());
    }

    @Test
    void testToPbjPlatformState_acc_lowestJudgeGenerationBeforeBirthRoundMode() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.lowestJudgeGenerationBeforeBirthRoundMode(),
                toPbjPlatformState(oldState, accumulator).lowestJudgeGenerationBeforeBirthRoundMode());

        final var newValue = nextInt();

        accumulator.setLowestJudgeGenerationBeforeBirthRoundMode(newValue);

        assertEquals(newValue, toPbjPlatformState(oldState, accumulator).lowestJudgeGenerationBeforeBirthRoundMode());
    }

    @Test
    void testToPbjPlatformState_acc_firstVersionInBirthRoundMode() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.firstVersionInBirthRoundMode(),
                toPbjPlatformState(oldState, accumulator).firstVersionInBirthRoundMode());

        final var newValue = randomSoftwareVersion();

        accumulator.setFirstVersionInBirthRoundMode(newValue);

        assertEquals(newValue, toPbjPlatformState(oldState, accumulator).firstVersionInBirthRoundMode());
    }

    @Test
    void testToPbjPlatformState_acc_lastRoundBeforeBirthRoundMode() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        // no change without update is expected
        assertEquals(
                oldState.lastRoundBeforeBirthRoundMode(),
                toPbjPlatformState(oldState, accumulator).lastRoundBeforeBirthRoundMode());

        final var newValue = nextInt();

        accumulator.setLastRoundBeforeBirthRoundMode(newValue);

        assertEquals(newValue, toPbjPlatformState(oldState, accumulator).lastRoundBeforeBirthRoundMode());
    }

    @Test
    void testToPbjPlatformState_acc_round() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        final var newValue = nextInt();

        accumulator.setRound(newValue);

        assertEquals(
                newValue,
                toPbjPlatformState(oldState, accumulator).consensusSnapshot().round());
    }

    @Test
    void testToPbjPlatformState_acc_round_and_snapshot() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        final var newRound = nextInt();
        final var newSnapshot = randomSnapshot(randotron);

        accumulator.setRound(newRound);
        accumulator.setSnapshot(newSnapshot);

        // snapshot fields shouldn't be lost
        assertThat(toPbjPlatformState(oldState, accumulator).consensusSnapshot())
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withIgnoredFields("round")
                        .build())
                .isEqualTo(newSnapshot);
        assertEquals(
                newRound,
                toPbjPlatformState(oldState, accumulator).consensusSnapshot().round());
    }

    @Test
    void testToPbjPlatformState_acc_consensusSnapshotTimestamp() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        final var newValue = nextInt();

        accumulator.setRound(newValue);

        assertEquals(
                newValue,
                toPbjPlatformState(oldState, accumulator).consensusSnapshot().round());
    }

    @Test
    void testToPbjPlatformState_acc_consensusTimestamp_and_snapshot() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        final var consensusTimestamp = randomInstant(randotron);
        final var newSnapshot = randomSnapshot(randotron);

        accumulator.setConsensusTimestamp(consensusTimestamp);
        accumulator.setSnapshot(newSnapshot);

        // snapshot fields shouldn't be lost
        assertThat(toPbjPlatformState(oldState, accumulator).consensusSnapshot())
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withIgnoredFields("consensusTimestamp")
                        .build())
                .isEqualTo(newSnapshot);
        assertEquals(
                toPbjTimestamp(consensusTimestamp),
                toPbjPlatformState(oldState, accumulator).consensusSnapshot().consensusTimestamp());
    }

    @Test
    void testToPbjPlatformState_acc_updateAll() {
        final var oldState = randomPbjPlatformState();
        final var accumulator = new PlatformStateValueAccumulator();

        final var newValue = randomPlatformState(randotron);

        accumulator.setCreationSoftwareVersion(newValue.getCreationSoftwareVersion());
        accumulator.setRoundsNonAncient(newValue.getRoundsNonAncient());
        accumulator.setSnapshot(newValue.getSnapshot());
        accumulator.setFreezeTime(newValue.getLastFrozenTime());
        accumulator.setLastFrozenTime(newValue.getLastFrozenTime());
        accumulator.setLegacyRunningEventHash(newValue.getLegacyRunningEventHash());
        accumulator.setLowestJudgeGenerationBeforeBirthRoundMode(
                newValue.getLowestJudgeGenerationBeforeBirthRoundMode());
        accumulator.setFirstVersionInBirthRoundMode(newValue.getFirstVersionInBirthRoundMode());
        accumulator.setLastRoundBeforeBirthRoundMode(newValue.getLastRoundBeforeBirthRoundMode());

        final var pbjState = toPbjPlatformState(oldState, accumulator);

        assertEquals(newValue.getCreationSoftwareVersion(), pbjState.creationSoftwareVersion());
        assertEquals(newValue.getRoundsNonAncient(), pbjState.roundsNonAncient());
        assertEquals(newValue.getSnapshot(), pbjState.consensusSnapshot());
        assertEquals(toPbjTimestamp(newValue.getLastFrozenTime()), pbjState.freezeTime());
        assertEquals(toPbjTimestamp(newValue.getLastFrozenTime()), pbjState.lastFrozenTime());
        assertArrayEquals(
                newValue.getLegacyRunningEventHash().getBytes().toByteArray(),
                pbjState.legacyRunningEventHash().toByteArray());
        assertEquals(
                newValue.getLowestJudgeGenerationBeforeBirthRoundMode(),
                pbjState.lowestJudgeGenerationBeforeBirthRoundMode());
        assertEquals(newValue.getFirstVersionInBirthRoundMode(), pbjState.firstVersionInBirthRoundMode());
        assertEquals(newValue.getLastRoundBeforeBirthRoundMode(), pbjState.lastRoundBeforeBirthRoundMode());
    }

    static PlatformStateModifier randomPlatformState(final Randotron randotron) {
        final PlatformStateValueAccumulator platformState = new PlatformStateValueAccumulator();
        platformState.setCreationSoftwareVersion(randomSoftwareVersion());
        platformState.setRoundsNonAncient(nextInt());
        platformState.setLastFrozenTime(randomInstant(randotron));
        platformState.setLegacyRunningEventHash(randomHash());
        platformState.setLowestJudgeGenerationBeforeBirthRoundMode(nextInt());
        platformState.setLastRoundBeforeBirthRoundMode(nextInt());
        platformState.setFirstVersionInBirthRoundMode(randomSoftwareVersion());
        platformState.setSnapshot(randomSnapshot(randotron));
        return platformState;
    }

    private com.hedera.hapi.platform.state.PlatformState randomPbjPlatformState() {
        return toPbjPlatformState(randomPlatformState(randotron));
    }

    private static ConsensusSnapshot randomSnapshot(final Randotron randotron) {
        final var judges = asList(
                new JudgeId(0L, randomHash().getBytes()),
                new JudgeId(1L, randomHash().getBytes()));
        return ConsensusSnapshot.newBuilder()
                .round(nextInt())
                .judgeIds(judges)
                .minimumJudgeInfoList(
                        asList(new MinimumJudgeInfo(nextInt(), nextInt()), new MinimumJudgeInfo(nextInt(), nextInt())))
                .nextConsensusNumber(nextInt())
                .consensusTimestamp(toPbjTimestamp(randomInstant(randotron)))
                .build();
    }

    private static SemanticVersion randomSoftwareVersion() {
        return SemanticVersion.newBuilder().major(nextInt(1, 100)).build();
    }
}
