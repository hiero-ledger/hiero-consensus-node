// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClprConfigTest {
    @Test
    void queueDepthCeilingMatchesBundledDefault() {
        assertThat(HederaTestConfigBuilder.createConfig()
                        .getConfigData(ClprConfig.class)
                        .maxQueueDepthCeiling())
                .isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void queueDepthCeilingMustBePositive(final int ceiling) {
        final var builder = HederaTestConfigBuilder.create().withValue("clpr.maxQueueDepthCeiling", ceiling);
        assertThatThrownBy(builder::getOrCreateConfig).isInstanceOf(ConfigViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10_000, Integer.MAX_VALUE})
    void positiveQueueDepthCeilingsAreAccepted(final int ceiling) {
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("clpr.maxQueueDepthCeiling", ceiling)
                .getOrCreateConfig();
        assertThat(configuration.getConfigData(ClprConfig.class).maxQueueDepthCeiling())
                .isEqualTo(ceiling);
    }

    @Test
    void enabledByDefault() {
        final var config = HederaTestConfigBuilder.createConfig().getConfigData(ClprConfig.class);

        assertThat(config.enabled()).isTrue();
    }

    @Test
    void additionalPeerEndpointCidrsAreEmptyByDefault() {
        final var config = HederaTestConfigBuilder.createConfig().getConfigData(ClprConfig.class);

        assertThat(config.additionalPeerEndpointCidrs()).isEmpty();
    }

    @Test
    void additionalPeerEndpointCidrsCanBeConfigured() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.additionalPeerEndpointCidrs", "127.0.0.1/32,10.0.0.0/24,fd00:1234::/64")
                .getOrCreateConfig()
                .getConfigData(ClprConfig.class);

        assertThat(config.additionalPeerEndpointCidrs())
                .containsExactlyInAnyOrder("127.0.0.1/32", "10.0.0.0/24", "fd00:1234::/64");
    }

    @Test
    void verifierGasLimitCoversCalldataFloorOfHieroStateProofs() {
        final var config = HederaTestConfigBuilder.createConfig().getConfigData(ClprConfig.class);
        // EIP-7623 charges at most 40 gas per calldata byte, plus base gas and verifier execution.
        assertThat(config.verifierGasLimit()).isGreaterThan(21_000L + 40L * (128 * 1024));
    }
}
