// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.RECEIPT_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionGetReceiptQuery;
import com.hedera.hapi.node.transaction.TransactionGetReceiptResponse;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkTransactionGetReceiptHandler;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.app.state.recordcache.PartialRecordSource;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class NetworkTransactionGetReceiptHandlerTest extends NetworkAdminHandlerTestBase {
    @Mock
    private QueryContext context;

    private NetworkTransactionGetReceiptHandler networkTransactionGetReceiptHandler;

    @BeforeEach
    void setUp() {
        networkTransactionGetReceiptHandler = new NetworkTransactionGetReceiptHandler();
        final var configuration = HederaTestConfigBuilder.createConfig();
        lenient().when(context.configuration()).thenReturn(configuration);
    }

    @Test
    void extractsHeader() {
        final var query = createGetTransactionReceiptQuery(transactionID, false, false);
        final var header = networkTransactionGetReceiptHandler.extractHeader(query);
        final var op = query.transactionGetReceiptOrThrow();
        assertEquals(op.header(), header);
    }

    @Test
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = networkTransactionGetReceiptHandler.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .transactionGetReceipt(
                        TransactionGetReceiptResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    void validatesQueryWhenValidReceipt() {

        final var query = createGetTransactionReceiptQuery(transactionID, false, false);
        given(context.query()).willReturn(query);

        assertThatCode(() -> networkTransactionGetReceiptHandler.validate(context))
                .doesNotThrowAnyException();
    }

    @Test
    void validatesQueryWhenNoTransactionId() {

        final var query = createEmptysQuery();
        given(context.query()).willReturn(query);

        assertThrowsPreCheck(() -> networkTransactionGetReceiptHandler.validate(context), INVALID_TRANSACTION_ID);
    }

    @Test
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();

        final var query = createGetTransactionReceiptQuery(transactionID, false, false);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);

        final var response = networkTransactionGetReceiptHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetReceiptOrThrow();
        assertEquals(ResponseCodeEnum.FAIL_FEE, op.header().nodeTransactionPrecheckCode());
        assertNull(op.receipt());
    }

    @Test
    void usesParentTxnIdWhenGivenNonZeroNonce() {
        final var responseHeader =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();

        final var topLevelId = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(2L).build())
                .transactionValidStart(
                        Timestamp.newBuilder().seconds(1_234_567L).nanos(890).build())
                .build();
        final var otherChildTxnId = topLevelId.copyBuilder().nonce(1).build();
        final var targetChildTxnId = topLevelId.copyBuilder().nonce(2).build();
        final var query = createGetTransactionReceiptQuery(targetChildTxnId, false, false);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);
        cache.addRecordSource(
                0L,
                topLevelId,
                HederaRecordCache.DueDiligenceFailure.NO,
                new PartialRecordSource(List.of(
                        TransactionRecord.newBuilder()
                                .transactionID(topLevelId)
                                .receipt(TransactionReceipt.newBuilder()
                                        .status(CONTRACT_REVERT_EXECUTED)
                                        .build())
                                .build(),
                        TransactionRecord.newBuilder()
                                .transactionID(otherChildTxnId)
                                .receipt(TransactionReceipt.newBuilder()
                                        .status(REVERTED_SUCCESS)
                                        .build())
                                .build(),
                        TransactionRecord.newBuilder()
                                .transactionID(targetChildTxnId)
                                .receipt(TransactionReceipt.newBuilder()
                                        .status(INVALID_TOKEN_NFT_SERIAL_NUMBER)
                                        .build())
                                .build())));

        final var response = networkTransactionGetReceiptHandler.findResponse(context, responseHeader);
        final var answer = response.transactionGetReceiptOrThrow();
        assertEquals(OK, answer.headerOrThrow().nodeTransactionPrecheckCode());
        assertNotNull(answer.receipt());
        final var receipt = answer.receiptOrThrow();
        assertEquals(INVALID_TOKEN_NFT_SERIAL_NUMBER, receipt.status());
    }

    @Test
    void stillDetectsMissingChildRecord() {
        final var responseHeader =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();

        final var topLevelId = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(2L).build())
                .transactionValidStart(
                        Timestamp.newBuilder().seconds(1_234_567L).nanos(890).build())
                .build();
        final var otherChildTxnId = topLevelId.copyBuilder().nonce(1).build();
        final var targetChildTxnId = topLevelId.copyBuilder().nonce(2).build();
        final var missingChildTxnId = topLevelId.copyBuilder().nonce(3).build();
        final var query = createGetTransactionReceiptQuery(missingChildTxnId, false, false);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);
        cache.addRecordSource(
                0L,
                topLevelId,
                HederaRecordCache.DueDiligenceFailure.NO,
                new PartialRecordSource(List.of(
                        TransactionRecord.newBuilder()
                                .transactionID(topLevelId)
                                .receipt(TransactionReceipt.newBuilder()
                                        .status(CONTRACT_REVERT_EXECUTED)
                                        .build())
                                .build(),
                        TransactionRecord.newBuilder()
                                .transactionID(otherChildTxnId)
                                .receipt(TransactionReceipt.newBuilder()
                                        .status(REVERTED_SUCCESS)
                                        .build())
                                .build(),
                        TransactionRecord.newBuilder()
                                .transactionID(targetChildTxnId)
                                .receipt(TransactionReceipt.newBuilder()
                                        .status(INVALID_TOKEN_NFT_SERIAL_NUMBER)
                                        .build())
                                .build())));
        final var response = networkTransactionGetReceiptHandler.findResponse(context, responseHeader);
        final var answer = response.transactionGetReceiptOrThrow();
        assertEquals(RECEIPT_NOT_FOUND, answer.headerOrThrow().nodeTransactionPrecheckCode());
    }

    private SingleTransactionRecord singleRecordWith(final TransactionRecord transactionRecord) {
        return new SingleTransactionRecord(
                Transaction.DEFAULT,
                transactionRecord,
                List.of(),
                new SingleTransactionRecord.TransactionOutputs(null));
    }

    @Test
    void getsResponseIsEmptyWhenTransactionNotExist() {
        final var responseHeader =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();

        final var query = createGetTransactionReceiptQuery(transactionIDNotInCache, false, false);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);

        final var response = networkTransactionGetReceiptHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetReceiptOrThrow();
        assertEquals(ResponseCodeEnum.RECEIPT_NOT_FOUND, op.header().nodeTransactionPrecheckCode());
        assertNull(op.receipt());
    }

    @Test
    void getsResponseIfOkResponse() {
        final var responseHeader =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();
        final var expectedReceipt = getExpectedReceipt();

        final var query = createGetTransactionReceiptQuery(transactionID, false, false);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);

        final var response = networkTransactionGetReceiptHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetReceiptOrThrow();
        assertEquals(OK, op.header().nodeTransactionPrecheckCode());
        assertEquals(expectedReceipt, op.receipt());
    }

    @Test
    void getsResponseIfOkResponseWithDuplicates() {
        final var responseHeader =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();
        final var expectedReceipt = getExpectedReceipt();
        final List<TransactionReceipt> expectedDuplicateReceipt = getExpectedDuplicateList();

        final var query = createGetTransactionReceiptQuery(transactionID, true, false);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);

        final var response = networkTransactionGetReceiptHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetReceiptOrThrow();
        assertEquals(OK, op.header().nodeTransactionPrecheckCode());
        assertEquals(expectedReceipt, op.receipt());
        assertEquals(expectedDuplicateReceipt, op.duplicateTransactionReceipts());
        assertEquals(
                expectedDuplicateReceipt.size(),
                op.duplicateTransactionReceipts().size());
    }

    @Test
    void getsResponseIfOkResponseWithChildrenReceipt() {
        final var responseHeader =
                ResponseHeader.newBuilder().nodeTransactionPrecheckCode(OK).build();
        final List<TransactionReceipt> expectedChildReceiptList =
                List.of(recordOne.receiptOrThrow(), recordTwo.receiptOrThrow(), recordThree.receiptOrThrow());

        final var txnId =
                recordThree.transactionIDOrThrow().copyBuilder().nonce(0).build();
        final var query = createGetTransactionReceiptQuery(txnId, false, true);
        when(context.query()).thenReturn(query);
        when(context.recordCache()).thenReturn(cache);

        final var response = networkTransactionGetReceiptHandler.findResponse(context, responseHeader);
        final var op = response.transactionGetReceiptOrThrow();
        assertEquals(OK, op.header().nodeTransactionPrecheckCode());
        assertEquals(otherRecord.receiptOrThrow(), op.receipt());
        assertEquals(expectedChildReceiptList, op.childTransactionReceipts());
        assertEquals(
                expectedChildReceiptList.size(), op.childTransactionReceipts().size());
    }

    private TransactionReceipt getExpectedReceipt() {
        return primaryRecord.receipt();
    }

    private List<TransactionReceipt> getExpectedDuplicateList() {
        return List.of(duplicate1.receipt(), duplicate2.receipt(), duplicate3.receipt());
    }

    private Query createGetTransactionReceiptQuery(
            final TransactionID transactionID, final boolean includeDuplicates, final boolean includeChildReceipts) {
        final var data = TransactionGetReceiptQuery.newBuilder()
                .transactionID(transactionID)
                .includeDuplicates(includeDuplicates)
                .includeChildReceipts(includeChildReceipts)
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().transactionGetReceipt(data).build();
    }

    private Query createEmptysQuery() {
        final var data = TransactionGetReceiptQuery.newBuilder()
                .header(QueryHeader.newBuilder().build())
                .build();

        return Query.newBuilder().transactionGetReceipt(data).build();
    }
}
