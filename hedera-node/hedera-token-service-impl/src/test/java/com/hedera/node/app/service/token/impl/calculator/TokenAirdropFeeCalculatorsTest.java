// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenAirdropTransactionBody;
import com.hedera.hapi.node.token.TokenCancelAirdropTransactionBody;
import com.hedera.hapi.node.token.TokenClaimAirdropTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.SimpleFeeContextImpl;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenAirdropFeeCalculatorsTest {
    private static final TokenID TOKEN_ID = TokenID.newBuilder().tokenNum(2001L).build();

    @Mock
    private FeeContext feeContext;

    @Mock
    private ReadableTokenStore tokenStore;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule,
                Set.of(
                        new TokenAirdropFeeCalculator(),
                        new TokenClaimAirdropFeeCalculator(),
                        new TokenCancelAirdropFeeCalculator()));
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_AIRDROP);
    }

    @Test
    @DisplayName("TokenAirdropFeeCalculator calculates correct fees")
    void tokenAirdropFeeCalculatorCalculatesCorrectFees() {
        final var configMock = mock(Configuration.class);
        final var tokenConfigMock = mock(TokensConfig.class);
        when(tokenConfigMock.airdropsEnabled()).thenReturn(true);
        when(configMock.getConfigData(TokensConfig.class)).thenReturn(tokenConfigMock);
        when(feeContext.configuration()).thenReturn(configMock);
        lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
        final var token = Token.newBuilder()
                .tokenId(TOKEN_ID)
                .tokenType(TokenType.FUNGIBLE_COMMON)
                .build();
        when(tokenStore.get(TOKEN_ID)).thenReturn(token);
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

        final var tokenTransfers = TokenTransferList.newBuilder()
                .token(TOKEN_ID)
                .transfers(
                        AccountAmount.newBuilder()
                                .accountID(
                                        AccountID.newBuilder().accountNum(1001L).build())
                                .amount(-50L)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(
                                        AccountID.newBuilder().accountNum(1002L).build())
                                .amount(50L)
                                .build())
                .build();

        var body = TransactionBody.newBuilder()
                .tokenAirdrop(TokenAirdropTransactionBody.newBuilder()
                        .tokenTransfers(tokenTransfers)
                        .build())
                .build();

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getNodeTotalTinycents()).isEqualTo(1000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(9000100L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("TokenCancelAirdropFeeCalculator calculates correct fees")
    void tokenCancelAirdropFeeCalculatorCalculatesCorrectFees() {
        lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

        var body = TransactionBody.newBuilder()
                .tokenCancelAirdrop(
                        TokenCancelAirdropTransactionBody.newBuilder().build())
                .build();

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getNodeTotalTinycents()).isEqualTo(1000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(199000000L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("TokenClaimAirdropFeeCalculator calculates correct fees")
    void tokenClaimAirdropFeeCalculatorCalculatesCorrectFees() {
        final var configMock = mock(Configuration.class);
        final var tokenConfigMock = mock(TokensConfig.class);
        when(tokenConfigMock.airdropsClaimEnabled()).thenReturn(true);
        when(configMock.getConfigData(TokensConfig.class)).thenReturn(tokenConfigMock);
        when(feeContext.configuration()).thenReturn(configMock);
        lenient().when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(tokenStore);
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);

        var body = TransactionBody.newBuilder()
                .tokenClaimAirdrop(TokenClaimAirdropTransactionBody.newBuilder().build())
                .build();

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getNodeTotalTinycents()).isEqualTo(1000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(299000000L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(2000L);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder().baseFee(1000L).extras(List.of()).build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000L),
                        makeExtraDef(Extra.KEYS, 100000000L),
                        makeExtraDef(Extra.FUNGIBLE_TOKENS, 1000000L),
                        makeExtraDef(Extra.NON_FUNGIBLE_TOKENS, 1000000L),
                        makeExtraDef(Extra.TOKEN_TRANSFER_BASE, 9000000L),
                        makeExtraDef(Extra.AIRDROPS, 5000000L))
                .services(makeService(
                        "Token",
                        makeServiceFee(HederaFunctionality.TOKEN_CLAIM_AIRDROP, 299000000),
                        makeServiceFee(HederaFunctionality.TOKEN_CANCEL_AIRDROP, 199000000),
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_TRANSFER,
                                100L,
                                makeExtraIncluded(Extra.TOKEN_TRANSFER_BASE, 0),
                                makeExtraIncluded(Extra.FUNGIBLE_TOKENS, 1),
                                makeExtraIncluded(Extra.NON_FUNGIBLE_TOKENS, 1))))
                .build();
    }
}
