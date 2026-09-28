// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Chooses between the different implementation of the synchronization protocol: unary or streaming. This is the client
 * side of the protocol.
 *
 * <p>Routes each outbound sync cycle to {@link ClprStreamingSynchronizer} when {@code clpr.streamingSyncEnabled} is
 * set,
 * and to {@link ClprUnarySynchronizer} otherwise.
 *
 * <p>The flag is read on every call rather than once at wiring time, so flipping it at runtime takes effect on the
 * next cycle, which means a bad rollout can be reversed without a restart.
 */
@Singleton
public final class ClprSyncRouter implements ClprSynchronizer {

    private static final Logger logger = LogManager.getLogger(ClprSyncRouter.class);

    private final ConfigProvider configProvider;
    private final ClprUnarySynchronizer unarySynchronizer;
    private final ClprStreamingSynchronizer streamingSynchronizer;

    /**
     * The mode the previous cycle ran in, or {@code null} before the first cycle; cycles run concurrently, so it is
     * swapped atomically.
     */
    private final AtomicReference<Boolean> lastStreamingSyncEnabled = new AtomicReference<>();

    /**
     * @param configProvider        source of the live {@link ClprConfig}
     * @param unarySynchronizer     handles the cycle when streaming sync is disabled
     * @param streamingSynchronizer handles the cycle when streaming sync is enabled
     */
    @Inject
    public ClprSyncRouter(
            @NonNull final ConfigProvider configProvider,
            @NonNull final ClprUnarySynchronizer unarySynchronizer,
            @NonNull final ClprStreamingSynchronizer streamingSynchronizer) {
        this.configProvider = requireNonNull(configProvider);
        this.unarySynchronizer = requireNonNull(unarySynchronizer);
        this.streamingSynchronizer = requireNonNull(streamingSynchronizer);
    }

    @Override
    public void synchronize(
            @NonNull final ClprChannel channel,
            @NonNull final List<ClprEndpoint> providedEndpoints,
            final long localEndpointManifestVersion,
            final long peerObservedManifestVersion) {
        final boolean streamingSyncEnabled = configProvider
                .getConfiguration()
                .getConfigData(ClprConfig.class)
                .streamingSyncEnabled();
        final Boolean previous = lastStreamingSyncEnabled.getAndSet(streamingSyncEnabled);
        if (previous == null || previous != streamingSyncEnabled) {
            logger.info(
                    "[CLPR-SYNC-OUTBOUND] outbound sync mode is now {}", streamingSyncEnabled ? "streaming" : "unary");
        }
        final ClprSynchronizer synchronizer = streamingSyncEnabled ? streamingSynchronizer : unarySynchronizer;
        synchronizer.synchronize(channel, providedEndpoints, localEndpointManifestVersion, peerObservedManifestVersion);
    }
}
