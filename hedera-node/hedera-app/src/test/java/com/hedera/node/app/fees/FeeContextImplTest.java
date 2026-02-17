// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
import com.hedera.node.app.throttle.SynchronizedThrottleAccumulator;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.swirlds.config.api.Configuration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeeContextImplTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private TransactionInfo txInfo;

    @Mock
    private FeeManager feeManager;

    @Mock
    private ReadableStoreFactory storeFactory;

    @Mock
    private Configuration configuration;

    @Mock
    private Authorizer authorizer;

    @Mock
    private TransactionDispatcher transactionDispatcher;

    @Mock
    private SynchronizedThrottleAccumulator frontendThrottle;

    private FeeContextImpl subject;

    @BeforeEach
    void setUp() {
        given(txInfo.functionality()).willReturn(CRYPTO_CREATE);
        subject = new FeeContextImpl(
                NOW,
                txInfo,
                Key.DEFAULT,
                AccountID.DEFAULT,
                feeManager,
                storeFactory,
                configuration,
                authorizer,
                0,
                transactionDispatcher,
                frontendThrottle);
    }

    @Test
    void delegatesHighVolumeThrottleUtilization() {
        given(frontendThrottle.getHighVolumeThrottleUtilization(CRYPTO_CREATE)).willReturn(3_333);

        assertEquals(3_333, subject.getHighVolumeThrottleUtilization(CRYPTO_CREATE));
        verify(frontendThrottle).getHighVolumeThrottleUtilization(CRYPTO_CREATE);
    }
}
