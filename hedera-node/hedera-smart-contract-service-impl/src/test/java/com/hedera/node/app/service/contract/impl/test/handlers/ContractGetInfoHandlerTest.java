// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractGetInfoQuery;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetInfoHandler;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.swirlds.config.api.Configuration;
import java.time.InstantSource;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractGetInfoHandlerTest {

    @Mock
    private QueryContext context;

    @Mock
    private Query query;

    @Mock
    private ContractGetInfoQuery contractGetInfoQuery;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableTokenStore tokenStore;

    @Mock
    private ReadableStakingInfoStore stakingInfoStore;

    @Mock
    private ReadableTokenRelationStore tokenRelationStore;

    @Mock
    private ReadableNetworkStakingRewardsStore networkStakingRewardsStore;

    @Mock
    private TokensConfig tokensConfig;

    @Mock
    private LedgerConfig ledgerConfig;

    @Mock
    private StakingConfig stakingConfig;

    @Mock
    private Configuration configuration;

    @Mock
    private EntityIdFactory entityIdFactory;

    private final Account smartContractAccount = Account.newBuilder()
            .smartContract(true)
            .accountId(AccountID.newBuilder().accountNum(1).build())
            .key(Key.DEFAULT)
            .build();

    private ContractGetInfoHandler handler;

    private final InstantSource instantSource = InstantSource.system();

    @BeforeEach
    void setUp() {
        handler = new ContractGetInfoHandler(instantSource, entityIdFactory);
    }

    @Test
    void extractHeaderShouldReturnHeader() {
        // given
        final var queryHeader = QueryHeader.newBuilder().build();
        when(query.contractGetInfoOrThrow()).thenReturn(contractGetInfoQuery);
        when(contractGetInfoQuery.header()).thenReturn(queryHeader);

        // when
        final var result = handler.extractHeader(query);

        // then
        assertThat(result).isSameAs(queryHeader);
    }

    @Test
    void createEmptyResponseShouldReturnResponse() {
        // given
        final var responseHeader =
                ResponseHeader.newBuilder().responseType(ANSWER_ONLY).build();

        // when
        final var response = handler.createEmptyResponse(responseHeader);

        // then
        assertThat(response).isNotNull();
        assertThat(response.contractGetInfo()).isNotNull();
        assertThat(response.contractGetInfoOrThrow().headerOrThrow()).isEqualTo(responseHeader);
    }

    @Test
    void validateShouldThrowWhenContractNotFound() {
        when(context.query()).thenReturn(query);
        when(query.contractGetInfoOrThrow()).thenReturn(contractGetInfoQuery);
        when(accountStore.getContractById(any())).thenReturn(null);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(accountStore);

        assertThatThrownBy(() -> handler.validate(context))
                .isInstanceOf(PreCheckException.class)
                .hasMessageContaining(INVALID_CONTRACT_ID.toString());
    }

    @Test
    void validateShouldNotThrowWhenContractFound() {
        mockContract();
        assertThatCode(() -> handler.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void findResponseShouldReturnValidResponseWhenQueryIsValid() {
        // given
        final var responseHeader = ResponseHeader.newBuilder()
                .responseType(ANSWER_ONLY)
                .nodeTransactionPrecheckCode(OK)
                .build();
        mockContract();
        mockConfigurationAndStores();

        // when
        final var response = handler.findResponse(context, responseHeader);

        // then
        assertThat(response).isNotNull();
        assertThat(response.contractGetInfo()).isNotNull();
        assertThat(response.contractGetInfoOrThrow().headerOrThrow()).isEqualTo(responseHeader);
    }

    @Test
    void computeFeesShouldReturnFees() {
        // given
        given(context.feeCalculator()).willReturn(feeCalculator);
        when(feeCalculator.legacyCalculate(any())).thenAnswer(invocation -> new Fees(10L, 0L, 0L));

        // when
        var fees = handler.computeFees(context);

        // then
        assertThat(fees).isNotNull();
        assertThat(fees.nodeFee()).isEqualTo(10L);
    }

    @Test
    void computeFeesWithNullContractShouldReturnConstantFeeData() {
        // given
        when(context.feeCalculator()).thenReturn(feeCalculator);
        when(context.query()).thenReturn(query);
        when(query.contractGetInfoOrThrow()).thenReturn(contractGetInfoQuery);
        when(accountStore.getContractById(any())).thenReturn(null);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(accountStore);

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
        final var nodeData = FeeData.newBuilder().setNodedata(components).build();

        when(feeCalculator.legacyCalculate(any())).thenAnswer(invocation -> {
            Function<SigValueObj, FeeData> function = invocation.getArgument(0);
            final var feeData = function.apply(new SigValueObj(1, 1, 1));
            long nodeFee = FeeBuilder.getComponentFeeInTinyCents(nodeData.getNodedata(), feeData.getNodedata());
            return new Fees(nodeFee, 0L, 0L);
        });

        // when
        Fees actualFees = handler.computeFees(context);

        // then
        assertThat(actualFees.nodeFee()).isEqualTo(1L);
        assertThat(actualFees.networkFee()).isZero();
        assertThat(actualFees.serviceFee()).isZero();
    }

    private void mockContract() {
        when(context.query()).thenReturn(query);
        when(query.contractGetInfoOrThrow()).thenReturn(contractGetInfoQuery);
        when(contractGetInfoQuery.contractIDOrElse(any()))
                .thenReturn(ContractID.newBuilder().contractNum(1000).build());
        when(accountStore.getContractById(any(ContractID.class))).thenReturn(smartContractAccount);
        when(context.createStore(ReadableAccountStore.class)).thenReturn(accountStore);
    }

    private void mockConfigurationAndStores() {
        when(context.configuration()).thenReturn(configuration);
        when(configuration.getConfigData(TokensConfig.class)).thenReturn(tokensConfig);
        when(configuration.getConfigData(LedgerConfig.class)).thenReturn(ledgerConfig);
        when(configuration.getConfigData(StakingConfig.class)).thenReturn(stakingConfig);
        when(context.createStore(ReadableTokenStore.class)).thenReturn(tokenStore);
        when(context.createStore(ReadableStakingInfoStore.class)).thenReturn(stakingInfoStore);
        when(context.createStore(ReadableTokenRelationStore.class)).thenReturn(tokenRelationStore);
        when(context.createStore(ReadableNetworkStakingRewardsStore.class)).thenReturn(networkStakingRewardsStore);
    }
}
