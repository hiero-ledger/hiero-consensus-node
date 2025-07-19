// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONTRACTS_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT;
import static com.hedera.node.app.service.contract.impl.test.handlers.ContractCallHandlerTest.INTRINSIC_GAS_FOR_0_ARG_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.ContextQueryProcessor;
import com.hedera.node.app.service.contract.impl.exec.QueryComponent;
import com.hedera.node.app.service.contract.impl.handlers.ContractCallLocalHandler;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.EntityIdFactory;
import java.time.InstantSource;
import java.util.function.Function;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractCallLocalHandlerTest {
    @Mock
    private QueryContext context;

    @Mock
    private QueryComponent.Factory factory;

    @Mock
    private ContractCallLocalQuery contractCallLocalQuery;

    @Mock
    private QueryHeader header;

    @Mock
    private ResponseHeader responseHeader;

    @Mock
    private Query query;

    @Mock
    private ContractID contractID;

    @Mock
    private ReadableAccountStore store;

    @Mock
    private ReadableTokenStore tokenStore;

    @Mock
    private Account contract;

    @Mock
    private QueryComponent component;

    @Mock
    private ContextQueryProcessor processor;

    @Mock
    private Configuration configuration;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private ProxyWorldUpdater proxyWorldUpdater;

    private final ContractID invalidContract =
            ContractID.newBuilder().evmAddress(Bytes.fromHex("abcdabcd")).build();

    private final InstantSource instantSource = InstantSource.system();

    private ContractCallLocalHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ContractCallLocalHandler(() -> factory, gasCalculator, instantSource, entityIdFactory);
    }

    @Test
    void extractHeaderTest() {
        // given:
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(contractCallLocalQuery.header()).willReturn(header);

        // when:
        var header = subject.extractHeader(query);

        // then:
        assertThat(header).isNotNull();
    }

    @Test
    void createEmptyResponseTest() {
        // when:
        var response = subject.createEmptyResponse(responseHeader);

        // then:
        assertThat(response).isNotNull();
    }

    @Test
    void validatePositiveTest() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(contractCallLocalQuery.contractID()).willReturn(contractID);
        given(contractCallLocalQuery.functionParameters()).willReturn(Bytes.EMPTY);
        given(context.createStore(ReadableAccountStore.class)).willReturn(store);
        given(store.getContractById(contractID)).willReturn(contract);
        givenAllowCallsToNonContractAccountOffConfig();

        // when:
        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void validateFailsOnNegativeGas() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(contractCallLocalQuery.gas()).willReturn(-1L);

        // when:
        assertThatThrownBy(() -> subject.validate(context)).isInstanceOf(PreCheckException.class);
    }

    @Test
    void validateFailsOnExcessGas() {
        // given
        given(context.query()).willReturn(query);
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(contractCallLocalQuery.gas()).willReturn(DEFAULT_CONTRACTS_CONFIG.maxGasPerSec() + 1);

        // when:
        assertThatThrownBy(() -> subject.validate(context)).isInstanceOf(PreCheckException.class);
    }

    @Test
    void validateFailsIfNoContractIdTest() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(contractCallLocalQuery.contractID()).willReturn(null);
        given(contractCallLocalQuery.functionParameters()).willReturn(Bytes.EMPTY);
        givenDefaultConfig();

        // when:
        assertThatThrownBy(() -> subject.validate(context)).isInstanceOf(PreCheckException.class);
    }

    @Test
    void validateFailsIfInvalidContractIdTest() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(contractCallLocalQuery.contractID()).willReturn(invalidContract);
        given(contractCallLocalQuery.functionParameters()).willReturn(Bytes.EMPTY);
        givenDefaultConfig();

        // when:
        assertThatThrownBy(() -> subject.validate(context)).isInstanceOf(PreCheckException.class);
    }

    @Test
    void validateFailsIfNoContractOrTokenTest() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(contractCallLocalQuery.contractID()).willReturn(contractID);
        given(contractCallLocalQuery.functionParameters()).willReturn(Bytes.EMPTY);
        given(context.createStore(ReadableAccountStore.class)).willReturn(store);
        given(store.getContractById(contractID)).willReturn(null);
        given(context.createStore(ReadableTokenStore.class)).willReturn(tokenStore);
        given(tokenStore.get(any())).willReturn(null);
        givenAllowCallsToNonContractAccountOffConfig();

        // when:
        assertThatThrownBy(() -> subject.validate(context)).isInstanceOf(PreCheckException.class);
    }

    @Test
    void validateFailsIfGasIsLessThanIntrinsic() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(contractCallLocalQuery.gas()).willReturn(INTRINSIC_GAS_FOR_0_ARG_METHOD - 1);
        givenAllowCallsToNonContractAccountOffConfig();

        // when:
        assertThatThrownBy(() -> subject.validate(context)).isInstanceOf(PreCheckException.class);
    }

    @Test
    void validateSucceedsIfContractDeletedTest() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(contractCallLocalQuery.contractID()).willReturn(contractID);
        given(contractCallLocalQuery.functionParameters()).willReturn(Bytes.EMPTY);
        given(context.createStore(ReadableAccountStore.class)).willReturn(store);
        given(store.getContractById(contractID)).willReturn(contract);
        givenAllowCallsToNonContractAccountOffConfig();

        // when:
        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
        verify(contract, never()).deleted();
    }

    @Test
    void findResponsePositiveTest() {
        given(factory.create(any(), any(), eq(HederaFunctionality.CONTRACT_CALL_LOCAL)))
                .willReturn(component);
        given(component.contextQueryProcessor()).willReturn(processor);
        given(proxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        final var expectedResult = SUCCESS_RESULT.asQueryResult(proxyWorldUpdater);
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT.finalStatus(),
                null,
                null,
                null,
                null,
                null,
                SUCCESS_RESULT.asEvmQueryResult(),
                SUCCESS_RESULT.signerNonce(),
                null,
                null);
        given(processor.call()).willReturn(expectedOutcome);

        // given(processor.call()).willReturn(responseHeader);
        // when:
        var response = subject.findResponse(context, responseHeader);

        assertThat(response.contractCallLocal().header()).isEqualTo(responseHeader);
        assertThat(response.contractCallLocal().functionResult()).isEqualTo(expectedOutcome.result());
    }

    @Test
    @SuppressWarnings("unchecked")
    void computesFeesSuccessfully() {

        given(context.query()).willReturn(query);
        given(query.contractCallLocalOrThrow()).willReturn(contractCallLocalQuery);
        given(context.feeCalculator()).willReturn(feeCalculator);
        givenAllowCallsToNonContractAccountOffConfig();

        // Mock the behavior of legacyCalculate method
        when(feeCalculator.legacyCalculate(any(Function.class))).thenAnswer(invocation -> {
            // Extract the callback passed to the method
            return new Fees(10L, 0L, 0L);
        });

        var fees = subject.computeFees(context);

        assertThat(fees).isEqualTo(new Fees(10L, 0L, 0L));
    }

    @Test
    void computeFeesWithNullContractShouldNotThrow() {
        // given
        when(context.feeCalculator()).thenReturn(feeCalculator);
        when(context.configuration()).thenReturn(configuration);
        when(configuration.getConfigData(ContractsConfig.class)).thenReturn(contractsConfig);
        when(contractsConfig.localCallEstRetBytes()).thenReturn(10);

        when(context.query()).thenReturn(query);
        when(query.contractCallLocalOrThrow()).thenReturn(contractCallLocalQuery);
        when(contractCallLocalQuery.functionParameters()).thenReturn(Bytes.EMPTY);
        when(contractCallLocalQuery.contractIDOrElse(ContractID.DEFAULT)).thenReturn(ContractID.DEFAULT);
        when(contractCallLocalQuery.headerOrElse(QueryHeader.DEFAULT)).thenReturn(QueryHeader.DEFAULT);

        final var components = FeeComponents.newBuilder()
                .setMax(15000)
                .setBpt(25)
                .setVpt(25)
                .setRbh(25)
                .setSbh(25)
                .setGas(25)
                .setTv(25)
                .setBpr(25)
                .setSbpr(25)
                .setConstant(1)
                .build();
        final var nodeData = com.hederahashgraph.api.proto.java.FeeData.newBuilder()
                .setNodedata(components)
                .build();

        when(feeCalculator.legacyCalculate(any())).thenAnswer(invocation -> {
            Function<SigValueObj, com.hederahashgraph.api.proto.java.FeeData> function = invocation.getArgument(0);
            final var feeData = function.apply(new SigValueObj(1, 1, 1));
            long nodeFee = FeeBuilder.getComponentFeeInTinyCents(nodeData.getNodedata(), feeData.getNodedata());
            return new Fees(nodeFee, 0L, 0L);
        });

        // when
        Fees actualFees = subject.computeFees(context);

        // then
        assertThat(actualFees.nodeFee()).isEqualTo(7L);
        assertThat(actualFees.networkFee()).isZero();
        assertThat(actualFees.serviceFee()).isZero();
    }

    private void givenDefaultConfig() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
    }

    private void givenAllowCallsToNonContractAccountOffConfig() {
        given(context.configuration()).willReturn(configuration);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
    }
}
