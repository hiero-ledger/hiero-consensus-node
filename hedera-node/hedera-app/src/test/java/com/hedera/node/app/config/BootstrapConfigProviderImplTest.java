// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.config;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.config.data.FeesConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootstrapConfigProviderImplTest {
    @Test
    void canOverrideBootstrapPropertiesViaConstructor() {
        final int defaultMultiplier =
                DEFAULT_CONFIG.getConfigData(FeesConfig.class).tokenTransferUsageMultiplier();
        final var bootstrapConfigProvider = new BootstrapConfigProviderImpl(
                Map.of("fees.tokenTransferUsageMultiplier", "" + (defaultMultiplier + 1)));
        final var bootstrapConfig = bootstrapConfigProvider.getConfiguration();
        final int multiplier = bootstrapConfig.getConfigData(FeesConfig.class).tokenTransferUsageMultiplier();
        assertNotEquals(defaultMultiplier, multiplier);
    }
}
