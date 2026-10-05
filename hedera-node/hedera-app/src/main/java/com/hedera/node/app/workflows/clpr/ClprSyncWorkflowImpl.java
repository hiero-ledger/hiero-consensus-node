// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsRequest;
import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsResponse;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server-side implementation of {@link ClprSyncWorkflow}. Opens a {@link ClprStreamingSyncSession} for each inbound
 * streaming {@code sync} call, which drives the two-phase exchange and answers {@code discoverEndpoints} requests
 * from the local endpoint cache.
 *
 * <p>Proof construction is performed by {@link ClprStateProofManager}; TSS signature
 * verification is Phase 2. Inbound bundle submission is implemented via {@link ClprBundleSubmitter}.
 */
@Singleton
public final class ClprSyncWorkflowImpl implements ClprSyncWorkflow {
    private static final Logger logger = LogManager.getLogger(ClprSyncWorkflowImpl.class);

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
        return new ClprStreamingSyncSession(stateAccessor, bundleSubmitter, stateProofManager, correlationId);
    }

    @Override
    public void handleDiscovery(@NonNull final Bytes requestBytes, @NonNull final BufferedData responseBuffer) {
        requireNonNull(requestBytes);
        requireNonNull(responseBuffer);

        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            throw new StatusRuntimeException(Status.UNAVAILABLE.withDescription("CLPR is not enabled"));
        }

        // Parse the discovery request
        final ClprDiscoverEndpointsRequest request;
        try {
            request = ClprDiscoverEndpointsRequest.PROTOBUF.parseStrict(requestBytes);
        } catch (final Exception e) {
            logger.warn("Failed to parse ClprDiscoverEndpointsRequest", e);
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("Invalid discovery request: " + e.getMessage()));
        }

        final var channelId = request.channelId();
        if (channelId.length() != 32) {
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("channel_id must be exactly 32 bytes"));
        }

        // Validate the channel exists in state
        try (final var wrappedState = stateAccessor.get()) {
            final var state = wrappedState.get();
            final var storeFactory = new ReadableStoreFactoryImpl(state);
            final var channelStore = storeFactory.readableStore(ReadableChannelStore.class);
            final var channel = channelStore.getChannel(channelId);
            if (channel == null) {
                throw new StatusRuntimeException(Status.NOT_FOUND.withDescription("Channel not found: " + channelId));
            }
        }

        // Return known endpoints from the local cache
        final var endpoints = channelManager.getKnownEndpoints(channelId);
        final var response =
                ClprDiscoverEndpointsResponse.newBuilder().endpoints(endpoints).build();

        final var responseBytes = ClprDiscoverEndpointsResponse.PROTOBUF.toBytes(response);
        responseBuffer.writeBytes(responseBytes);

        logger.debug("Handled CLPR discovery for channel {}, returned {} endpoints", channelId, endpoints.size());
    }
}
