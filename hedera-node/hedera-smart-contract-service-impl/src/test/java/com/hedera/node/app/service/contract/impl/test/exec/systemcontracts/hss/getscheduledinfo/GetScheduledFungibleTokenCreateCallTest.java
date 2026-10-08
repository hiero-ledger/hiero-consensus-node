// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hss.getscheduledinfo;

import static com.hedera.hapi.node.base.ResponseCodeEnum.*;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.TokenTupleUtils.fungibleTokenInfoTupleFor;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.LEDGER_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledFungibleTokenCreateCall;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GetScheduledFungibleTokenCreateCallTest extends CallTestBase {

    private static final long VIEW_GAS = 123L;

    @Mock
    private Configuration configuration;

    private GetScheduledFungibleTokenCreateCall subject;
    private final ScheduleID scheduleId =
            ScheduleID.newBuilder().scheduleNum(1L).build();

    @BeforeEach
    void setUp() {
        subject = new GetScheduledFungibleTokenCreateCall(gasCalculator, mockEnhancement(), configuration, scheduleId);
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

        final var result = subject.execute(frame);

        assertEquals(INVALID_SCHEDULE_ID, result.responseCode());
    }

    @Test
    void returnsInvalidForNonFungibleTokenSchedule() {
        var schedule = Schedule.newBuilder()
                .scheduledTransaction(SchedulableTransactionBody.newBuilder()
                        .tokenCreation(TokenCreateTransactionBody.newBuilder()
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .build())
                        .build())
                .build();
        given(nativeOperations.getSchedule(scheduleId)).willReturn(schedule);

        Call.PricedResult result = subject.execute(frame);

        assertEquals(INVALID_SCHEDULE_ID, result.responseCode());
    }

    @Test
    void returnsSuccessForValidFungibleTokenSchedule() {
        given(nativeOperations.ledgerId()).willReturn(Bytes.wrap(LEDGER_ID));

        var schedule = Schedule.newBuilder()
                .scheduledTransaction(SchedulableTransactionBody.newBuilder()
                        .tokenCreation(TokenCreateTransactionBody.newBuilder()
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .expiry(Timestamp.DEFAULT)
                                .autoRenewPeriod(Duration.DEFAULT)
                                .build())
                        .build())
                .build();
        given(nativeOperations.getSchedule(scheduleId)).willReturn(schedule);

        Call.PricedResult result = subject.execute(frame);

        assertEquals(SUCCESS, result.responseCode());
    }

    @Test
    void returnsEmptyInfoWithExecutedStatusForExecutedSchedule() {
        assertEmptyInfoFor(fungibleCreateSchedule(true, false), SCHEDULE_ALREADY_EXECUTED);
    }

    @Test
    void returnsEmptyInfoWithDeletedStatusForDeletedSchedule() {
        assertEmptyInfoFor(fungibleCreateSchedule(false, true), SCHEDULE_ALREADY_DELETED);
    }

    @Test
    void reportsExecutedStatusBeforeValidatingScheduleType() {
        final var schedule = Schedule.newBuilder()
                .executed(true)
                .scheduledTransaction(SchedulableTransactionBody.newBuilder().freeze(FreezeTransactionBody.DEFAULT))
                .build();
        assertEmptyInfoFor(schedule, SCHEDULE_ALREADY_EXECUTED);
    }

    @Test
    void reportsDeletedStatusBeforeValidatingScheduleType() {
        final var schedule = Schedule.newBuilder()
                .deleted(true)
                .scheduledTransaction(SchedulableTransactionBody.newBuilder().freeze(FreezeTransactionBody.DEFAULT))
                .build();
        assertEmptyInfoFor(schedule, SCHEDULE_ALREADY_DELETED);
    }

    // Non-default token fields, so the scheduled token info would differ from the empty info
    private static Schedule fungibleCreateSchedule(final boolean executed, final boolean deleted) {
        return Schedule.newBuilder()
                .executed(executed)
                .deleted(deleted)
                .scheduledTransaction(SchedulableTransactionBody.newBuilder()
                        .tokenCreation(TokenCreateTransactionBody.newBuilder()
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .name("scheduled")
                                .symbol("SCH")
                                .treasury(
                                        AccountID.newBuilder().accountNum(1234L).build())
                                .initialSupply(500L)
                                .expiry(Timestamp.DEFAULT)
                                .autoRenewPeriod(Duration.DEFAULT)
                                .build())
                        .build())
                .build();
    }

    private void assertEmptyInfoFor(final Schedule schedule, final ResponseCodeEnum expectedStatus) {
        given(nativeOperations.getSchedule(scheduleId)).willReturn(schedule);
        given(nativeOperations.ledgerId()).willReturn(Bytes.wrap(LEDGER_ID));
        given(gasCalculator.viewGasRequirement()).willReturn(VIEW_GAS);

        final var result = subject.execute(frame);

        assertEquals(expectedStatus, result.responseCode());
        assertTrue(result.isViewCall());
        assertEquals(VIEW_GAS, result.fullResult().gasRequirement());
        assertEquals(
                MessageFrame.State.COMPLETED_SUCCESS,
                result.fullResult().result().state());
        final var ledgerId = org.apache.tuweni.bytes.Bytes.wrap(
                        Bytes.wrap(LEDGER_ID).toByteArray())
                .toString();
        assertEquals(
                org.apache.tuweni.bytes.Bytes.wrap(GET_SCHEDULED_CREATE_FUNGIBLE_TOKEN_INFO
                        .getOutputs()
                        .encode(Tuple.of(
                                expectedStatus.protoOrdinal(), fungibleTokenInfoTupleFor(Token.DEFAULT, ledgerId, 1)))
                        .array()),
                result.fullResult().output());
    }
}
