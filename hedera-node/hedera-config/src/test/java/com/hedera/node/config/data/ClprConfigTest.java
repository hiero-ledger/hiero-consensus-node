// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClprConfigTest {

    @Test
    void enabledByDefault() {
        final var config = HederaTestConfigBuilder.createConfig().getConfigData(ClprConfig.class);

        assertThat(config.enabled()).isTrue();
    }

    @Test
    void dnsEndpointsAreDisabledUnlessExplicitlyEnabled() {
        final var defaults = HederaTestConfigBuilder.createConfig().getConfigData(ClprConfig.class);
        assertThat(defaults.publishDnsEndpoints()).isFalse();
        assertThat(defaults.allowDnsPeerEndpoints()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true"})
    void dnsPublicationAndDialingCanBeEnabledIndependently(final boolean publish, final boolean dial) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.publishDnsEndpoints", Boolean.toString(publish))
                .withValue("clpr.allowDnsPeerEndpoints", Boolean.toString(dial))
                .getOrCreateConfig()
                .getConfigData(ClprConfig.class);
        assertThat(config.publishDnsEndpoints()).isEqualTo(publish);
        assertThat(config.allowDnsPeerEndpoints()).isEqualTo(dial);
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
        // A verifyConfig call with an endpoint manifest proof carries two ~15 KB Hiero state proofs, which
        // need ~1.2M gas to cover their EIP-7623 calldata floor
        assertThat(config.verifierGasLimit()).isGreaterThanOrEqualTo(1_200_000L);
    }
}
