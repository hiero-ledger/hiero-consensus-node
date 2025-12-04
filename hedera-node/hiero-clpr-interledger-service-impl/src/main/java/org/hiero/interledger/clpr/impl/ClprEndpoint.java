// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
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
     * <p>In dev mode the node first ensures a local configuration exists. If none is present it
     * submits a bootstrap configuration; otherwise it re-submits the existing configuration with a
     * freshly generated timestamp so downstream nodes observe monotonic updates. Any failures are
     * logged and swallowed so the scheduler keeps running.</p>
     */
    void runOnce() {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.clprEnabled()) {
            return;
        }
        if (!clprConfig.devModeEnabled()) {
            return;
        }

        final var selfNodeInfo = networkInfo.selfNodeInfo();
        final AccountID accountId = requireNonNull(selfNodeInfo.accountId(), "self node account ID must not be null");
        final var localEndpoint = localServiceEndpoint();

        try (final ClprClient localClient = connectionManager.createClient(localEndpoint)) {
            final var fetchedConfig = localClient.getConfiguration();

            if (fetchedConfig == null) {
                log.debug("The network does not have a ledger configuration set. ");
                final var bootstrapConfig = buildBootstrapConfiguration();
                final var status = localClient.setConfiguration(accountId, accountId, bootstrapConfig);
                log.debug(
                        "CLPR Endpoint: Bootstrapped local ledger {} for node {} (timestamp={}, status={})",
                        bootstrapConfig.ledgerIdOrThrow().ledgerId(),
                        selfNodeInfo.nodeId(),
                        formatTimestamp(bootstrapConfig.timestampOrElse(Timestamp.DEFAULT)),
                        status);
                if (status != ResponseCodeEnum.SUCCESS && status != ResponseCodeEnum.OK) {
                    log.warn(
                            "CLPR Endpoint: Bootstrap submission failed (status={}) using payer {} with {} endpoints (timestamp={}, ledger={})",
                            status,
                            accountId,
                            bootstrapConfig.endpoints().size(),
                            formatTimestamp(bootstrapConfig.timestampOrElse(Timestamp.DEFAULT)),
                            bootstrapConfig.ledgerIdOrElse(bootstrapConfig.ledgerIdOrThrow()));
                }
            } else {
                final var fetchedLedgerId = fetchedConfig.ledgerId();
                final var fetchedTimestamp = fetchedConfig.timestampOrElse(Timestamp.DEFAULT);
                log.debug(
                        "CLPR Endpoint: Retrieved local configuration for ledger {} at timestamp {}",
                        fetchedLedgerId != null ? fetchedLedgerId.ledgerId() : "<unset>",
                        formatTimestamp(fetchedTimestamp));
                final Timestamp refreshedTimestamp = timestampFromSystemNow();
                final var refreshedConfig = fetchedConfig
                        .copyBuilder()
                        .timestamp(refreshedTimestamp)
                        .build();
                final var status = localClient.setConfiguration(accountId, accountId, refreshedConfig);
                log.debug(
                        "CLPR Endpoint: Refreshed local ledger {} to timestamp {} (status={})",
                        refreshedConfig.ledgerIdOrThrow().ledgerId(),
                        formatTimestamp(refreshedConfig.timestampOrElse(Timestamp.DEFAULT)),
                        status);
                if (!(status == ResponseCodeEnum.SUCCESS || status == ResponseCodeEnum.OK)) {
                    log.warn(
                            "CLPR Endpoint: Failed to refresh local ledger {} (status={})",
                            refreshedConfig.ledgerIdOrThrow().ledgerId(),
                            status);
                }
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
            log.info("CLPR Endpoint is disabled via configuration; skipping start");
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

    /**
     * Synthesises a timestamp using the current epoch millis plus a nanosecond component. The logic
     * favours "something strictly newer than before" over absolute accuracy; it is sufficient for
     * demo bootstrap traffic.
     */
    private static Timestamp timestampFromSystemNow() {
        final long epochMillis = System.currentTimeMillis();
        long seconds = Math.floorDiv(epochMillis, 1_000L);
        long nanos = (epochMillis % 1_000L) * 1_000_000L;
        final long nanoAdjustment = Math.floorMod(System.nanoTime(), 1_000_000L);
        nanos += nanoAdjustment;
        if (nanos >= 1_000_000_000L) {
            nanos -= 1_000_000_000L;
            seconds += 1;
        }
        return Timestamp.newBuilder().seconds(seconds).nanos((int) nanos).build();
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

    private ServiceEndpoint selectEndpoint(@NonNull final NodeInfo nodeInfo) {
        final var endpoints = nodeInfo.hapiEndpoints();
        return endpoints.isEmpty() ? null : endpoints.getFirst();
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

    private ClprLedgerConfiguration buildBootstrapConfiguration() {
        final var ledgerIdBytes = Bytes.wrap(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        final List<org.hiero.hapi.interledger.state.clpr.ClprEndpoint> endpoints = new ArrayList<>();
        for (final NodeInfo nodeInfo : networkInfo.addressBook()) {
            final var endpoint = selectEndpoint(nodeInfo);
            if (endpoint != null) {
                endpoints.add(toClprEndpoint(endpoint));
            }
        }
        if (endpoints.isEmpty()) {
            endpoints.add(toClprEndpoint(localServiceEndpoint()));
        }
        final var bootstrapTimestamp =
                Timestamp.newBuilder().seconds(0).nanos(0).build();
        return ClprLedgerConfiguration.newBuilder()
                .ledgerId(org.hiero.hapi.interledger.state.clpr.ClprLedgerId.newBuilder()
                        .ledgerId(ledgerIdBytes)
                        .build())
                .timestamp(bootstrapTimestamp)
                .endpoints(endpoints)
                .build();
    }

    private org.hiero.hapi.interledger.state.clpr.ClprEndpoint toClprEndpoint(final ServiceEndpoint endpoint) {
        return org.hiero.hapi.interledger.state.clpr.ClprEndpoint.newBuilder()
                .endpoint(endpoint)
                .signingCertificate(Bytes.EMPTY)
                .build();
    }
}
