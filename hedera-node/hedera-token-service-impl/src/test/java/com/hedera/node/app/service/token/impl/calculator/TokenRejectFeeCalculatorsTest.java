// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.token.TokenReference;
import com.hedera.hapi.node.token.TokenRejectTransactionBody;
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
class TokenRejectFeeCalculatorsTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(testSchedule, Set.of(new TokenRejectFeeCalculator()));
        when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_REJECT);
    }

    @Test
    @DisplayName("TokenRejectFeeCalculator charges flat service fee for 1 rejection (within includedCount)")
    void rejectOne() {
        var rejection = TokenReference.newBuilder().build();
        var body = TransactionBody.newBuilder()
                .tokenReject(TokenRejectTransactionBody.newBuilder()
                        .rejections(List.of(rejection))
                        .build())
                .build();

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        assertThat(result.getServiceTotalTinycents()).isEqualTo(9_000_000L);
    }

    @Test
    @DisplayName("TokenRejectFeeCalculator scales service fee for 3 rejections")
    void rejectThreeScales() {
        var rejection = TokenReference.newBuilder().build();
        var body = TransactionBody.newBuilder()
                .tokenReject(TokenRejectTransactionBody.newBuilder()
                        .rejections(List.of(rejection, rejection, rejection))
                        .build())
                .build();

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result).isNotNull();
        // base 9_000_000 + 2 extra token types * 1_000_000 = 11_000_000
        assertThat(result.getServiceTotalTinycents()).isEqualTo(11_000_000L);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder().baseFee(1000L).extras(List.of()).build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(makeExtraDef(Extra.SIGNATURES, 1_000_000L), makeExtraDef(Extra.TOKEN_TYPES, 1_000_000L))
                .services(makeService(
                        "Token",
                        makeServiceFee(
                                HederaFunctionality.TOKEN_REJECT, 9_000_000L, makeExtraIncluded(Extra.TOKEN_TYPES, 1))))
                .build();
    }
}
