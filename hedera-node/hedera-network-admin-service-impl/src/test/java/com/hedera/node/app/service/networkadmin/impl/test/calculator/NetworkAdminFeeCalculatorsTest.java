// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.networkadmin.impl.calculator.GetByKeyFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.GetVersionInfoFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.TransactionGetReceiptFeeCalculator;
import com.hedera.node.app.service.networkadmin.impl.calculator.TransactionGetRecordFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContextUtil;
import com.hedera.node.app.spi.workflows.QueryContext;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NetworkAdminFeeCalculatorsTest {

    private static final long GET_VERSION_INFO_FEE = 100L;
    private static final long GET_BY_KEY_FEE = 200L;
    private static final long TRANSACTION_GET_RECEIPT_FEE = 0L;
    private static final long TRANSACTION_GET_RECORD_FEE = 300L;

    @Test
    @DisplayName("GetVersionInfoFeeCalculator accumulates correct service fee")
    void testGetVersionInfoFeeCalculator() {
        final var calculator = new GetVersionInfoFeeCalculator();
        final var mockQueryContext = mock(QueryContext.class);
        final var feeResult = new FeeResult();

        calculator.accumulateNodePayment(
                Query.newBuilder().build(),
                SimpleFeeContextUtil.fromQueryContext(mockQueryContext),
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
                SimpleFeeContextUtil.fromQueryContext(mockQueryContext),
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
                SimpleFeeContextUtil.fromQueryContext(mockQueryContext),
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
        final var mockQueryContext = mock(QueryContext.class);
        final var feeResult = new FeeResult();

        calculator.accumulateNodePayment(
                Query.newBuilder().build(),
                SimpleFeeContextUtil.fromQueryContext(mockQueryContext),
                feeResult,
                createTestFeeSchedule());

        assertThat(feeResult.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getNetworkTotalTinycents()).isEqualTo(0L);
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
                        makeServiceFee(HederaFunctionality.TRANSACTION_GET_RECORD, TRANSACTION_GET_RECORD_FEE)))
                .build();
    }
}
