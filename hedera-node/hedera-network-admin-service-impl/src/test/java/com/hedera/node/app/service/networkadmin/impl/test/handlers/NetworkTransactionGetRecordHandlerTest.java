// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_ID;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionGetRecordQuery;
import com.hedera.hapi.node.transaction.TransactionGetRecordResponse;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkTransactionGetRecordHandler;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fixtures.fees.FakeFeeCalculator;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class NetworkTransactionGetRecordHandlerTest extends NetworkAdminHandlerTestBase {
    @Mock
    private QueryContext context;

    private NetworkTransactionGetRecordHandler networkTransactionGetRecordHandler;

    @BeforeEach
    void setUp() {
        networkTransactionGetRecordHandler = new NetworkTransactionGetRecordHandler();
        final var configuration = HederaTestConfigBuilder.createConfig();
        lenient().when(context.configuration()).thenReturn(configuration);
    }

    @Test
    void extractsHeader() {
        final var query = createGetTransactionRecordQuery(transactionID, false, false);
        final var header = networkTransactionGetRecordHandler.extractHeader(query);
        final var op = query.transactionGetRecordOrThrow();
        assertThat(op.header()).isEqualTo(header);
    }

    @Test
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = networkTransactionGetRecordHandler.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .transactionGetRecord(TransactionGetRecordResponse.newBuilder().header(responseHeader))
                .build();
        assertThat(expectedResponse).isEqualTo(response);
    }

    @Test
    void requiresPayment() {
        assertThat(networkTransactionGetRecordHandler.requiresNodePayment(ResponseType.ANSWER_ONLY))
                .isTrue();
        assertThat(networkTransactionGetRecordHandler.requiresNodePayment(ResponseType.ANSWER_STATE_PROOF))
                .isTrue();
        assertThat(networkTransactionGetRecordHandler.requiresNodePayment(ResponseType.COST_ANSWER))
                .isFalse();
        assertThat(networkTransactionGetRecordHandler.requiresNodePayment(ResponseType.COST_ANSWER_STATE_PROOF))
                .isFalse();
    }

    @Test
    void needsAnswerOnlyCostForCostAnswer() {
        assertThat(networkTransactionGetRecordHandler.needsAnswerOnlyCost(ResponseType.ANSWER_ONLY))
                .isFalse();
        assertThat(networkTransactionGetRecordHandler.needsAnswerOnlyCost(ResponseType.ANSWER_STATE_PROOF))
                .isFalse();
        assertThat(networkTransactionGetRecordHandler.needsAnswerOnlyCost(ResponseType.COST_ANSWER))
                .isTrue();
        assertThat(networkTransactionGetRecordHandler.needsAnswerOnlyCost(ResponseType.COST_ANSWER_STATE_PROOF))
                .isFalse();
    }

    @Test
    void validatesQueryWhenValidRecord() {

        final var query = createGetTransactionRecordQuery(transactionID, false, false);
        given(context.query()).willReturn(query);

        assertThatCode(() -> networkTransactionGetRecordHandler.validate(context))
                .doesNotThrowAnyException();
    }

    @Test
    void validatesQueryWhenNoTransactionId() {

        final var query = createEmptysQuery();
        given(context.query()).willReturn(query);

        assertThrowsPreCheck(() -> networkTransactionGetRecordHandler.validate(context), INVALID_TRANSACTION_ID);
    }

    @Test
    void validatesQueryWhenNoAccountId() {
        final var query = createGetTransactionRecordQuery(transactionIDWithoutAccount(0, 0), false, false);
        given(context.query()).willReturn(query);

        assertThrowsPreCheck(() -> networkTransactionGetRecordHandler.validate(context), INVALID_ACCOUNT_ID);
    }

    @Test
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();

        final var query = createGetTransactionRecordQuery(transactionID, false, false);
        when(context.query()).thenReturn(query);

        final var response = networkTransactionGetRecordHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetRecordOrThrow();
        assertThat(op.header()).isNotNull();
        assertThat(op.header().nodeTransactionPrecheckCode()).isEqualTo(ResponseCodeEnum.FAIL_FEE);
        assertThat(op.transactionRecord()).isNull();
    }

    @Test
    void getsResponseIsEmptyWhenTransactionNotExist() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();

        final var query = createGetTransactionRecordQuery(transactionIDNotInCache, false, false);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);

        final var response = networkTransactionGetRecordHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetRecordOrThrow();
        assertThat(op.header()).isNotNull();
        assertThat(op.header().nodeTransactionPrecheckCode()).isEqualTo(ResponseCodeEnum.RECORD_NOT_FOUND);
        assertThat(op.transactionRecord()).isNull();
    }

    @Test
    void getsResponseIfOkResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedRecord = getExpectedRecord();

        final var query = createGetTransactionRecordQuery(transactionID, false, false);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);

        final var response = networkTransactionGetRecordHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetRecordOrThrow();
        assertThat(op.header()).isNotNull();
        assertThat(op.header().nodeTransactionPrecheckCode()).isEqualTo(ResponseCodeEnum.OK);
        assertThat(op.transactionRecord()).isEqualTo(expectedRecord);
    }

    @Test
    void getsResponseIfOkResponseWithDuplicates() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedRecord = getExpectedRecord();
        final List<TransactionRecord> expectedDuplicateRecords = getExpectedDuplicateList();

        final var query = createGetTransactionRecordQuery(transactionID, true, false);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);

        final var response = networkTransactionGetRecordHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetRecordOrThrow();
        assertThat(op.header()).isNotNull();
        assertThat(op.header().nodeTransactionPrecheckCode()).isEqualTo(ResponseCodeEnum.OK);
        assertThat(op.transactionRecord()).isEqualTo(expectedRecord);
        assertThat(op.duplicateTransactionRecords()).isEqualTo(expectedDuplicateRecords);
        assertThat(op.duplicateTransactionRecords().size()).isEqualTo(expectedDuplicateRecords.size());
    }

    @Test
    void getsResponseIfOkResponseWithChildrenRecord() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final List<TransactionRecord> expectedChildRecordList = List.of(recordOne, recordTwo, recordThree);

        final var txnId = otherRecord.transactionIDOrThrow();
        final var query = createGetTransactionRecordQuery(txnId, false, true);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);

        final var response = networkTransactionGetRecordHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetRecordOrThrow();
        assertThat(op.header()).isNotNull();
        assertThat(op.header().nodeTransactionPrecheckCode()).isEqualTo(ResponseCodeEnum.OK);
        assertThat(op.transactionRecord()).isEqualTo(otherRecord);
        assertThat(op.childTransactionRecords()).isEqualTo(expectedChildRecordList);
        assertThat(op.childTransactionRecords().size()).isEqualTo(expectedChildRecordList.size());
    }

    @Test
    @DisplayName("test computeFees When Free")
    void testComputeFees() {
        final var query = createGetTransactionRecordQuery(transactionID, false, false);
        given(context.query()).willReturn(query);
        given(context.recordCache()).willReturn(cache);
        given(context.feeCalculator()).willReturn(feeCalculator);
        feeCalculator.addNetworkRamByteSeconds(6);
        assertThatCode(() -> networkTransactionGetRecordHandler.computeFees(context))
                .doesNotThrowAnyException();
        verify(feeCalculator).addNetworkRamByteSeconds(6);
    }

    @Test
    @DisplayName("test computeFees with duplicates and children")
    void testComputeFeesWithDuplicatesAndChildRecords() {
        final var query = createGetTransactionRecordQuery(transactionID, true, true);
        given(context.query()).willReturn(query);
        given(context.recordCache()).willReturn(cache);
        FeeCalculator feeCalc = new FakeFeeCalculator();
        feeCalc.addBytesPerTransaction(1000L);
        feeCalc.addNetworkRamByteSeconds(6);
        given(context.feeCalculator()).willReturn(feeCalc);
        assertThatCode(() -> networkTransactionGetRecordHandler.computeFees(context))
                .doesNotThrowAnyException();
        var networkFee = networkTransactionGetRecordHandler.computeFees(context).networkFee();
        assertThat(networkFee).isZero();
    }

    private TransactionRecord getExpectedRecord() {
        return primaryRecord;
    }

    private List<TransactionRecord> getExpectedDuplicateList() {
        return List.of(duplicate1, duplicate2, duplicate3);
    }

    private List<TransactionRecord> getExpectedChildRecordList() {
        return List.of(recordOne, recordTwo, recordThree);
    }

    private Query createGetTransactionRecordQuery(
            final TransactionID transactionID, final boolean includeDuplicates, final boolean includeChildRecords) {
        final var data = TransactionGetRecordQuery.newBuilder()
                .transactionID(transactionID)
                .includeDuplicates(includeDuplicates)
                .includeChildRecords(includeChildRecords)
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().transactionGetRecord(data).build();
    }

    private Query createEmptysQuery() {
        final var data = TransactionGetRecordQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().transactionGetRecord(data).build();
    }
}
