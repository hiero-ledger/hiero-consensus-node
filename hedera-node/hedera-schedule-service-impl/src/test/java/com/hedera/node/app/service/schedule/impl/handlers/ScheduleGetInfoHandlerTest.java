// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;

import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoQuery;
import com.hedera.hapi.node.scheduled.ScheduleGetInfoQuery;
import com.hedera.hapi.node.scheduled.ScheduleGetInfoQuery.Builder;
import com.hedera.hapi.node.scheduled.ScheduleGetInfoResponse;
import com.hedera.hapi.node.scheduled.ScheduleInfo;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.fees.usage.schedule.ScheduleOpsUsage;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.Assertions;
import com.hedera.node.app.spi.fixtures.fees.FakeFeeCalculator;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.InvalidKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

class ScheduleGetInfoHandlerTest extends ScheduleHandlerTestBase {
    @Mock
    protected QueryContext mockQueryContext;

    private ScheduleGetInfoHandler subject;

    @BeforeEach
    void setUp() throws PreCheckException, InvalidKeyException {
        setUpBase();
        subject = new ScheduleGetInfoHandler(new ScheduleOpsUsage());
    }

    @Test
    void verifyQueryValidation() {
        // setup the readable store
        given(mockQueryContext.createStore(ReadableScheduleStore.class)).willReturn(scheduleStore);
        // validate a couple schedules present in state
        given(mockQueryContext.query()).willReturn(createQuery(scheduleInState));
        assertThatNoException().isThrownBy(() -> subject.validate(mockQueryContext));
        given(mockQueryContext.query()).willReturn(createQuery(otherScheduleInState));
        assertThatNoException().isThrownBy(() -> subject.validate(mockQueryContext));

        // Now validate missing schedules
        for (final Schedule next : listOfScheduledOptions) {
            given(mockQueryContext.query()).willReturn(createQuery(next));
            Assertions.assertThrowsPreCheck(() -> subject.validate(mockQueryContext), INVALID_SCHEDULE_ID);
        }
        // verify that malformed queries throw appropriate precheck exceptions
        given(mockQueryContext.query()).willReturn(createBadQuery(scheduleInState, true, false));
        Assertions.assertThrowsPreCheck(() -> subject.validate(mockQueryContext), INVALID_SCHEDULE_ID);
        given(mockQueryContext.query()).willReturn(createBadQuery(scheduleInState, false, true));
        Assertions.assertThrowsPreCheck(() -> subject.validate(mockQueryContext), INVALID_TRANSACTION);
        given(mockQueryContext.query()).willReturn(createBadQuery(scheduleInState, true, true));
        Assertions.assertThrowsPreCheck(() -> subject.validate(mockQueryContext), INVALID_TRANSACTION);
        given(mockQueryContext.query()).willReturn(createWrongQuery());
        Assertions.assertThrowsPreCheck(() -> subject.validate(mockQueryContext), INVALID_TRANSACTION);
    }

    @Test
    void verifyQueryResponse() {
        given(mockQueryContext.configuration()).willReturn(testConfig);
        // setup the readable store
        given(mockQueryContext.createStore(ReadableScheduleStore.class)).willReturn(scheduleStore);
        final ResponseHeader.Builder testHeaderBuilder = ResponseHeader.newBuilder();
        testHeaderBuilder.nodeTransactionPrecheckCode(ResponseCodeEnum.OK);
        testHeaderBuilder.responseType(ResponseType.ANSWER_ONLY);

        // validate a couple schedules present in state
        given(mockQueryContext.query()).willReturn(createQuery(scheduleInState));
        Response testResult = subject.findResponse(mockQueryContext, testHeaderBuilder.build());
        ScheduleInfo actual = validateResponseAndExtractInfo(testResult, testHeaderBuilder.build());
        validateScheduleInfo(actual, scheduleInState);

        given(mockQueryContext.query()).willReturn(createQuery(otherScheduleInState));
        testResult = subject.findResponse(mockQueryContext, testHeaderBuilder.build());
        actual = validateResponseAndExtractInfo(testResult, testHeaderBuilder.build());
        validateScheduleInfo(actual, otherScheduleInState);

        // validate schedules not in state
        for (final Schedule next : listOfScheduledOptions) {
            given(mockQueryContext.query()).willReturn(createQuery(next));
            testResult = subject.findResponse(mockQueryContext, testHeaderBuilder.build());
            actual = validateFailedResponseAndExtractInfo(testResult, testHeaderBuilder.build(), INVALID_SCHEDULE_ID);
            assertThat(actual).isNull();
        }
    }

    @Test
    void verifyFeeComputation() {
        given(mockQueryContext.configuration()).willReturn(testConfig);
        // setup the readable store
        given(mockQueryContext.createStore(ReadableScheduleStore.class)).willReturn(scheduleStore);
        final ResponseHeader.Builder testHeaderBuilder = ResponseHeader.newBuilder();
        testHeaderBuilder.nodeTransactionPrecheckCode(ResponseCodeEnum.OK);
        testHeaderBuilder.responseType(ResponseType.COST_ANSWER);
        // This always generates {0,0,0} fees, but we can observe calls...
        // It would be helpful to have a test calculator that does the accumulation to test the values
        // produced, but this will have to do for now.
        final FeeCalculator feeSpy = Mockito.spy(new FakeFeeCalculator());
        given(mockQueryContext.feeCalculator()).willReturn(feeSpy);

        // validate a schedule that is present in state
        given(mockQueryContext.query()).willReturn(createQuery(scheduleInState));
        Fees actual = subject.computeFees(mockQueryContext);
        assertThat(actual.networkFee()).isEqualTo(0L);
        assertThat(actual.nodeFee()).isEqualTo(0L);
        assertThat(actual.serviceFee()).isEqualTo(0L);
        assertThat(actual.totalFee()).isEqualTo(0L);
        verify(feeSpy).legacyCalculate(any());
    }

    @NonNull
    private static ScheduleInfo validateResponseAndExtractInfo(
            final Response testResult, final ResponseHeader expectedHeader) {
        assertThat(testResult.hasScheduleGetInfo()).isTrue();
        assertThat(testResult.scheduleGetInfo()).isNotNull();
        final ScheduleGetInfoResponse innerResult = testResult.scheduleGetInfo();
        assertThat(innerResult).isNotNull();
        assertThat(innerResult.hasHeader()).isTrue();
        assertThat(innerResult.header()).isEqualTo(expectedHeader);
        assertThat(innerResult.hasScheduleInfo()).isTrue();
        return innerResult.scheduleInfo();
    }

    @NonNull
    private static ScheduleInfo validateFailedResponseAndExtractInfo(
            final Response testResult, final ResponseHeader expectedHeader, final ResponseCodeEnum failureCode) {
        assertThat(testResult.hasScheduleGetInfo()).isTrue();
        assertThat(testResult.scheduleGetInfo()).isNotNull();
        final ScheduleGetInfoResponse innerResult = testResult.scheduleGetInfo();
        assertThat(innerResult).isNotNull();
        assertThat(innerResult.hasHeader()).isTrue();
        final ResponseHeader resultHeader = innerResult.header();
        assertThat(resultHeader).isNotEqualTo(expectedHeader);
        final ResponseCodeEnum responseCode = resultHeader.nodeTransactionPrecheckCode();
        assertThat(responseCode).isNotEqualTo(OK).isEqualTo(failureCode);
        assertThat(innerResult.hasScheduleInfo()).isFalse();
        return innerResult.scheduleInfo();
    }

    private void validateScheduleInfo(final ScheduleInfo actual, final Schedule expected) {
        final var keyBuilder = KeyList.newBuilder().keys(expected.signatories());
        final KeyList expectSigners = keyBuilder.build();
        final LedgerConfig ledgerConfig = testConfig.getConfigData(LedgerConfig.class);
        final Bytes expectedLedgerId = ledgerConfig.id();
        assertThat(actual).isNotNull();
        assertThat(actual.scheduleID()).isEqualTo(expected.scheduleId());
        assertThat(actual.waitForExpiry()).isEqualTo(expected.waitForExpiry());
        assertThat(actual.adminKey()).isEqualTo(expected.adminKey());
        assertThat(actual.creatorAccountID()).isEqualTo(expected.schedulerAccountId());
        assertThat(actual.payerAccountID()).isEqualTo(expected.payerAccountId());
        assertThat(actual.expirationTime()).isEqualTo(timestampFrom(expected.calculatedExpirationSecond()));
        assertThat(actual.scheduledTransactionID()).isEqualTo(HandlerUtility.transactionIdForScheduled(expected));
        assertThat(actual.scheduledTransactionBody()).isEqualTo(expected.scheduledTransaction());
        assertThat(actual.memo()).isEqualTo(expected.memo());
        assertThat(actual.signers()).isEqualTo(expectSigners);
        if (expected.executed()) assertThat(actual.executionTime()).isEqualTo(expected.resolutionTime());
        if (expected.deleted()) assertThat(actual.deletionTime()).isEqualTo(expected.resolutionTime());
        assertThat(actual.ledgerId()).isNotNull().isEqualTo(expectedLedgerId);
    }

    private Query createWrongQuery() {
        final Query.Builder queryBuilder = Query.newBuilder();
        final ConsensusGetTopicInfoQuery.Builder builder = ConsensusGetTopicInfoQuery.newBuilder();
        final QueryHeader.Builder headBuilder = QueryHeader.newBuilder();
        headBuilder.responseType(ResponseType.ANSWER_STATE_PROOF);
        builder.header(headBuilder);
        builder.topicID(TopicID.DEFAULT);
        queryBuilder.consensusGetTopicInfo(builder);
        return queryBuilder.build();
    }

    private Query createBadQuery(final Schedule scheduleInState, final boolean noId, final boolean noHeader) {
        final Query original = createQuery(scheduleInState);
        final Builder modify = original.scheduleGetInfo().copyBuilder();
        if (noId) modify.scheduleID((ScheduleID) null);
        if (noHeader) modify.header((QueryHeader) null);
        return Query.newBuilder().scheduleGetInfo(modify).build();
    }

    private Query createQuery(final Schedule scheduleToFind) {
        final Query.Builder queryBuilder = Query.newBuilder();
        final Builder builder = ScheduleGetInfoQuery.newBuilder();
        final QueryHeader.Builder headBuilder = QueryHeader.newBuilder();
        headBuilder.responseType(ResponseType.ANSWER_STATE_PROOF);
        builder.header(headBuilder);
        builder.scheduleID(scheduleToFind.scheduleId());
        queryBuilder.scheduleGetInfo(builder);
        return queryBuilder.build();
    }
}
