// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.context.SimpleFeeContextImpl;
import com.hedera.node.app.spi.fees.FeeContext;
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
class TokenBurnFeeCalculatorsTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new TokenBurnFeeCalculator()));
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_BURN);
    }

    @Test
    @DisplayName("TokenBurnFeeCalculator charges flat service fee for fungible burn")
    void tokenBurnFungibleCase() {
        var body = TransactionBody.newBuilder()
                .tokenBurn(TokenBurnTransactionBody.newBuilder().amount(100L).build())
                .build();

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getServiceTotalTinycents()).isEqualTo(9_000_000L);
    }

    @Test
    @DisplayName("TokenBurnFeeCalculator charges flat service fee for 1 NFT serial (within includedCount)")
    void tokenBurnNftOneSerial() {
        var body = TransactionBody.newBuilder()
                .tokenBurn(
                        TokenBurnTransactionBody.newBuilder().serialNumbers(1L).build())
                .build();

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getServiceTotalTinycents()).isEqualTo(9_000_000L);
    }

    @Test
    @DisplayName("TokenBurnFeeCalculator scales service fee for 3 NFT serials")
    void tokenBurnNftThreeSerialsScales() {
        var body = TransactionBody.newBuilder()
                .tokenBurn(TokenBurnTransactionBody.newBuilder()
                        .serialNumbers(1L, 2L, 3L)
                        .build())
                .build();

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        // base 9_000_000 + 2 extra serials * 8_900_000 = 26_800_000
        assertThat(result.getServiceTotalTinycents()).isEqualTo(26_800_000L);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder().baseFee(1000L).extras(List.of()).build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(makeExtraDef(Extra.SIGNATURES, 1_000_000L), makeExtraDef(Extra.NFT_SERIALS, 8_900_000L))
                .services(makeService(
                        "Token",
                        makeServiceFee(
                                HederaFunctionality.TOKEN_BURN, 9_000_000L, makeExtraIncluded(Extra.NFT_SERIALS, 1))))
                .build();
    }
}
