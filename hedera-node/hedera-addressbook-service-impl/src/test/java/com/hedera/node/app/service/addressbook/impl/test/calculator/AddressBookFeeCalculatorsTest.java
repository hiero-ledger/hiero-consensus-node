// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.addressbook.impl.calculator.NodeCreateFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.calculator.NodeDeleteFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.calculator.NodeUpdateFeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddressBookFeeCalculatorsTest {
    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule,
                Set.of(new NodeCreateFeeCalculator(), new NodeUpdateFeeCalculator(), new NodeDeleteFeeCalculator()));
    }

    static Stream<TestCase> provideTestCases() {
        return Stream.of(
                new TestCase(
                        new NodeCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .nodeCreate(com.hedera.hapi.node.addressbook.NodeCreateTransactionBody.newBuilder()
                                        .build())
                                .build(),
                        1,
                        100000L,
                        123000000L,
                        200000L),
                new TestCase(
                        new NodeUpdateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .nodeUpdate(com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody.newBuilder()
                                        .build())
                                .build(),
                        2,
                        1100000L,
                        234000000L,
                        2200000L),
                new TestCase(
                        new NodeDeleteFeeCalculator(),
                        TransactionBody.newBuilder()
                                .nodeDelete(com.hedera.hapi.node.addressbook.NodeDeleteTransactionBody.newBuilder()
                                        .build())
                                .build(),
                        1,
                        100000L,
                        345000000L,
                        200000L));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Fee calculation for all Node*FeeCalculators")
    void testFeeCalculators(TestCase testCase) {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(testCase.numSignatures);
        final var result = feeCalculator.calculateTxFee(testCase.body, feeContext);
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
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000),
                        makeExtraDef(Extra.KEYS, 10000000),
                        makeExtraDef(Extra.BYTES, 110000))
                .services(makeService(
                        "AddressBookService",
                        makeServiceFee(
                                HederaFunctionality.NODE_CREATE,
                                123000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 1000)),
                        makeServiceFee(
                                HederaFunctionality.NODE_UPDATE,
                                234000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 1000)),
                        makeServiceFee(HederaFunctionality.NODE_DELETE, 345000000)))
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
