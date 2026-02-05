// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionGetRecordQuery;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.fees.SimpleFeeContextImpl;
import com.hedera.node.app.service.networkadmin.impl.calculator.GetByKeyFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.GetVersionInfoFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.TransactionGetReceiptFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.TransactionGetRecordFeeCalculator;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.workflows.QueryContext;
import java.util.List;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NetworkAdminFeeCalculatorsTest {
    private static final long GET_VERSION_INFO_FEE = 100L;
    private static final long GET_BY_KEY_FEE = 200L;
    private static final long TRANSACTION_GET_RECEIPT_FEE = 0L;
    private static final long TRANSACTION_GET_RECORD_FEE = 300L;

    @Mock
    private RecordCache recordCache;

    @Mock
    private RecordCache.History recordHistory;

    @Test
    @DisplayName("GetVersionInfoFeeCalculator accumulates correct service fee")
    void testGetVersionInfoFeeCalculator() {
        final var calculator = new GetVersionInfoFeeCalculator();
        final var mockQueryContext = mock(QueryContext.class);
        final var feeResult = new FeeResult();

        calculator.accumulateNodePayment(
                Query.newBuilder().build(),
                new SimpleFeeContextImpl(null, mockQueryContext),
                feeResult,
                createTestFeeSchedule());

        assertThat(feeResult.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getNetworkTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(GET_VERSION_INFO_FEE);
        assertThat(calculator.getQueryType()).isEqualTo(Query.QueryOneOfType.NETWORK_GET_VERSION_INFO);
    }

    @Test
    @DisplayName("GetByKeyFeeCalculator accumulates correct service fee")
    void testGetByKeyFeeCalculator() {
        final var calculator = new GetByKeyFeeCalculator();
        final var mockQueryContext = mock(QueryContext.class);
        final var feeResult = new FeeResult();

        calculator.accumulateNodePayment(
                Query.newBuilder().build(),
                new SimpleFeeContextImpl(null, mockQueryContext),
                feeResult,
                createTestFeeSchedule());

        assertThat(feeResult.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getNetworkTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(GET_BY_KEY_FEE);
        assertThat(calculator.getQueryType()).isEqualTo(Query.QueryOneOfType.GET_BY_KEY);
    }

    @Test
    @DisplayName("TransactionGetReceiptFeeCalculator accumulates correct service fee (free query)")
    void testTransactionGetReceiptFeeCalculator() {
        final var calculator = new TransactionGetReceiptFeeCalculator();
        final var mockQueryContext = mock(QueryContext.class);
        final var feeResult = new FeeResult();

        calculator.accumulateNodePayment(
                Query.newBuilder().build(),
                new SimpleFeeContextImpl(null, mockQueryContext),
                feeResult,
                createTestFeeSchedule());

        assertThat(feeResult.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getNetworkTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(TRANSACTION_GET_RECEIPT_FEE);
        assertThat(calculator.getQueryType()).isEqualTo(Query.QueryOneOfType.TRANSACTION_GET_RECEIPT);
    }

    @Test
    @DisplayName("TransactionGetRecordFeeCalculator accumulates correct service fee")
    void testTransactionGetRecordFeeCalculator() {
        final var calculator = new TransactionGetRecordFeeCalculator();
        final var feeResult = new FeeResult();

        calculator.accumulateNodePayment(
                Query.newBuilder()
                        .transactionGetRecord(
                                TransactionGetRecordQuery.newBuilder().build())
                        .build(),
                new SimpleFeeContextImpl(null, null),
                feeResult,
                createTestFeeSchedule());

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(TRANSACTION_GET_RECORD_FEE);
    }

    @ParameterizedTest
    @CsvSource({"false,false,1", "true,false,3", "false,true,4", "true,true,6"})
    @DisplayName("TransactionGetRecordFeeCalculator accumulates correct service fee with children and duplicates")
    void testTransactionGetRecordFeeCalculatorWithChildrenAndDuplicates(
            boolean includeDuplicates, boolean includeChildRecords, int expectedMultiplier) {
        final var calculator = new TransactionGetRecordFeeCalculator();
        final var mockQueryContext = mock(QueryContext.class);
        final var transactionID = TransactionID.newBuilder().build();
        final var query = Query.newBuilder()
                .transactionGetRecord(TransactionGetRecordQuery.newBuilder()
                        .transactionID(transactionID)
                        .includeDuplicates(includeDuplicates)
                        .includeChildRecords(includeChildRecords)
                        .build())
                .build();
        when(mockQueryContext.query()).thenReturn(query);
        when(mockQueryContext.recordCache()).thenReturn(recordCache);
        if (includeDuplicates || includeChildRecords) {
            when(recordCache.getHistory(transactionID)).thenReturn(recordHistory);
        }
        if (includeDuplicates) {
            when(recordHistory.duplicateCount()).thenReturn(2);
        }
        if (includeChildRecords) {
            when(recordHistory.childRecords())
                    .thenReturn(List.of(
                            TransactionRecord.newBuilder().build(),
                            TransactionRecord.newBuilder().build(),
                            TransactionRecord.newBuilder().build()));
        }
        final var feeResult = new FeeResult();

        calculator.accumulateNodePayment(
                query, new SimpleFeeContextImpl(null, mockQueryContext), feeResult, createTestFeeSchedule());

        assertThat(feeResult.getServiceTotalTinycents())
                .isEqualTo(TRANSACTION_GET_RECORD_FEE + (TRANSACTION_GET_RECORD_FEE * (expectedMultiplier - 1)));
        assertThat(calculator.getQueryType()).isEqualTo(Query.QueryOneOfType.TRANSACTION_GET_RECORD);
    }

    @Test
    @DisplayName("TransactionGetRecordFeeCalculator accumulates correct service fee when no history is present")
    void testTransactionGetRecordFeeCalculatorWithNoHistory() {
        final var calculator = new TransactionGetRecordFeeCalculator();
        final var mockQueryContext = mock(QueryContext.class);
        final var transactionID = TransactionID.newBuilder().build();
        final var query = Query.newBuilder()
                .transactionGetRecord(TransactionGetRecordQuery.newBuilder()
                        .transactionID(transactionID)
                        .includeDuplicates(true)
                        .includeChildRecords(true)
                        .build())
                .build();
        when(mockQueryContext.query()).thenReturn(query);
        when(mockQueryContext.recordCache()).thenReturn(recordCache);
        when(recordCache.getHistory(transactionID)).thenReturn(null);
        final var feeResult = new FeeResult();

        calculator.accumulateNodePayment(
                query, new SimpleFeeContextImpl(null, mockQueryContext), feeResult, createTestFeeSchedule());

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(TRANSACTION_GET_RECORD_FEE);
        assertThat(calculator.getQueryType()).isEqualTo(Query.QueryOneOfType.TRANSACTION_GET_RECORD);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .services(makeService(
                        "NetworkService",
                        makeServiceFee(HederaFunctionality.GET_VERSION_INFO, GET_VERSION_INFO_FEE),
                        makeServiceFee(HederaFunctionality.GET_BY_KEY, GET_BY_KEY_FEE),
                        makeServiceFee(HederaFunctionality.TRANSACTION_GET_RECEIPT, TRANSACTION_GET_RECEIPT_FEE),
                        makeServiceFee(
                                HederaFunctionality.TRANSACTION_GET_RECORD,
                                TRANSACTION_GET_RECORD_FEE,
                                makeExtraIncluded(Extra.RECORDS, 1))))
                .extras(makeExtraDef(Extra.RECORDS, TRANSACTION_GET_RECORD_FEE))
                .build();
    }
}
