// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.context.SimpleFeeContextImpl;
import com.hedera.node.app.spi.fees.FeeContext;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.hiero.hapi.support.fees.*;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TokenDissociateFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class TokenDissociateFeeCalculatorTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        feeCalculator =
                new SimpleFeeCalculatorImpl(createTestFeeSchedule(), Set.of(new TokenDissociateFeeCalculator()));
        lenient().when(feeContext.functionality()).thenReturn(HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT);
        lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
    }

    @Test
    void singleTokenPaysOnlyTheBaseFee() {
        final var result = feeCalculator.calculateTxFee(dissociate(1), new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getServiceTotalTinycents()).isEqualTo(499000000L);
    }

    @Test
    void eachExtraTokenIsCharged() {
        final var result = feeCalculator.calculateTxFee(dissociate(10), new SimpleFeeContextImpl(feeContext, null));

        // Base fee (499000000) + 9 extra tokens (9 * 500000000 = 4500000000)
        assertThat(result.getServiceTotalTinycents()).isEqualTo(4999000000L);
    }

    @Test
    void scheduleWithoutTheExtraChargesOnlyTheBaseFee() {
        final var oldSchedule = createTestFeeSchedule()
                .copyBuilder()
                .services(makeService(
                        "TokenService", makeServiceFee(HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT, 499000000L)))
                .build();
        final var calculator = new SimpleFeeCalculatorImpl(oldSchedule, Set.of(new TokenDissociateFeeCalculator()));

        final var result = calculator.calculateTxFee(dissociate(10), new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getServiceTotalTinycents()).isEqualTo(499000000L);
    }

    private static TransactionBody dissociate(final int numTokens) {
        final var tokens = LongStream.rangeClosed(1, numTokens)
                .mapToObj(i -> TokenID.newBuilder().tokenNum(1000L + i).build())
                .toList();
        return TransactionBody.newBuilder()
                .tokenDissociate(TokenDissociateTransactionBody.newBuilder()
                        .account(AccountID.newBuilder().accountNum(123).build())
                        .tokens(tokens)
                        .build())
                .build();
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(9).build())
                .extras(makeExtraDef(Extra.SIGNATURES, 1000000L), makeExtraDef(Extra.TOKEN_DISSOCIATE, 500000000L))
                .services(makeService(
                        "TokenService",
                        makeServiceFee(
                                HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT,
                                499000000L,
                                makeExtraIncluded(Extra.TOKEN_DISSOCIATE, 1))))
                .build();
    }
}
