// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import org.junit.jupiter.api.Test;

final class NativeCoinConfigTest {

    @Test
    void testDefaultDecimalValue() {
        // given
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();

        // when
        final var nativeCoinConfig = config.getConfigData(NativeCoinConfig.class);

        // then — AC-2: default 8 and subunitsPerWholeUnit 100_000_000
        assertThat(nativeCoinConfig.decimals()).isEqualTo(8);
    }

    @Test
    void testCustomDecimalValue() {
        // given
        final var config = HederaTestConfigBuilder.create()
                .withValue("nativeCoin.decimals", "6")
                .getOrCreateConfig();

        // when
        final var nativeCoinConfig = config.getConfigData(NativeCoinConfig.class);

        // then
        assertThat(nativeCoinConfig.decimals()).isEqualTo(6);
    }

    @Test
    void testDecimalsTooHigh() {
        // given
        final var builder = HederaTestConfigBuilder.create().withValue("nativeCoin.decimals", "19");

        // then — AC-3: config framework rejects out-of-range value
        // ConfigViolationException contains ConfigViolation objects with property details;
        // the exception message itself is generic ("Configuration failed based on N violations!")
        assertThatThrownBy(builder::getOrCreateConfig).isInstanceOf(ConfigViolationException.class);
    }

    @Test
    void testDecimalsTooLow() {
        // given
        final var builder = HederaTestConfigBuilder.create().withValue("nativeCoin.decimals", "-1");

        // then — AC-4: config framework rejects out-of-range value
        assertThatThrownBy(builder::getOrCreateConfig).isInstanceOf(ConfigViolationException.class);
    }
}
