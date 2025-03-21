// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.getscheduledinfo;

import static com.hedera.hapi.node.base.ResponseCodeEnum.*;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.LEDGER_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledNonFungibleTokenCreateCall;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GetScheduledNonFungibleTokenCreateCallTest extends CallTestBase {

    @Mock
    private Configuration configuration;

    @Mock
    private LedgerConfig ledgerConfig;

    private GetScheduledNonFungibleTokenCreateCall subject;
    private final ScheduleID scheduleId =
            ScheduleID.newBuilder().scheduleNum(1L).build();

    @BeforeEach
    void setUp() {
        subject =
                new GetScheduledNonFungibleTokenCreateCall(gasCalculator, mockEnhancement(), configuration, scheduleId);
    }

    @Test
    void returnsNotFoundForMissingSchedule() {
        given(nativeOperations.getSchedule(scheduleId)).willReturn(null);

        Call.PricedResult result = subject.execute(frame);

        assertEquals(RECORD_NOT_FOUND, result.responseCode());
    }

    @Test
    void returnsInvalidForNonTokenCreationSchedule() {
        var schedule = Schedule.newBuilder()
                .scheduledTransaction(SchedulableTransactionBody.newBuilder().freeze(FreezeTransactionBody.DEFAULT))
                .build();
        given(nativeOperations.getSchedule(scheduleId)).willReturn(schedule);

        Call.PricedResult result = subject.execute(frame);

        assertEquals(INVALID_SCHEDULE_ID, result.responseCode());
    }

    @Test
    void returnsInvalidForFungibleTokenSchedule() {
        var schedule = Schedule.newBuilder()
                .scheduledTransaction(SchedulableTransactionBody.newBuilder()
                        .tokenCreation(TokenCreateTransactionBody.newBuilder()
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .build())
                        .build())
                .build();
        given(nativeOperations.getSchedule(scheduleId)).willReturn(schedule);

        Call.PricedResult result = subject.execute(frame);

        assertEquals(INVALID_SCHEDULE_ID, result.responseCode());
    }

    @Test
    void returnsSuccessForValidNonFungibleTokenSchedule() {
        given(configuration.getConfigData(LedgerConfig.class)).willReturn(ledgerConfig);
        given(ledgerConfig.id()).willReturn(Bytes.wrap(LEDGER_ID));

        var schedule = Schedule.newBuilder()
                .scheduledTransaction(SchedulableTransactionBody.newBuilder()
                        .tokenCreation(TokenCreateTransactionBody.newBuilder()
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .expiry(Timestamp.DEFAULT)
                                .autoRenewPeriod(Duration.DEFAULT)
                                .build())
                        .build())
                .build();
        given(nativeOperations.getSchedule(scheduleId)).willReturn(schedule);

        Call.PricedResult result = subject.execute(frame);

        assertEquals(SUCCESS, result.responseCode());
    }
}
