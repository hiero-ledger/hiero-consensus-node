// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;

class ClprConfigTest {

    @Test
    void disabledByDefault() {
        final var config = HederaTestConfigBuilder.createConfig().getConfigData(ClprConfig.class);

        assertThat(config.enabled()).isFalse();
    }

    @Test
    void verifierGasLimitCoversCalldataFloorOfHieroStateProofs() {
        final var config = HederaTestConfigBuilder.createConfig().getConfigData(ClprConfig.class);
        // A verifyConfig call with an endpoint manifest proof carries two ~15 KB Hiero state proofs, which
        // need ~1.2M gas to cover their EIP-7623 calldata floor
        assertThat(config.verifierGasLimit()).isGreaterThanOrEqualTo(1_200_000L);
    }
}
