// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.util;

import com.hedera.hapi.node.base.TokenID;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.test.util.SigReqAdapterUtils;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;

/**
 * Adds parity tests for Signature requirements from mono-service in mod-service.
 */
public class ParityTestBase {
    protected ReadableAccountStore readableAccountStore;
    protected WritableAccountStore writableAccountStore;
    protected ReadableTokenStore readableTokenStore;
    protected WritableTokenRelationStore writableTokenRelStore;
    protected TokenID token = TokenID.newBuilder().tokenNum(1).build();
    protected Configuration configuration;

    /**
     * Sets up the test environment.
     */
    @BeforeEach
    public void setUp() {
        readableAccountStore = SigReqAdapterUtils.wellKnownAccountStoreAt();
        writableAccountStore = SigReqAdapterUtils.wellKnownWritableAccountStoreAt();
        readableTokenStore = SigReqAdapterUtils.wellKnownTokenStoreAt();
        writableTokenRelStore = SigReqAdapterUtils.wellKnownTokenRelStoreAt();
        configuration = HederaTestConfigBuilder.createConfig();
    }
}
