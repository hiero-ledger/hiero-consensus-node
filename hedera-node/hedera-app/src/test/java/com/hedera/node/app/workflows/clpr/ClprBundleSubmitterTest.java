// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprBundleSubmitterTest {

    private static final AccountID SELF_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3).build();
    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final Bytes BUNDLE_PAYLOAD = Bytes.wrap(new byte[] {1, 2, 3, 4});
    private static final long SELF_NODE_ID = 7L;
    private static final long ONE_HBAR_IN_TINYBARS = 100_000_000L;

    @Mock
    private AppContext appContext;

    @Mock
    private AppContext.Gossip gossip;

    @Mock
    private NodeInfo selfNodeInfo;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfig;

    @Mock
    private Configuration configuration;

    @Mock
    private HederaConfig hederaConfig;

    @Mock
    private ClprConfig clprConfig;

    private ClprBundleSubmitter subject;

    @BeforeEach
    void setUp() {
        lenient().when(appContext.gossip()).thenReturn(gossip);
        lenient().when(gossip.isAvailable()).thenReturn(true);
        final Supplier<NodeInfo> selfNodeSupplier = () -> selfNodeInfo;
        lenient().when(appContext.selfNodeInfoSupplier()).thenReturn(selfNodeSupplier);
        lenient().when(selfNodeInfo.accountId()).thenReturn(SELF_ACCOUNT_ID);
        lenient().when(selfNodeInfo.nodeId()).thenReturn(SELF_NODE_ID);
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfig);
        lenient().when(versionedConfig.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
        lenient().when(versionedConfig.getConfigData(ClprConfig.class)).thenReturn(clprConfig);
        lenient().when(hederaConfig.transactionMaxValidDuration()).thenReturn(180L);
        lenient().when(clprConfig.enabled()).thenReturn(true);
        lenient().when(clprConfig.nodeSubmitBundleMaxFee()).thenReturn(100_000_000L);
        subject = new ClprBundleSubmitter(appContext, configProvider);
    }

    @Test
    void skipsSubmissionWhenClprIsDisabled() {
        when(clprConfig.enabled()).thenReturn(false);
        final var payload = ClprSyncPayload.newBuilder()
                .channelId(CHANNEL_ID)
                .bundlePayload(BUNDLE_PAYLOAD)
                .build();

        assertThat(subject.submitBundle(payload)).isFalse();
        verify(gossip, never()).submit(any());
    }

    @Test
    void submitsBundleSuccessfully() {
        final var payload = ClprSyncPayload.newBuilder()
                .channelId(CHANNEL_ID)
                .bundlePayload(BUNDLE_PAYLOAD)
                .build();

        final var result = subject.submitBundle(payload);

        assertThat(result).isTrue();

        final var txBodyCaptor = ArgumentCaptor.forClass(TransactionBody.class);
        verify(gossip).submit(txBodyCaptor.capture());

        final var txBody = txBodyCaptor.getValue();
        assertThat(txBody.hasClprSubmitBundle()).isTrue();
        final var clprOp = txBody.clprSubmitBundleOrThrow();
        assertThat(clprOp.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(clprOp.bundlePayload()).isEqualTo(BUNDLE_PAYLOAD);
        assertThat(clprOp.endpointNodeId()).isEqualTo(SELF_NODE_ID);
        assertThat(txBody.transactionIDOrThrow().accountID()).isEqualTo(SELF_ACCOUNT_ID);
        assertThat(txBody.nodeAccountID()).isEqualTo(SELF_ACCOUNT_ID);
        assertThat(txBody.transactionFee()).isEqualTo(ONE_HBAR_IN_TINYBARS);
    }

    @Test
    void skipsEmptyBundlePayload() {
        final var payload = ClprSyncPayload.newBuilder()
                .channelId(CHANNEL_ID)
                .bundlePayload(Bytes.EMPTY)
                .build();

        final var result = subject.submitBundle(payload);

        assertThat(result).isFalse();
        verify(gossip, never()).submit(any());
    }

    @Test
    void returnsFalseWhenGossipUnavailable() {
        when(gossip.isAvailable()).thenReturn(false);

        final var payload = ClprSyncPayload.newBuilder()
                .channelId(CHANNEL_ID)
                .bundlePayload(BUNDLE_PAYLOAD)
                .build();

        final var result = subject.submitBundle(payload);

        assertThat(result).isFalse();
        verify(gossip, never()).submit(any());
    }

    @Test
    void returnsFalseWhenSubmitThrowsIllegalArgument() {
        Mockito.doThrow(new IllegalArgumentException("rejected")).when(gossip).submit(any());

        final var payload = ClprSyncPayload.newBuilder()
                .channelId(CHANNEL_ID)
                .bundlePayload(BUNDLE_PAYLOAD)
                .build();

        final var result = subject.submitBundle(payload);

        assertThat(result).isFalse();
    }
}
