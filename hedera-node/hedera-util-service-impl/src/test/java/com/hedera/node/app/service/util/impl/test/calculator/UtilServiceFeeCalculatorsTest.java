// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.test.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.util.AtomicBatchTransactionBody;
import com.hedera.hapi.node.util.UtilPrngTransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.service.util.impl.calculator.AtomicBatchFeeCalculator;
import com.hedera.node.app.service.util.impl.calculator.UtilPrngFeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContextUtil;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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
class UtilServiceFeeCalculatorsTest {

    private static final long ATOMIC_BATCH_BASE_FEE = 500000000L;
    private static final long UTIL_PRNG_BASE_FEE = 1000000L;

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule, Set.of(new AtomicBatchFeeCalculator(), new UtilPrngFeeCalculator()), Set.of());
    }

    static Stream<TestCase> provideTestCases() {
        return Stream.of(
                new TestCase(
                        new AtomicBatchFeeCalculator(),
                        TransactionBody.newBuilder()
                                .atomicBatch(
                                        AtomicBatchTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        100000L,
                        ATOMIC_BATCH_BASE_FEE,
                        200000L),
                new TestCase(
                        new UtilPrngFeeCalculator(),
                        TransactionBody.newBuilder()
                                .utilPrng(UtilPrngTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        100000L,
                        UTIL_PRNG_BASE_FEE,
                        200000L));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Fee calculation for util service calculators")
    void testFeeCalculators(TestCase testCase) {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(testCase.numSignatures);

        final var result = feeCalculator.calculateTxFee(testCase.body, SimpleFeeContextUtil.fromFeeContext(feeContext));

        assertThat(result).isNotNull();
        assertThat(result.getNodeTotalTinycents()).isEqualTo(testCase.expectedNodeFee);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(testCase.expectedServiceFee);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(testCase.expectedNetworkFee);
    }

    @Test
    @DisplayName("AtomicBatchFeeCalculator returns correct transaction type")
    void atomicBatchFeeCalculatorReturnsCorrectType() {
        final var calculator = new AtomicBatchFeeCalculator();
        assertThat(calculator.getTransactionType()).isEqualTo(TransactionBody.DataOneOfType.ATOMIC_BATCH);
    }

    @Test
    @DisplayName("UtilPrngFeeCalculator returns correct transaction type")
    void utilPrngFeeCalculatorReturnsCorrectType() {
        final var calculator = new UtilPrngFeeCalculator();
        assertThat(calculator.getTransactionType()).isEqualTo(TransactionBody.DataOneOfType.UTIL_PRNG);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(makeExtraDef(Extra.SIGNATURES, 1000000))
                .services(makeService(
                        "UtilService",
                        makeServiceFee(HederaFunctionality.ATOMIC_BATCH, ATOMIC_BATCH_BASE_FEE),
                        makeServiceFee(HederaFunctionality.UTIL_PRNG, UTIL_PRNG_BASE_FEE)))
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
