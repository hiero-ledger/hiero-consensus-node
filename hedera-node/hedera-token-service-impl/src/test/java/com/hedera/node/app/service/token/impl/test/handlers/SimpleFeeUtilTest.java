// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.service.token.impl.handlers.SimpleFeeUtil;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.hiero.hapi.fees.FeeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for SimpleFeeUtil utility methods.
 * Validates simple fees feature flag checking and fee conversion from FeeResult (tinycents)
 * to Fees (tinybars) using exchange rates.
 */
@ExtendWith(MockitoExtension.class)
class SimpleFeeUtilTest {

    @Test
    void shouldUseSimpleFeesReturnsTrueWhenEnabled() {
        // given
        final var config = HederaTestConfigBuilder.create()
                .withValue("fees.simpleFeesEnabled", true)
                .getOrCreateConfig();
        final var queryContext = mock(QueryContext.class);
        given(queryContext.configuration()).willReturn(config);

        // when
        final var result = SimpleFeeUtil.shouldUseSimpleFees(queryContext);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void shouldUseSimpleFeesReturnsFalseWhenDisabled() {
        // given
        final var config = HederaTestConfigBuilder.create()
                .withValue("fees.simpleFeesEnabled", false)
                .getOrCreateConfig();
        final var queryContext = mock(QueryContext.class);
        given(queryContext.configuration()).willReturn(config);

        // when
        final var result = SimpleFeeUtil.shouldUseSimpleFees(queryContext);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void convertFeeResultToFeesWithStandardRates() {
        // given
        final var feeResult = createFeeResult(100L, 200L, 300L);
        final var rate = ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(12).build();

        // when
        final var fees = SimpleFeeUtil.convertFeeResultToFees(feeResult, rate);

        // then
        // 100 tinycents * 1 hbar / 12 cents = 8 tinybars (integer division)
        assertThat(fees.nodeFee()).isEqualTo(8L);
        // 200 tinycents * 1 hbar / 12 cents = 16 tinybars
        assertThat(fees.networkFee()).isEqualTo(16L);
        // 300 tinycents * 1 hbar / 12 cents = 25 tinybars
        assertThat(fees.serviceFee()).isEqualTo(25L);
    }

    @Test
    void convertFeeResultToFeesWithZeroAmounts() {
        // given
        final var feeResult = createFeeResult(0L, 0L, 0L);
        final var rate = ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(12).build();

        // when
        final var fees = SimpleFeeUtil.convertFeeResultToFees(feeResult, rate);

        // then
        assertThat(fees.nodeFee()).isZero();
        assertThat(fees.networkFee()).isZero();
        assertThat(fees.serviceFee()).isZero();
    }

    @Test
    void convertFeeResultToFeesWithLargeAmounts() {
        // given
        final var feeResult = createFeeResult(1_000_000L, 2_000_000L, 3_000_000L);
        final var rate = ExchangeRate.newBuilder().hbarEquiv(30).centEquiv(1200).build();

        // when
        final var fees = SimpleFeeUtil.convertFeeResultToFees(feeResult, rate);

        // then
        // 1_000_000 * 30 / 1200 = 25_000
        assertThat(fees.nodeFee()).isEqualTo(25_000L);
        assertThat(fees.networkFee()).isEqualTo(50_000L);
        assertThat(fees.serviceFee()).isEqualTo(75_000L);
    }

    @Test
    void convertFeeResultToFeesHandlesOverflow() {
        // given - amount that would overflow when multiplied by hbarEquiv
        final var feeResult = createFeeResult(Long.MAX_VALUE / 2, 0L, 0L);
        final var rate =
                ExchangeRate.newBuilder().hbarEquiv(100).centEquiv(1200).build();

        // when - should use fallback calculation via FeeBuilder
        final var fees = SimpleFeeUtil.convertFeeResultToFees(feeResult, rate);

        // then - should get a valid result without overflow
        assertThat(fees.nodeFee()).isGreaterThan(0L);
    }

    @Test
    void convertFeeResultToFeesMaintainsPrecision() {
        // given - testing precision with small rates
        final var feeResult = createFeeResult(1L, 5L, 10L);
        final var rate = ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(1).build();

        // when
        final var fees = SimpleFeeUtil.convertFeeResultToFees(feeResult, rate);

        // then - with 1:1 rate, values should be unchanged
        assertThat(fees.nodeFee()).isEqualTo(1L);
        assertThat(fees.networkFee()).isEqualTo(5L);
        assertThat(fees.serviceFee()).isEqualTo(10L);
    }

    @Test
    void convertFeeResultToFeesWithDifferentExchangeRates() {
        // given
        final var feeResult = createFeeResult(120L, 240L, 360L);
        final var rate = ExchangeRate.newBuilder().hbarEquiv(5).centEquiv(100).build();

        // when
        final var fees = SimpleFeeUtil.convertFeeResultToFees(feeResult, rate);

        // then - 120 * 5 / 100 = 6
        assertThat(fees.nodeFee()).isEqualTo(6L);
        assertThat(fees.networkFee()).isEqualTo(12L);
        assertThat(fees.serviceFee()).isEqualTo(18L);
    }

    /**
     * Helper method to create FeeResult instances with specified values.
     */
    private static FeeResult createFeeResult(final long node, final long network, final long service) {
        final var result = new FeeResult();
        result.node = node;
        result.network = network;
        result.service = service;
        return result;
    }
}
