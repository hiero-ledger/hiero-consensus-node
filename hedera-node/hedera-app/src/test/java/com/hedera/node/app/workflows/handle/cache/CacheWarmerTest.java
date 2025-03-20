// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.HederaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CacheWarmerTest {

    @Mock
    TransactionChecker checker;

    @Mock
    TransactionDispatcher dispatcher;

    @Mock
    ConfigProvider configProvider;

    @Mock
    VersionedConfiguration versionedConfiguration;

    @Mock
    HederaConfig hederaConfig;

    @Test
    @DisplayName("Instantiation test")
    void testInstantiation() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);

        final var cacheWarmer =
                new CacheWarmer(checker, dispatcher, Runnable::run, ServicesSoftwareVersion::new, configProvider);
        assertThat(cacheWarmer).isInstanceOf(CacheWarmer.class);
    }
}
