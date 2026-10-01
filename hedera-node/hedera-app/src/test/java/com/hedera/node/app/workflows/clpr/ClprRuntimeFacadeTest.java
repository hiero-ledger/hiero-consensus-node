// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

        assertAllInboundCallsDisabled(subject);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultOrExplicitlyDisabledConfigurationNeverConstructsRuntime(final boolean explicitFalse) {
        final var builder = HederaTestConfigBuilder.create();
        if (explicitFalse) {
            builder.withValue("clpr.enabled", false);
        }
        given(configuration.getConfigData(ClprConfig.class))
                .willReturn(builder.getOrCreateConfig().getConfigData(ClprConfig.class));

        subject.start();
        subject.onChannelActivated(Bytes.EMPTY);
        subject.seedPeerEndpoints(Bytes.EMPTY, List.of());
        assertAllInboundCallsDisabled(subject);
        subject.stop();

        assertThat(channelManagerProviderCalls).hasValue(0);
        assertThat(syncWorkflowProviderCalls).hasValue(0);
        verifyNoInteractions(channelManager, syncWorkflow);
    }

    @Test
    void disablingAnInitializedRuntimeRejectsNewWorkAndStillAllowsShutdown() {
        given(clprConfig.enabled()).willReturn(true);
        subject.start();
        subject.handleSync(Bytes.EMPTY, BufferedData.allocate(1));
        clearInvocations(channelManager, syncWorkflow);

        given(clprConfig.enabled()).willReturn(false);
        subject.start();
        subject.onChannelActivated(Bytes.EMPTY);
        subject.onChannelClosed(Bytes.EMPTY);
        subject.seedPeerEndpoints(Bytes.EMPTY, List.of());
        subject.recordPeerObservedManifestVersion(Bytes.EMPTY, 1L);
        assertAllInboundCallsDisabled(subject);

        verifyNoInteractions(channelManager, syncWorkflow);
        assertThat(channelManagerProviderCalls).hasValue(1);
        assertThat(syncWorkflowProviderCalls).hasValue(1);
        subject.stop();
        verify(channelManager).stop();
    }

    @Test
    void restartingWithDisabledConfigurationDoesNotRecreatePreviousRuntime() {
        given(clprConfig.enabled()).willReturn(true);
        subject.start();
        subject.stop();
        clearInvocations(channelManager, syncWorkflow);

        given(clprConfig.enabled()).willReturn(false);
        final var restarted = new ClprRuntimeFacade(configProvider, channelManagerProvider, syncWorkflowProvider);
        restarted.start();
        assertAllInboundCallsDisabled(restarted);
        restarted.stop();

        assertThat(channelManagerProviderCalls).hasValue(1);
        assertThat(syncWorkflowProviderCalls).hasValue(0);
        verifyNoInteractions(channelManager, syncWorkflow);
    }

    private static void assertAllInboundCallsDisabled(final ClprRuntimeFacade runtime) {
        final List<Runnable> calls = List.of(
                () -> runtime.handleSync(Bytes.EMPTY, BufferedData.allocate(1)),
                () -> runtime.handleDiscovery(Bytes.EMPTY, BufferedData.allocate(1)),
                () -> runtime.openStreamingSync(),
                () -> runtime.openStreamingSync("disabled-test"));
        for (final var call : calls) {
            final var error = assertThrows(StatusRuntimeException.class, call::run);
            assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
            assertEquals("CLPR is not enabled", error.getStatus().getDescription());
        }
    }
}
