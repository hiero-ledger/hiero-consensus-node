// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.interledger.clpr.client.ClprClient;
import org.hiero.interledger.clpr.impl.client.ClprConnectionManager;

/**
 * A CLPR Endpoint is responsible for connecting to remote CLPR Endpoints and exchanging state proofs with them.
 */
@Singleton
public class ClprEndpoint {
    private static final Logger log = LogManager.getLogger(ClprEndpoint.class);

    private boolean started = false;
    private final @NonNull ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            getStaticThreadManager().createThreadFactory("clpr", "EndpointManager"));
    private Future<?> routineFuture;

    private final NetworkInfo networkInfo;
    private final ConfigProvider configProvider;
    private final ClprConnectionManager connectionManager;
    private final Clock clock;

    @Inject
    public ClprEndpoint(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ExecutorService executor,
            @NonNull final Metrics metrics,
            @NonNull final ClprConnectionManager clprConnectionManager) {
        this(networkInfo, configProvider, clprConnectionManager, Clock.systemUTC());
        requireNonNull(executor);
        requireNonNull(metrics);
    }

    ClprEndpoint(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ClprConnectionManager clprConnectionManager,
            @NonNull final Clock clock) {
        this.networkInfo = requireNonNull(networkInfo);
        this.configProvider = requireNonNull(configProvider);
        this.connectionManager = requireNonNull(clprConnectionManager);
        this.clock = requireNonNull(clock);
    }

    /**
     * Performs a single maintenance cycle for the endpoint.
     *
     * <p>In dev mode this endpoint only observes the local configuration. Bootstrap and refresh are
     * now handled by system transactions at startup; if no configuration is present we simply log
     * and wait.</p>
     */
    void runOnce() {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.clprEnabled()) {
            log.debug("CLPR endpoint maintenance skipped; feature disabled");
            return;
        }

        final var selfNodeInfo = networkInfo.selfNodeInfo();
        final var localEndpoint = localServiceEndpoint();

        try (final ClprClient localClient = connectionManager.createClient(localEndpoint)) {
            final var fetchedConfig = localClient.getConfiguration();

            if (fetchedConfig == null) {
                log.debug(
                        "CLPR Endpoint: local configuration not yet available for node {}; awaiting system bootstrap",
                        selfNodeInfo.nodeId());
            } else {
                final var fetchedLedgerId = fetchedConfig.ledgerId();
                final var fetchedTimestamp = fetchedConfig.timestampOrElse(Timestamp.DEFAULT);
                log.debug(
                        "CLPR Endpoint: Retrieved local configuration for ledger {} at timestamp {}",
                        fetchedLedgerId != null ? fetchedLedgerId.ledgerId() : "<unset>",
                        formatTimestamp(fetchedTimestamp));
            }
        } catch (final UnknownHostException e) {
            log.warn("CLPR Endpoint: Unable to resolve local endpoint {}", localEndpoint, e);
        } catch (final Exception e) {
            log.warn("CLPR Endpoint: Failed to fetch local configuration via client", e);
        }
    }

    private void scheduleRoutineActivity() {
        final var interval = configProvider
                .getConfiguration()
                .getConfigData(ClprConfig.class)
                .connectionFrequency();
        routineFuture = scheduler.scheduleAtFixedRate(this::runOnce, interval, interval, TimeUnit.MILLISECONDS);
    }

    public synchronized void start() {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.clprEnabled()) {
            log.info("CLPR is disabled, ClprEndpoint Client not started.");
            return;
        }
        if (!started) {
            log.info("Starting CLPR Endpoint...");
            scheduleRoutineActivity();
            started = true;
        }
    }

    public synchronized void stop() {
        if (started) {
            log.info("Stopping CLPR Endpoint...");
            if (routineFuture != null) {
                routineFuture.cancel(true);
            }
            started = false;
            routineFuture = null;
        }
    }

    private static String formatTimestamp(@Nullable final Timestamp timestamp) {
        if (timestamp == null) {
            return "<none>";
        }
        final long seconds = timestamp.seconds();
        final int nanos = timestamp.nanos();
        try {
            final Instant instant = Instant.ofEpochSecond(seconds, nanos);
            return "%d.%09d (%s)".formatted(seconds, nanos, instant);
        } catch (final DateTimeException e) {
            return "%d.%09d".formatted(seconds, nanos);
        }
    }

    private ServiceEndpoint localServiceEndpoint() {
        final var grpcConfig = configProvider.getConfiguration().getConfigData(GrpcConfig.class);
        // Transactions are only accepted on the primary gRPC port; the node-operator port supports queries only.
        final byte[] loopback = new byte[] {127, 0, 0, 1};
        final int port = grpcConfig.port();
        return ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(loopback))
                .port(port)
                .build();
    }
}
