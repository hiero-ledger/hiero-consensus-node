// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprRuntimeFacadeTest {

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration configuration;

    @Mock
    private ClprConfig clprConfig;

    private Provider<ClprChannelManager> channelManagerProvider;

    private Provider<ClprSyncWorkflowImpl> syncWorkflowProvider;

    private AtomicInteger channelManagerProviderCalls;
    private AtomicInteger syncWorkflowProviderCalls;

    @Mock
    private ClprChannelManager channelManager;

    @Mock
    private ClprSyncWorkflowImpl syncWorkflow;

    private ClprRuntimeFacade subject;

    @BeforeEach
    void setUp() {
        given(configProvider.getConfiguration()).willReturn(configuration);
        given(configuration.getConfigData(ClprConfig.class)).willReturn(clprConfig);
        channelManagerProviderCalls = new AtomicInteger();
        syncWorkflowProviderCalls = new AtomicInteger();
        channelManagerProvider = () -> {
            channelManagerProviderCalls.incrementAndGet();
            return channelManager;
        };
        syncWorkflowProvider = () -> {
            syncWorkflowProviderCalls.incrementAndGet();
            return syncWorkflow;
        };
        subject = new ClprRuntimeFacade(configProvider, channelManagerProvider, syncWorkflowProvider);
    }

    @Test
    void disabledFeatureDoesNotInstantiateRuntimeGraph() {
        given(clprConfig.enabled()).willReturn(false);

        subject.start();
        subject.stop();
        subject.onChannelActivated(Bytes.EMPTY);
        subject.onChannelClosed(Bytes.EMPTY);
        subject.seedPeerEndpoints(Bytes.EMPTY, List.of());
        subject.recordPeerObservedManifestVersion(Bytes.EMPTY, 1L);

        assertThatThrownBy(() -> subject.handleSync(Bytes.EMPTY, BufferedData.allocate(1)))
                .isInstanceOf(StatusRuntimeException.class);
        assertThatThrownBy(subject::openStreamingSync).isInstanceOf(StatusRuntimeException.class);
        assertThat(channelManagerProviderCalls).hasValue(0);
        assertThat(syncWorkflowProviderCalls).hasValue(0);
    }

    @Test
    void enabledFeatureLazilyInstantiatesAndDelegatesToRuntimeGraph() {
        given(clprConfig.enabled()).willReturn(true);
        final var request = Bytes.wrap(new byte[] {1});
        final var response = BufferedData.allocate(1);

        subject.start();
        subject.handleSync(request, response);
        subject.stop();

        assertThat(channelManagerProviderCalls).hasValue(1);
        verify(channelManager).start();
        assertThat(syncWorkflowProviderCalls).hasValue(1);
        verify(syncWorkflow).handleSync(request, response);
        verify(channelManager).stop();
    }
}
