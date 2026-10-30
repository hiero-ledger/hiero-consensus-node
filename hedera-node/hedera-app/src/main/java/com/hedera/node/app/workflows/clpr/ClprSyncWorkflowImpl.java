// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Server-side implementation of {@link ClprSyncWorkflow}. Opens a {@link ClprStreamingSyncSession} for each inbound
 * streaming {@code sync} call, which drives the two-phase exchange.
 *
 * <p>Proof construction is performed by {@link ClprStateProofManager}; TSS signature
 * verification is Phase 2. Inbound bundle submission is implemented via {@link ClprBundleSubmitter}.
 */
@Singleton
public final class ClprSyncWorkflowImpl implements ClprSyncWorkflow {
    private final ConfigProvider configProvider;
    private final Supplier<AutoCloseableWrapper<State>> stateAccessor;
    private final ClprBundleSubmitter bundleSubmitter;
    private final ClprChannelManager channelManager;
    private final ClprStateProofManager stateProofManager;

    @Inject
    public ClprSyncWorkflowImpl(
            @NonNull final ConfigProvider configProvider,
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final ClprBundleSubmitter bundleSubmitter,
            @NonNull final ClprChannelManager channelManager,
            @NonNull final ClprStateProofManager stateProofManager) {
        this.configProvider = requireNonNull(configProvider);
        this.stateAccessor = requireNonNull(stateAccessor);
        this.bundleSubmitter = requireNonNull(bundleSubmitter);
        this.channelManager = requireNonNull(channelManager);
        this.stateProofManager = requireNonNull(stateProofManager);
    }

    @Override
    @NonNull
    public ClprStreamingSyncSession openStreamingSync() {
        return openStreamingSync(null);
    }

    @Override
    @NonNull
    public ClprStreamingSyncSession openStreamingSync(@Nullable final String correlationId) {
        if (!configProvider.getConfiguration().getConfigData(ClprConfig.class).enabled()) {
            throw new StatusRuntimeException(Status.UNAVAILABLE.withDescription("CLPR is not enabled"));
        }
        return new ClprStreamingSyncSession(
                stateAccessor,
                bundleSubmitter,
                stateProofManager,
                channelManager::peerObservedManifestVersion,
                correlationId);
    }
}
