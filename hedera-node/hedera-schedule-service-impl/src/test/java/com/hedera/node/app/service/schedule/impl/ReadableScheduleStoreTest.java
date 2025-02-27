// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.node.app.spi.workflows.PreCheckException;
import java.security.InvalidKeyException;
import org.assertj.core.api.BDDAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableScheduleStoreTest extends ScheduleTestBase {

    @BeforeEach
    void setUp() throws PreCheckException, InvalidKeyException {
        setUpBase();
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void constructorThrowsIfStatesIsNull() {
        BDDAssertions.assertThatThrownBy(() -> new ReadableScheduleStoreImpl(null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getNullReturnsNull() {
        assertThat(scheduleStore.get(null)).isNull();
    }

    @Test
    void getsExpectedSize() {
        assertThat(scheduleStore.numSchedulesInState()).isEqualTo(2);
    }

    @Test
    void returnsEmptyIfMissingSchedule() {
        final long missingId = testScheduleID.scheduleNum() + 15_000L;
        final ScheduleID missing =
                testScheduleID.copyBuilder().scheduleNum(missingId).build();
        assertThat(scheduleStore.get(missing)).isNull();
    }

    @Test
    void getsScheduleMetaFromFetchedSchedule() {
        final Schedule readSchedule = scheduleStore.get(testScheduleID);
        assertThat(readSchedule).isNotNull();
        assertThat(readSchedule.payerAccountId()).isEqualTo(payer);
        assertThat(readSchedule.adminKey()).isEqualTo(adminKey);
        assertThat(readSchedule.scheduledTransaction()).isEqualTo(scheduled);
    }

    @Test
    void getsScheduleMetaFromFetchedScheduleNoExplicitPayer() {
        final Schedule modified =
                scheduleInState.copyBuilder().payerAccountId(nullAccount).build();
        writableSchedules.put(modified);
        final Schedule readSchedule = scheduleStore.get(testScheduleID);
        assertThat(readSchedule).isNotNull();
        assertThat(readSchedule.payerAccountId()).isNull();
        assertThat(readSchedule.adminKey()).isEqualTo(adminKey);
        assertThat(readSchedule.scheduledTransaction()).isEqualTo(scheduled);
    }
}
