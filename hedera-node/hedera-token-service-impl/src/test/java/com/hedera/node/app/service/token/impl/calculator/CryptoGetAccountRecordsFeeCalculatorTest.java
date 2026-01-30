// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.*;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.token.CryptoGetAccountRecordsQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeContextUtil;
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
 * Unit tests for {@link CryptoGetAccountRecordsFeeCalculator}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoGetAccountRecordsFeeCalculatorTest {

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;
    private FeeSchedule testSchedule;

    @BeforeEach
    void setUp() {
        testSchedule = createTestFeeSchedule();
        feeCalculator =
                new SimpleFeeCalculatorImpl(testSchedule, Set.of(), Set.of(new CryptoGetAccountRecordsFeeCalculator()));
    }

    @Nested
    @DisplayName("CryptoGetAccountRecords Fee Calculation Tests")
    class CryptoGetAccountRecordsTests {
        @Test
        @DisplayName("calculateQueryFee returns correct base fee")
        void calculateQueryFeeBasic() {
            // Given
            lenient().when(feeContext.numTxnSignatures()).thenReturn(1);
            final var cryptoGetRecords = CryptoGetAccountRecordsQuery.newBuilder()
                    .header(QueryHeader.DEFAULT)
                    .accountID(AccountID.newBuilder().accountNum(1001).build())
                    .build();
            final var query =
                    Query.newBuilder().cryptoGetAccountRecords(cryptoGetRecords).build();

            // When
            final var result = feeCalculator.calculateQueryFee(query, SimpleFeeContextUtil.fromFeeContext(null));

            // Then: service=1000000 ($0.0001 USD)
            assertThat(result).isNotNull();
            assertThat(result.totalTinycents()).isEqualTo(1000000L);
        }

        @Test
        @DisplayName("calculateQueryFee with null fee context")
        void calculateQueryFeeWithNullContext() {
            // Given
            final var cryptoGetRecords = CryptoGetAccountRecordsQuery.newBuilder()
                    .header(QueryHeader.DEFAULT)
                    .accountID(AccountID.newBuilder().accountNum(1001).build())
                    .build();
            final var query =
                    Query.newBuilder().cryptoGetAccountRecords(cryptoGetRecords).build();

            // When
            final var result = feeCalculator.calculateQueryFee(query, SimpleFeeContextUtil.fromFeeContext(null));

            // Then: Same fees - context is optional
            assertThat(result.totalTinycents()).isEqualTo(1000000L);
        }
    }

    @Test
    @DisplayName("getQueryType returns CRYPTO_GET_ACCOUNT_RECORDS")
    void getQueryTypeReturnsCryptoGetAccountRecords() {
        final var calculator = new CryptoGetAccountRecordsFeeCalculator();
        assertThat(calculator.getQueryType()).isEqualTo(Query.QueryOneOfType.CRYPTO_GET_ACCOUNT_RECORDS);
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
                        "CryptoService", makeServiceFee(HederaFunctionality.CRYPTO_GET_ACCOUNT_RECORDS, 1000000L)))
                .build();
    }
}
