// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprSyncRouterTest {
    private static final ClprChannel CHANNEL =
            ClprChannel.newBuilder().channelId(Bytes.wrap(new byte[] {1})).build();
    private static final List<ClprEndpoint> ENDPOINTS = List.of(ClprEndpoint.DEFAULT);
    private static final long LOCAL_MANIFEST_VERSION = 3L;
    private static final long PEER_OBSERVED_MANIFEST_VERSION = 2L;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfig;

    @Mock
    private ClprUnarySynchronizer unarySynchronizer;

    @Mock
    private ClprStreamingSynchronizer streamingSynchronizer;

    private ClprSyncRouter subject;

    @BeforeEach
    void setUp() {
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
        subject = new ClprSyncRouter(configProvider, unarySynchronizer, streamingSynchronizer);
    }

    @Test
    @DisplayName("given streaming sync disabled, then synchronize() delegates to the unary synchronizer")
    void givenStreamingSyncDisabled_thenSynchronizeDelegatesToUnary() {
        givenStreamingSyncEnabled(false);

        synchronize();

        verify(unarySynchronizer)
                .synchronize(CHANNEL, ENDPOINTS, LOCAL_MANIFEST_VERSION, PEER_OBSERVED_MANIFEST_VERSION);
        verifyNoInteractions(streamingSynchronizer);
    }

    @Test
    @DisplayName("given streaming sync enabled, then synchronize() delegates to the streaming synchronizer")
    void givenStreamingSyncEnabled_thenSynchronizeDelegatesToStreaming() {
        givenStreamingSyncEnabled(true);

        synchronize();

        verify(streamingSynchronizer)
                .synchronize(CHANNEL, ENDPOINTS, LOCAL_MANIFEST_VERSION, PEER_OBSERVED_MANIFEST_VERSION);
        verifyNoInteractions(unarySynchronizer);
    }

    @Test
    @DisplayName("given the flag flips between two calls, when synchronize() is called again, then it follows the flag")
    void givenFlagFlipsBetweenCalls_whenSynchronizeIsCalledAgain_thenFollowsTheFlag() {
        given(versionedConfig.getConfigData(ClprConfig.class))
                .willReturn(ClprConfigBuilder.newBuilder()
                        .streamingSyncEnabled(true)
                        .build())
                .willReturn(ClprConfigBuilder.newBuilder()
                        .streamingSyncEnabled(false)
                        .build());

        synchronize();
        synchronize();

        final var order = inOrder(streamingSynchronizer, unarySynchronizer);
        order.verify(streamingSynchronizer)
                .synchronize(CHANNEL, ENDPOINTS, LOCAL_MANIFEST_VERSION, PEER_OBSERVED_MANIFEST_VERSION);
        order.verify(unarySynchronizer)
                .synchronize(CHANNEL, ENDPOINTS, LOCAL_MANIFEST_VERSION, PEER_OBSERVED_MANIFEST_VERSION);
    }

    private void givenStreamingSyncEnabled(final boolean enabled) {
        given(versionedConfig.getConfigData(ClprConfig.class))
                .willReturn(ClprConfigBuilder.newBuilder()
                        .streamingSyncEnabled(enabled)
                        .build());
    }

    private void synchronize() {
        subject.synchronize(CHANNEL, ENDPOINTS, LOCAL_MANIFEST_VERSION, PEER_OBSERVED_MANIFEST_VERSION);
    }
}
