// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.junit.jupiter.api.Test;

class ConfigDataSupportTest {

    @Test
    void testConfig() {
        // given
        var builder = HederaTestConfigBuilder.create().withConfigDataType(ApiPermissionConfig.class);

        // then
        assertThatNoException().isThrownBy(builder::getOrCreateConfig);
    }

    @Test
    void testConfigRecordsAvailable() {
        // given
        var config = HederaTestConfigBuilder.create()
                .withConfigDataType(ApiPermissionConfig.class)
                .getOrCreateConfig();

        // when
        ApiPermissionConfig configData = config.getConfigData(ApiPermissionConfig.class);

        // then
        assertThat(configData).isNotNull();
    }
}
