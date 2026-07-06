// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static com.hedera.node.app.service.contract.impl.test.handlers.ContractCallHandlerTest.INTRINSIC_GAS_FOR_0_ARG_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.ContextQueryProcessor;
import com.hedera.node.app.service.contract.impl.exec.QueryComponent;
import com.hedera.node.app.service.contract.impl.exec.gas.GasCharges;
import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculator;
import com.hedera.node.app.service.contract.impl.handlers.ContractCallLocalHandler;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.InstantSource;
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
    private HederaGasCalculator gasCalculator;

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
        given(gasCalculator.transactionGasRequirements(anyInt(), anyInt(), anyBoolean(), any(), any()))
                .willReturn(GasCharges.NONE);
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
        given(gasCalculator.transactionGasRequirements(anyInt(), anyInt(), anyBoolean(), any(), any()))
                .willReturn(GasCharges.NONE);
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
        given(gasCalculator.transactionGasRequirements(anyInt(), anyInt(), anyBoolean(), any(), any()))
                .willReturn(GasCharges.NONE);
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
        given(gasCalculator.transactionGasRequirements(anyInt(), anyInt(), anyBoolean(), any(), any()))
                .willReturn(GasCharges.NONE);
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
        given(gasCalculator.transactionGasRequirements(anyInt(), anyInt(), anyBoolean(), any(), any()))
                .willReturn(GasCharges.NONE);
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
                null,
                null);
        given(processor.call()).willReturn(expectedOutcome);

        // given(processor.call()).willReturn(responseHeader);
        // when:
        var response = subject.findResponse(context, responseHeader);

        assertThat(response.contractCallLocal().header()).isEqualTo(responseHeader);
        assertThat(response.contractCallLocal().functionResult()).isEqualTo(expectedOutcome.result());
    }

    private void givenDefaultConfig() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
    }

    private void givenAllowCallsToNonContractAccountOffConfig() {
        given(context.configuration()).willReturn(configuration);
        given(configuration.getConfigData(ContractsConfig.class)).willReturn(contractsConfig);
    }
}
