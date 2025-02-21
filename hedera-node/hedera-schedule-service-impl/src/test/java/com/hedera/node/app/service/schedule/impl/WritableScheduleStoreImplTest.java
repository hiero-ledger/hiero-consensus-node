// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.node.app.spi.workflows.PreCheckException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.InvalidKeyException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WritableScheduleStoreImplTest extends ScheduleTestBase {

    @BeforeEach
    void setUp() throws PreCheckException, InvalidKeyException {
        setUpBase();
    }

    @Test
    void verifyGetNullIsNull() {
        final var actual = writableSchedules.get(null);
        assertThat(actual).isNull();
    }

    @Test
    void verifyDeleteMarksDeletedInState() {
        final ScheduleID idToDelete = scheduleInState.scheduleId();
        Schedule actual = writableById.get(idToDelete);
        assertThat(actual).isNotNull().extracting("deleted").isEqualTo(Boolean.FALSE);
        writableSchedules.delete(idToDelete, testConsensusTime);
        actual = scheduleStore.get(idToDelete);
        assertThat(actual).isNotNull().extracting("deleted").isEqualTo(Boolean.TRUE);
        assertThat(actual.resolutionTime()).isNotNull().isEqualTo(asTimestamp(testConsensusTime));
    }

    private Timestamp asTimestamp(final Instant testConsensusTime) {
        return new Timestamp(testConsensusTime.getEpochSecond(), testConsensusTime.getNano());
    }

    @Test
    void verifyDeleteNonExistentScheduleThrows() {
        assertThatThrownBy(() -> writableSchedules.delete(ScheduleID.DEFAULT, testConsensusTime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Schedule to be deleted, " + ScheduleID.DEFAULT + ", not found in state.");
    }

    @Test
    void verifyDeleteNullScheduleThrows() {
        assertThatThrownBy(() -> writableSchedules.delete(null, testConsensusTime))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void verifyPutModifiesState() {
        final ScheduleID idToDelete = scheduleInState.scheduleIdOrThrow();
        Schedule actual = writableById.get(idToDelete);
        assertThat(actual).isNotNull();
        assertThat(actual.signatories()).containsExactlyInAnyOrderElementsOf(scheduleInState.signatories());
        final Set<Key> modifiedSignatories = Set.of(schedulerKey, payerKey);
        final Schedule modified = replaceSignatoriesAndMarkExecuted(actual, modifiedSignatories, testConsensusTime);
        writableSchedules.put(modified);
        actual = scheduleStore.get(idToDelete);
        assertThat(actual).isNotNull();
        assertThat(actual.executed()).isTrue();
        assertThat(actual.resolutionTime()).isNotNull().isEqualTo(asTimestamp(testConsensusTime));
        assertThat(actual.signatories()).containsExactlyInAnyOrderElementsOf(modifiedSignatories);
    }

    @Test
    void verifyPutDoesDeduplication() {
        final ScheduleID idToDelete = scheduleInState.scheduleId();
        Schedule actual = writableById.get(idToDelete);
        assertThat(actual).isNotNull();
        assertThat(actual.signatories()).containsExactlyInAnyOrderElementsOf(scheduleInState.signatories());
        final Set<Key> modifiedSignatories = Set.of(schedulerKey, payerKey);
        final Schedule modified = replaceSignatoriesAndMarkExecuted(actual, modifiedSignatories, testConsensusTime);
        final var hash = new ProtoBytes(ScheduleStoreUtility.calculateBytesHash(actual));

        final var equality = writableByEquality.get(hash);
        assertThat(equality).isNotNull();

        final var scheduledCounts =
                writableScheduledCounts.get(new TimestampSeconds(actual.calculatedExpirationSecond()));
        assertThat(scheduledCounts).isNotNull();
        assertThat(scheduledCounts.numberScheduled()).isEqualTo(1);

        writableSchedules.put(modified);
        writableSchedules.put(modified);
        writableSchedules.put(modified);

        actual = scheduleStore.get(idToDelete);
        assertThat(actual).isNotNull();
        assertThat(actual.executed()).isTrue();
        assertThat(actual.resolutionTime()).isNotNull().isEqualTo(asTimestamp(testConsensusTime));
        assertThat(actual.signatories()).containsExactlyInAnyOrderElementsOf(modifiedSignatories);

        // size doesn't increase when same element is put multiple times
        final var equalitAfter = writableByEquality.get(hash);
        assertThat(equalitAfter).isNotNull();

        final var countAfter = writableScheduledCounts.get(new TimestampSeconds(actual.calculatedExpirationSecond()));
        assertThat(countAfter).isNotNull();
        assertThat(countAfter.numberScheduled()).isEqualTo(1);
    }

    @NonNull
    static Schedule replaceSignatoriesAndMarkExecuted(
            @NonNull final Schedule schedule,
            @NonNull final Set<Key> newSignatories,
            @NonNull final Instant consensusTime) {
        final Timestamp consensusTimestamp = new Timestamp(consensusTime.getEpochSecond(), consensusTime.getNano());
        final Schedule.Builder builder = schedule.copyBuilder().executed(true).resolutionTime(consensusTimestamp);
        return builder.signatories(List.copyOf(newSignatories)).build();
    }
}
