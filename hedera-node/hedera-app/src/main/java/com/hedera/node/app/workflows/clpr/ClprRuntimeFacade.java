// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lightweight feature gate in front of the node-local CLPR runtime.
 *
 * <p>The real runtime graph is deliberately available only through {@link Provider providers}. When
 * {@code clpr.enabled} is false, constructing this facade does not construct the channel manager,
 * its scheduler, the synchronizer and client cache, or the sync workflow. The method-level checks
 * remain defensive in case the feature is disabled after the graph has already been instantiated.
 */
@Singleton
public final class ClprRuntimeFacade implements ClprRuntime, ClprChannelLifecycle, ClprSyncWorkflow {
    private static final Logger logger = LogManager.getLogger(ClprRuntimeFacade.class);

    private final ConfigProvider configProvider;
    private final Provider<ClprChannelManager> channelManagerProvider;
    private final Provider<ClprSyncWorkflowImpl> syncWorkflowProvider;

    @Nullable
    private volatile ClprChannelManager initializedChannelManager;

    @Inject
    public ClprRuntimeFacade(
            @NonNull final ConfigProvider configProvider,
            @NonNull final Provider<ClprChannelManager> channelManagerProvider,
            @NonNull final Provider<ClprSyncWorkflowImpl> syncWorkflowProvider) {
        this.configProvider = requireNonNull(configProvider);
        this.channelManagerProvider = requireNonNull(channelManagerProvider);
        this.syncWorkflowProvider = requireNonNull(syncWorkflowProvider);
    }

    @Override
    public void start() {
        if (!isEnabled()) {
            logger.info("CLPR is disabled; runtime graph will not be instantiated");
            return;
        }
        channelManager().start();
    }

    @Override
    public void stop() {
        final var channelManager = initializedChannelManager;
        if (channelManager != null) {
            channelManager.stop();
        }
    }

    @Override
    public void onChannelActivated(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        if (isEnabled()) {
            channelManager().onChannelActivated(channelId);
        }
    }

    @Override
    public void onChannelClosed(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        if (isEnabled()) {
            channelManager().onChannelClosed(channelId);
        }
    }

    @Override
    public void seedPeerEndpoints(@NonNull final Bytes channelId, @NonNull final List<ClprEndpoint> endpoints) {
        requireNonNull(channelId);
        requireNonNull(endpoints);
        if (isEnabled()) {
            channelManager().seedPeerEndpoints(channelId, endpoints);
        }
    }

    @Override
    public void recordPeerObservedManifestVersion(@NonNull final Bytes channelId, final long peerObservedVersion) {
        requireNonNull(channelId);
        if (isEnabled()) {
            channelManager().recordPeerObservedManifestVersion(channelId, peerObservedVersion);
        }
    }

    @Override
    public void handleSync(@NonNull final Bytes requestBytes, @NonNull final BufferedData responseBuffer) {
        requireNonNull(requestBytes);
        requireNonNull(responseBuffer);
        syncWorkflow().handleSync(requestBytes, responseBuffer);
    }

    @Override
    public void handleDiscovery(@NonNull final Bytes requestBytes, @NonNull final BufferedData responseBuffer) {
        requireNonNull(requestBytes);
        requireNonNull(responseBuffer);
        syncWorkflow().handleDiscovery(requestBytes, responseBuffer);
    }

    @NonNull
    @Override
    public ClprStreamingSyncSession openStreamingSync() {
        return syncWorkflow().openStreamingSync();
    }

    @NonNull
    @Override
    public ClprStreamingSyncSession openStreamingSync(@Nullable final String correlationId) {
        return syncWorkflow().openStreamingSync(correlationId);
    }

    private boolean isEnabled() {
        return configProvider.getConfiguration().getConfigData(ClprConfig.class).enabled();
    }

    private ClprSyncWorkflow syncWorkflow() {
        if (!isEnabled()) {
            throw new StatusRuntimeException(Status.UNAVAILABLE.withDescription("CLPR is not enabled"));
        }
        // ClprSyncWorkflowImpl also depends on this manager. Resolve it here so stop() can avoid
        // calling Provider#get() (and accidentally constructing the graph) on a disabled node.
        channelManager();
        return syncWorkflowProvider.get();
    }

    private ClprChannelManager channelManager() {
        var channelManager = initializedChannelManager;
        if (channelManager == null) {
            synchronized (this) {
                channelManager = initializedChannelManager;
                if (channelManager == null) {
                    channelManager = channelManagerProvider.get();
                    initializedChannelManager = channelManager;
                }
            }
        }
        return channelManager;
    }
}
