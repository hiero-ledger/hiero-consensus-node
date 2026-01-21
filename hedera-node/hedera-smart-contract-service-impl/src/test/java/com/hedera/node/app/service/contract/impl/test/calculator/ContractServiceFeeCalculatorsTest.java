// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.contract.ContractUpdateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.calculator.ContractCallFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractCreateFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractDeleteFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractUpdateFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.EthereumFeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
class ContractServiceFeeCalculatorsTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule,
                Set.of(
                        new ContractCreateFeeCalculator(),
                        new ContractUpdateFeeCalculator(),
                        new ContractDeleteFeeCalculator(),
                        new ContractCallFeeCalculator(),
                        new EthereumFeeCalculator()));
    }

    static Stream<TestCase> provideTestCases() {
        return Stream.of(
                new TestCase(
                        new ContractCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .contractCreateInstance(ContractCreateTransactionBody.newBuilder()
                                        .build())
                                .build(),
                        1,
                        100000L,
                        499000000L,
                        200000L),
                new TestCase(
                        new ContractDeleteFeeCalculator(),
                        TransactionBody.newBuilder()
                                .contractDeleteInstance(ContractDeleteTransactionBody.newBuilder()
                                        .build())
                                .build(),
                        2,
                        1100000L,
                        69000000L,
                        2200000L),
                new TestCase(
                        new ContractCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .contractCreateInstance(ContractCreateTransactionBody.newBuilder()
                                        .adminKey(Key.newBuilder()
                                                .ed25519(Bytes.wrap(new byte[32]))
                                                .build())
                                        .build())
                                .build(),
                        1,
                        100000L,
                        509000000L,
                        200000L),
                new TestCase(
                        new ContractUpdateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .contractUpdateInstance(ContractUpdateTransactionBody.newBuilder()
                                        .adminKey(Key.newBuilder()
                                                .ed25519(Bytes.wrap(new byte[32]))
                                                .build())
                                        .build())
                                .build(),
                        3,
                        2100000L,
                        509000000L,
                        4200000L),
                new TestCase(
                        new ContractCallFeeCalculator(),
                        TransactionBody.newBuilder()
                                .contractCall(
                                        ContractCallTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        0L,
                        0L,
                        0L),
                new TestCase(
                        new EthereumFeeCalculator(),
                        TransactionBody.newBuilder()
                                .ethereumTransaction(
                                        EthereumTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        100000L,
                        0L,
                        200000L));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Fee calculation for all ContractFeeCalculators")
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
                        "ContractService",
                        makeServiceFee(
                                HederaFunctionality.CONTRACT_CREATE,
                                499000000,
                                makeExtraIncluded(Extra.KEYS, 0),
                                makeExtraIncluded(Extra.BYTES, 1000)),
                        makeServiceFee(HederaFunctionality.CONTRACT_CALL, 0),
                        makeServiceFee(
                                HederaFunctionality.CONTRACT_UPDATE,
                                499000000,
                                makeExtraIncluded(Extra.KEYS, 0),
                                makeExtraIncluded(Extra.BYTES, 1000)),
                        makeServiceFee(HederaFunctionality.CONTRACT_DELETE, 69000000),
                        makeServiceFee(HederaFunctionality.ETHEREUM_TRANSACTION, 0)))
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
