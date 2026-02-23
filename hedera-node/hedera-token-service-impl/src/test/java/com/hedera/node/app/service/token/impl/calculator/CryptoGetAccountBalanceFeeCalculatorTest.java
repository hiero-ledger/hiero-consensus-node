// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.token.CryptoGetAccountBalanceQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.spi.fees.FeeContext;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CryptoGetAccountBalanceFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoGetAccountBalanceFeeCalculatorTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;
    private FeeSchedule testSchedule;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
        feeCalculator =
                new SimpleFeeCalculatorImpl(testSchedule, Set.of(), Set.of(new CryptoGetAccountBalanceFeeCalculator()));
    }

    @Nested
    @DisplayName("CryptoGetAccountBalance Fee Calculation Tests")
    class CryptoGetAccountBalanceTests {
        @Test
        @DisplayName("calculateQueryFee returns correct base fee (free query)")
        void calculateQueryFeeBasic() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var cryptoGetBalance = CryptoGetAccountBalanceQuery.newBuilder()
                    .header(QueryHeader.DEFAULT)
                    .accountID(AccountID.newBuilder().accountNum(1001).build())
                    .build();
            final var query =
                    Query.newBuilder().cryptogetAccountBalance(cryptoGetBalance).build();

            // When
            final var result = feeCalculator.calculateQueryFee(query, null);

            // Then: service=0 (free query)
            assertThat(result).isNotNull();
            assertThat(result.getServiceTotalTinycents()).isEqualTo(0L);
        }

        @Test
        @DisplayName("calculateQueryFee with null fee context")
        void calculateQueryFeeWithNullContext() {
            // Given
            final var cryptoGetBalance = CryptoGetAccountBalanceQuery.newBuilder()
                    .header(QueryHeader.DEFAULT)
                    .accountID(AccountID.newBuilder().accountNum(1001).build())
                    .build();
            final var query =
                    Query.newBuilder().cryptogetAccountBalance(cryptoGetBalance).build();

            // When
            final var result = feeCalculator.calculateQueryFee(query, null);

            // Then: Same fees - context is optional
            assertThat(result.getServiceTotalTinycents()).isEqualTo(0L);
        }
    }

    @Test
    @DisplayName("getQueryType returns CRYPTOGET_ACCOUNT_BALANCE")
    void getQueryTypeReturnsCryptoGetAccountBalance() {
        final var calculator = new CryptoGetAccountBalanceFeeCalculator();
        assertThat(calculator.getQueryType()).isEqualTo(Query.QueryOneOfType.CRYPTOGET_ACCOUNT_BALANCE);
    }

    // Helper method to create test fee schedule
    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 10)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(makeExtraDef(Extra.SIGNATURES, 1000000L))
                .services(makeService(
                        "CryptoService", makeServiceFee(HederaFunctionality.CRYPTO_GET_ACCOUNT_BALANCE, 0L)))
                .build();
    }
}
