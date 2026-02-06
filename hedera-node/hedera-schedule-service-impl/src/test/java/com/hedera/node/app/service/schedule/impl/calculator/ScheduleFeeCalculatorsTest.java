// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.calculator;

import static com.hedera.hapi.util.HapiUtils.functionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.SimpleFeeContextImpl;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleFeeCalculatorsTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule,
                Set.of(
                        new ScheduleCreateFeeCalculator(),
                        new ScheduleSignFeeCalculator(),
                        new ScheduleDeleteFeeCalculator()),
                Set.of(new ScheduleGetInfoFeeCalculator()));
    }

    static Stream<TestCase> provideTestCases() {
        return Stream.of(
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                                        .scheduledTransactionBody(SchedulableTransactionBody.newBuilder()
                                                .build())
                                        .build())
                                .build(),
                        1,
                        100000L,
                        99000000L,
                        200000L),
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                                        .scheduledTransactionBody(SchedulableTransactionBody.newBuilder()
                                                .build())
                                        .adminKey(Key.newBuilder()
                                                .ed25519(Bytes.wrap(new byte[32]))
                                                .build())
                                        .build())
                                .build(),
                        2,
                        1100000L,
                        99000000L,
                        2200000L),
                // Schedule create contract call case
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                                        .scheduledTransactionBody(SchedulableTransactionBody.newBuilder()
                                                .contractCall(ContractCallTransactionBody.DEFAULT)
                                                .build())
                                        .build())
                                .build(),
                        1,
                        100000L,
                        99012345L,
                        200000L),
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                                        .scheduledTransactionBody(SchedulableTransactionBody.newBuilder()
                                                .build())
                                        .adminKey(Key.newBuilder()
                                                .ed25519(Bytes.wrap(new byte[32]))
                                                .build())
                                        .build())
                                .build(),
                        2,
                        1100000L,
                        99000000L,
                        2200000L),
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                                        .scheduledTransactionBody(SchedulableTransactionBody.newBuilder()
                                                .build())
                                        .adminKey(Key.newBuilder()
                                                .keyList(KeyList.newBuilder()
                                                        .keys(
                                                                Key.newBuilder()
                                                                        .ed25519(Bytes.wrap(new byte[32]))
                                                                        .build(),
                                                                Key.newBuilder()
                                                                        .ecdsaSecp256k1(Bytes.wrap(new byte[33]))
                                                                        .build())
                                                        .build())
                                                .build())
                                        .build())
                                .build(),
                        1,
                        100000L,
                        109000000L,
                        200000L),
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                                        .scheduledTransactionBody(SchedulableTransactionBody.newBuilder()
                                                .build())
                                        .adminKey(Key.newBuilder()
                                                .thresholdKey(ThresholdKey.newBuilder()
                                                        .threshold(2)
                                                        .keys(KeyList.newBuilder()
                                                                .keys(
                                                                        Key.newBuilder()
                                                                                .ed25519(Bytes.wrap(new byte[32]))
                                                                                .build(),
                                                                        Key.newBuilder()
                                                                                .ecdsaSecp256k1(
                                                                                        Bytes.wrap(new byte[33]))
                                                                                .build())
                                                                .build())
                                                        .build())
                                                .build())
                                        .build())
                                .build(),
                        3,
                        2100000L,
                        109000000L,
                        4200000L),
                // ScheduleSignFeeCalculator case
                new TestCase(
                        new ScheduleSignFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleSign(
                                        ScheduleSignTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        100000L,
                        9000000L,
                        200000L),
                // ScheduleDeleteFeeCalculator case
                new TestCase(
                        new ScheduleDeleteFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleDelete(ScheduleDeleteTransactionBody.newBuilder()
                                        .build())
                                .build(),
                        1,
                        100000L,
                        9000000L,
                        200000L));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Fee calculation for all ScheduleFeeCalculators")
    void testFeeCalculators(TestCase testCase) throws UnknownHederaFunctionality {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(testCase.numSignatures);
        when(feeContext.functionality()).thenReturn(functionOf(testCase.body()));

        final var result = feeCalculator.calculateTxFee(testCase.body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getNodeTotalTinycents()).isEqualTo(testCase.expectedNodeFee);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(testCase.expectedServiceFee);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(testCase.expectedNetworkFee);
    }

    @Test
    void testQueryFeeCalculator() {
        final var mockQueryContext = mock(QueryContext.class);
        final var query = Query.newBuilder().build();
        final var queryFeeCalculator = new ScheduleGetInfoFeeCalculator();
        final var feeResult = new FeeResult();

        queryFeeCalculator.accumulateNodePayment(
                query, new SimpleFeeContextImpl(null, mockQueryContext), feeResult, createTestFeeSchedule());

        assertThat(feeResult.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getNetworkTotalTinycents()).isEqualTo(0L);
        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(5L);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000L),
                        makeExtraDef(Extra.KEYS, 10000000L),
                        makeExtraDef(Extra.STATE_BYTES, 110L),
                        makeExtraDef(Extra.SCHEDULE_CREATE_CONTRACT_CALL_BASE, 12345L))
                .services(makeService(
                        "ScheduleService",
                        makeServiceFee(
                                HederaFunctionality.SCHEDULE_CREATE,
                                99000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.SCHEDULE_CREATE_CONTRACT_CALL_BASE, 0)),
                        makeServiceFee(HederaFunctionality.SCHEDULE_SIGN, 9000000),
                        makeServiceFee(HederaFunctionality.SCHEDULE_DELETE, 9000000),
                        makeServiceFee(HederaFunctionality.SCHEDULE_GET_INFO, 5)))
                .build();
    }

    record TestCase(
            ServiceFeeCalculator calculator,
            TransactionBody body,
            int numSignatures,
            long expectedNodeFee,
            long expectedServiceFee,
            long expectedNetworkFee) {

        @Override
        public @NonNull String toString() {
            return calculator.getClass().getSimpleName() + " with " + numSignatures + " signatures";
        }
    }
}
