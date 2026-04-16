// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.calculator;

import static com.hedera.hapi.util.HapiUtils.functionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.addressbook.NodeCreateTransactionBody;
import com.hedera.hapi.node.addressbook.NodeDeleteTransactionBody;
import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.addressbook.RegisteredNodeCreateTransactionBody;
import com.hedera.hapi.node.addressbook.RegisteredNodeDeleteTransactionBody;
import com.hedera.hapi.node.addressbook.RegisteredNodeUpdateTransactionBody;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.context.SimpleFeeContextImpl;
import com.hedera.node.app.service.addressbook.impl.calculator.NodeCreateFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.calculator.NodeDeleteFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.calculator.NodeUpdateFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.calculator.RegisteredNodeCreateFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.calculator.RegisteredNodeDeleteFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.calculator.RegisteredNodeUpdateFeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddressBookFeeCalculatorsTest {
    // describe expected fees
    private static final long NODE_CREATE_NODE_FEE = 100_000L;
    private static final long NODE_CREATE_NETWORK_FEE = 123_000_000L;
    private static final long NODE_CREATE_SERVICE_FEE = 200_000L;

    private static final long NODE_UPDATE_NODE_FEE = 1_100_000L;
    private static final long NODE_UPDATE_NETWORK_FEE = 234_000_000L;
    private static final long NODE_UPDATE_SERVICE_FEE = 2_200_000L;

    private static final long NODE_DELETE_NODE_FEE = 100_000L;
    private static final long NODE_DELETE_NETWORK_FEE = 345_000_000L;
    private static final long NODE_DELETE_SERVICE_FEE = 200_000L;

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
                                .nodeCreate(
                                        NodeCreateTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        NODE_CREATE_NODE_FEE,
                        NODE_CREATE_NETWORK_FEE,
                        NODE_CREATE_SERVICE_FEE),
                new TestCase(
                        new NodeUpdateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .nodeUpdate(
                                        NodeUpdateTransactionBody.newBuilder().build())
                                .build(),
                        2,
                        NODE_UPDATE_NODE_FEE,
                        NODE_UPDATE_NETWORK_FEE,
                        NODE_UPDATE_SERVICE_FEE),
                new TestCase(
                        new NodeDeleteFeeCalculator(),
                        TransactionBody.newBuilder()
                                .nodeDelete(
                                        NodeDeleteTransactionBody.newBuilder().build())
                                .build(),
                        1,
                        NODE_DELETE_NODE_FEE,
                        NODE_DELETE_NETWORK_FEE,
                        NODE_DELETE_SERVICE_FEE));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Fee calculation for all Node*FeeCalculators")
    void testFeeCalculators(TestCase testCase) throws UnknownHederaFunctionality {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(testCase.numSignatures);
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.enableDAB", true)
                .getOrCreateConfig();
        lenient().when(feeContext.configuration()).thenReturn(config);
        when(feeContext.functionality()).thenReturn(functionOf(testCase.body()));
        final var result = feeCalculator.calculateTxFee(testCase.body, new SimpleFeeContextImpl(feeContext, null));
        assertThat(result).isNotNull();
        assertThat(result.getNodeTotalTinycents()).isEqualTo(testCase.expectedNodeFee);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(testCase.expectedServiceFee);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(testCase.expectedNetworkFee);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Fee calculation throws NOT_SUPPORTED when DAB is disabled")
    void testFeeCalculatorsThrowWhenDABDisabled(TestCase testCase) throws UnknownHederaFunctionality {
        lenient().when(feeContext.numTxnSignatures()).thenReturn(testCase.numSignatures);
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.enableDAB", false)
                .getOrCreateConfig();
        lenient().when(feeContext.configuration()).thenReturn(config);
        lenient().when(feeContext.functionality()).thenReturn(functionOf(testCase.body()));
        final var simpleFeeContext = new SimpleFeeContextImpl(feeContext, null);
        final var ex = assertThrows(
                HandleException.class, () -> feeCalculator.calculateTxFee(testCase.body, simpleFeeContext));
        assertThat(ex.getStatus()).isEqualTo(ResponseCodeEnum.NOT_SUPPORTED);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Fee calculation works when feeContext is null (skips DAB check)")
    void testFeeCalculatorsWithNullFeeContext(TestCase testCase) {
        final var simpleFeeContext = new SimpleFeeContextImpl(null, null);
        final var feeResult = new FeeResult();
        final var testSchedule = createTestFeeSchedule();
        testCase.calculator().accumulateServiceFee(testCase.body(), simpleFeeContext, feeResult, testSchedule);
        assertThat(feeResult.getServiceBaseFeeTinycents()).isGreaterThan(0);
    }

    // ─── Registered Node Fee Calculators ───────────────────────────

    static Stream<RegisteredNodeTestCase> provideRegisteredNodeTestCases() {
        return Stream.of(
                new RegisteredNodeTestCase(
                        new RegisteredNodeCreateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .registeredNodeCreate(RegisteredNodeCreateTransactionBody.newBuilder()
                                        .build())
                                .build()),
                new RegisteredNodeTestCase(
                        new RegisteredNodeUpdateFeeCalculator(),
                        TransactionBody.newBuilder()
                                .registeredNodeUpdate(RegisteredNodeUpdateTransactionBody.newBuilder()
                                        .build())
                                .build()),
                new RegisteredNodeTestCase(
                        new RegisteredNodeDeleteFeeCalculator(),
                        TransactionBody.newBuilder()
                                .registeredNodeDelete(RegisteredNodeDeleteTransactionBody.newBuilder()
                                        .build())
                                .build()));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideRegisteredNodeTestCases")
    @DisplayName("Registered node fee calculation throws NOT_SUPPORTED when registeredNodesEnabled is false")
    void testRegisteredNodeFeeCalculatorsThrowWhenDisabled(RegisteredNodeTestCase testCase) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.registeredNodesEnabled", false)
                .getOrCreateConfig();
        lenient().when(feeContext.configuration()).thenReturn(config);
        final var simpleFeeContext = new SimpleFeeContextImpl(feeContext, null);
        final var feeResult = new FeeResult();
        final var testSchedule = createTestFeeSchedule();

        final var ex = assertThrows(HandleException.class, () -> testCase.calculator()
                .accumulateServiceFee(testCase.body(), simpleFeeContext, feeResult, testSchedule));
        assertThat(ex.getStatus()).isEqualTo(ResponseCodeEnum.NOT_SUPPORTED);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideRegisteredNodeTestCases")
    @DisplayName("Registered node fee calculation succeeds when registeredNodesEnabled is true")
    void testRegisteredNodeFeeCalculatorsSucceedWhenEnabled(RegisteredNodeTestCase testCase) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.registeredNodesEnabled", true)
                .getOrCreateConfig();
        lenient().when(feeContext.configuration()).thenReturn(config);
        final var simpleFeeContext = new SimpleFeeContextImpl(feeContext, null);
        final var feeResult = new FeeResult();
        final var testSchedule = createTestFeeSchedule();

        testCase.calculator().accumulateServiceFee(testCase.body(), simpleFeeContext, feeResult, testSchedule);
        assertThat(feeResult.getServiceBaseFeeTinycents()).isGreaterThan(0);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideRegisteredNodeTestCases")
    @DisplayName("Registered node fee calculation works when feeContext is null (skips check)")
    void testRegisteredNodeFeeCalculatorsWithNullFeeContext(RegisteredNodeTestCase testCase) {
        final var simpleFeeContext = new SimpleFeeContextImpl(null, null);
        final var feeResult = new FeeResult();
        final var testSchedule = createTestFeeSchedule();

        testCase.calculator().accumulateServiceFee(testCase.body(), simpleFeeContext, feeResult, testSchedule);
        assertThat(feeResult.getServiceBaseFeeTinycents()).isGreaterThan(0);
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
                        makeExtraDef(Extra.STATE_BYTES, 110000))
                .services(makeService(
                        "AddressBookService",
                        makeServiceFee(
                                HederaFunctionality.NODE_CREATE,
                                123000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.STATE_BYTES, 1000)),
                        makeServiceFee(
                                HederaFunctionality.NODE_UPDATE,
                                234000000,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.STATE_BYTES, 1000)),
                        makeServiceFee(HederaFunctionality.NODE_DELETE, 345000000),
                        makeServiceFee(HederaFunctionality.REGISTERED_NODE_CREATE, 100000000),
                        makeServiceFee(HederaFunctionality.REGISTERED_NODE_UPDATE, 200000000),
                        makeServiceFee(HederaFunctionality.REGISTERED_NODE_DELETE, 300000000)))
                .build();
    }

    record RegisteredNodeTestCase(ServiceFeeCalculator calculator, TransactionBody body) {
        @Override
        public @NonNull String toString() {
            return calculator.getClass().getSimpleName();
        }
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
