// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleFeeCalculatorsTest {

    @Mock
    private CalculatorState calculatorState;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule,
                Set.of(
                        new ScheduleCreateFeeCalculator(),
                        new ScheduleSignFeeCalculator(),
                        new ScheduleDeleteFeeCalculator()));
    }

    static Stream<TestCase> provideTestCases() {
        return Stream.of(
                // ScheduleCreateFeeCalculator cases
                // No admin key: keys = 0, service fee = 1000000L
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                                        .build())
                                .build(),
                        1,
                        100000L,
                        1000000L,
                        200000L),
                // Simple ED25519 admin key: keys = 1, service fee = 1000000L + 100000000L = 101000000L
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                                        .adminKey(Key.newBuilder()
                                                .ed25519(Bytes.wrap(new byte[32]))
                                                .build())
                                        .build())
                                .build(),
                        2,
                        100000L,
                        101000000L,
                        200000L),
                // KeyList admin key: keys = 2, service fee = 1000000L + 2*100000000L = 201000000L
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
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
                        201000000L,
                        200000L),
                // ThresholdKey admin key: keys = 2, service fee = 1000000L + 2*100000000L = 201000000L
                new TestCase(
                        new ScheduleCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleCreate(ScheduleCreateTransactionBody.newBuilder()
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
                        100000L,
                        201000000L,
                        200000L),
                // ScheduleSignFeeCalculator case
                new TestCase(
                        new ScheduleSignFeeCalculator(),
                        TransactionBody.newBuilder()
                                .scheduleSign(
                                        ScheduleSignTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        100000L,
                        100000L,
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
                        100000L,
                        200000L));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Fee calculation for all ScheduleFeeCalculators")
    void testFeeCalculators(TestCase testCase) {
        lenient().when(calculatorState.numTxnSignatures()).thenReturn(testCase.numSignatures);

        final var result = feeCalculator.calculateTxFee(testCase.body, calculatorState);

        assertThat(result).isNotNull();
        assertThat(result.node).isEqualTo(testCase.expectedNodeFee);
        assertThat(result.service).isEqualTo(testCase.expectedServiceFee);
        assertThat(result.network).isEqualTo(testCase.expectedNetworkFee);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 10)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000L),
                        makeExtraDef(Extra.KEYS, 100000000L),
                        makeExtraDef(Extra.BYTES, 110L))
                .services(makeService(
                        "ScheduleService",
                        makeServiceFee(
                                HederaFunctionality.SCHEDULE_CREATE, 1000000L, makeExtraIncluded(Extra.SIGNATURES, 1)),
                        makeServiceFee(
                                HederaFunctionality.SCHEDULE_SIGN, 100000L, makeExtraIncluded(Extra.SIGNATURES, 1)),
                        makeServiceFee(
                                HederaFunctionality.SCHEDULE_DELETE, 100000L, makeExtraIncluded(Extra.SIGNATURES, 1))))
                .build();
    }

    static class TestCase {
        final ServiceFeeCalculator calculator;
        final TransactionBody body;
        final int numSignatures;
        final long expectedNodeFee;
        final long expectedServiceFee;
        final long expectedNetworkFee;

        TestCase(
                ServiceFeeCalculator calculator,
                TransactionBody body,
                int numSignatures,
                long expectedNodeFee,
                long expectedServiceFee,
                long expectedNetworkFee) {
            this.calculator = calculator;
            this.body = body;
            this.numSignatures = numSignatures;
            this.expectedNodeFee = expectedNodeFee;
            this.expectedServiceFee = expectedServiceFee;
            this.expectedNetworkFee = expectedNetworkFee;
        }

        @Override
        public String toString() {
            return calculator.getClass().getSimpleName() + " with " + numSignatures + " signatures";
        }
    }
}
