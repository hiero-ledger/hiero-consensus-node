// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.ClprConfigBuilder;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers {@link ClprSyncWorkflowImpl#openStreamingSync()} — the admission point for a streaming sync stream.
 *
 * <p>Admission lives here rather than in the session so it is decided once per stream instead of once per message,
 * and so the peer is rejected before it writes anything. That makes this the only place the CLPR-enabled flag is
 * consulted on the streaming path, and the only place it can be tested directly.
 */
@ExtendWith(MockitoExtension.class)
class ClprSyncWorkflowImplTest {

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfig;

    @Mock
    private Supplier<AutoCloseableWrapper<State>> stateAccessor;

    @Mock
    private ClprBundleSubmitter bundleSubmitter;

    @Mock
    private ClprChannelManager connectionManager;

    @Mock
    private ClprStateProofManager stateProofManager;

    @Nested
    class OpenStreamingSync {

        @Test
        @DisplayName("creates new session when CLPR is enabled")
        void opensSessionWhenEnabled() {
            givenClprEnabled(true);
            final var subject = newWorkflow();

            final var first = subject.openStreamingSync();
            final var second = subject.openStreamingSync();

            assertThat(first).isNotNull();
            // One stream is one sync cycle, so every call must hand back its own state machine — a shared instance
            // would carry one stream's connection ID and bundle cursor into the next.
            assertThat(second).isNotNull().isNotSameAs(first);
        }

        @Test
        @DisplayName("rejects as UNAVAILABLE when CLPR is disabled")
        void rejectsWhenClprDisabled() {
            givenClprEnabled(false);

            assertThatThrownBy(() -> newWorkflow().openStreamingSync())
                    .isInstanceOf(StatusRuntimeException.class)
                    .hasMessageContaining("CLPR is not enabled")
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE);
        }

        @Test
        @DisplayName("the flag is read per call, so disabling it takes effect on the next stream")
        void readsTheFlagOnEveryCall() {
            // ClprConfig arrives via ConfigProvider, which serves a new VersionedConfiguration after a
            // fileUpdate(APP_PROPERTIES). Caching the flag at construction would leave a disabled node still
            // accepting streams until restart.
            given(configProvider.getConfiguration()).willReturn(versionedConfig);
            given(versionedConfig.getConfigData(ClprConfig.class))
                    .willReturn(ClprConfigBuilder.newBuilder().enabled(true).build())
                    .willReturn(ClprConfigBuilder.newBuilder().enabled(false).build());
            final var subject = newWorkflow();

            assertThat(subject.openStreamingSync()).isNotNull();
            assertThatThrownBy(subject::openStreamingSync).isInstanceOf(StatusRuntimeException.class);
        }
    }

    private void givenClprEnabled(final boolean enabled) {
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
        given(versionedConfig.getConfigData(ClprConfig.class))
                .willReturn(ClprConfigBuilder.newBuilder().enabled(enabled).build());
    }

    private ClprSyncWorkflowImpl newWorkflow() {
        return new ClprSyncWorkflowImpl(
                configProvider, stateAccessor, bundleSubmitter, connectionManager, stateProofManager);
    }
}
