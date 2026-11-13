// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.ClprServiceApi;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.node.app.spi.fees.NodeFeeAccumulator;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides instances of {@link ClprServiceApi} scoped to the CLPR service's writable state.
 */
public class ClprServiceApiProvider implements ServiceApiProvider<ClprServiceApi> {

    /**
     * A provider for contexts with no sync orchestrator, such as standalone transaction dispatch.
     */
    public static final ClprServiceApiProvider CLPR_SERVICE_API_PROVIDER =
            new ClprServiceApiProvider(ClprChannelLifecycle.NO_OP);

    private final ClprChannelLifecycle channelLifecycle;

    /**
     * Creates a provider whose API instances tell {@code channelLifecycle} about the Channels they send on.
     *
     * @param channelLifecycle the node's Channel lifecycle
     */
    public ClprServiceApiProvider(@NonNull final ClprChannelLifecycle channelLifecycle) {
        this.channelLifecycle = requireNonNull(channelLifecycle);
    }

    @Override
    public String serviceName() {
        return ClprService.NAME;
    }

    @Override
    public ClprServiceApi newInstance(
            @NonNull final Configuration configuration,
            @NonNull final WritableStates writableStates,
            @NonNull final WritableEntityCounters entityCounters,
            @NonNull final NodeFeeAccumulator nodeFeeAccumulator) {
        requireNonNull(configuration);
        requireNonNull(writableStates);
        final var channelReadStore = new ReadableChannelStoreImpl(writableStates);
        final var channelWriteStore = new WritableChannelStore(writableStates);
        final var connectorStore = new WritableConnectorStore(writableStates);
        final var configStore = new ReadableLedgerConfigurationStoreImpl(writableStates);
        final var messageQueueStore = new WritableMessageQueueStore(writableStates);
        return new ClprServiceApiImpl(
                channelReadStore,
                channelWriteStore,
                connectorStore,
                configStore,
                messageQueueStore,
                configuration,
                channelLifecycle);
    }
}
