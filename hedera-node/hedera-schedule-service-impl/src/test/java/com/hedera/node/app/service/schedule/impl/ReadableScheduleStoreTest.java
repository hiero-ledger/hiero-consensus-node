/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
