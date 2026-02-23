// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TssSubmissionsTest {
    @Mock
    private Executor executor;

    @Mock
    private AppContext appContext;

    @Mock
    private AppContext.Gossip gossip;

    @Mock
    private NodeInfo selfNodeInfo;

    @Mock
    private Consumer<TransactionBody.Builder> spec;

    @Mock
    private BiConsumer<TransactionBody, String> onFailure;

    private TssSubmissions subject;

    @BeforeEach
    void setUp() {
        subject = new TssSubmissions(executor, appContext);
    }

    @Test
    void submitsAsExpectedWithActiveGossip() {
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);
        given(gossip.isAvailable()).willReturn(true);
        final var adminConfig = DEFAULT_CONFIG.getConfigData(NetworkAdminConfig.class);
        final var hederaConfig = DEFAULT_CONFIG.getConfigData(HederaConfig.class);
        subject.submitIfActive(spec, onFailure);

        verify(gossip)
                .submitFuture(
                        AccountID.DEFAULT,
                        Instant.EPOCH,
                        Duration.of(hederaConfig.transactionMaxValidDuration(), SECONDS),
                        spec,
                        executor,
                        adminConfig.timesToTrySubmission(),
                        adminConfig.distinctTxnIdsToTry(),
                        adminConfig.retryDelay(),
                        onFailure);
    }

    @Test
    void submitsNothingIfGossipNotAvailable() {
        given(appContext.gossip()).willReturn(gossip);
        final var adminConfig = DEFAULT_CONFIG.getConfigData(NetworkAdminConfig.class);
        final var hederaConfig = DEFAULT_CONFIG.getConfigData(HederaConfig.class);
        subject.submitIfActive(spec, onFailure);
        verify(gossip, never())
                .submitFuture(
                        AccountID.DEFAULT,
                        Instant.EPOCH,
                        Duration.of(hederaConfig.transactionMaxValidDuration(), SECONDS),
                        spec,
                        executor,
                        adminConfig.timesToTrySubmission(),
                        adminConfig.distinctTxnIdsToTry(),
                        adminConfig.retryDelay(),
                        onFailure);
    }
}
