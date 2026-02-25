// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test.handler;

import static org.mockito.BDDMockito.given;

import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import org.hiero.interledger.clpr.impl.test.ClprTestBase;
import org.mockito.Mock;

public class ClprHandlerTestBase extends ClprTestBase {

    @Mock
    protected VersionedConfiguration configuration;

    protected void setupHandlerBase() {
        setupBase();
    }

    protected void mockDefaultClprConfig() {
        given(configuration.getConfigData(ClprConfig.class)).willReturn(defaultClprConfig());
    }

    protected ClprConfig defaultClprConfig() {
        return new ClprConfig(true, 5000, true, 5, 6144);
    }
}
