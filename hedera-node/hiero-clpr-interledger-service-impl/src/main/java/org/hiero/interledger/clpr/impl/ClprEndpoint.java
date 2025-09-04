// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
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
    private final ForkJoinPool forkJoinPool;
    private final ConfigProvider configProvider;
    private final ClprStateProofManager stateProofManager;
    private final ClprConnectionManager connectionManager;
    private final Metrics metrics;

    @Inject
    public ClprEndpoint(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ExecutorService executor,
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final Metrics metrics,
            @NonNull final ClprConnectionManager clprConnectionManager) {
        this.networkInfo = requireNonNull(networkInfo);
        this.forkJoinPool = (ForkJoinPool) requireNonNull(executor);
        this.configProvider = requireNonNull(configProvider);
        this.stateProofManager = requireNonNull(stateProofManager);
        this.metrics = requireNonNull(metrics);
        this.connectionManager = requireNonNull(clprConnectionManager);
    }

    private void endpointRoutine() {
        final var start = System.nanoTime();
        System.out.println("CLPR Endpoint Routine starting...");
        final var selfInfo = networkInfo.selfNodeInfo();
        final var hapiEndpoints = selfInfo.hapiEndpoints();

        ClprLedgerConfiguration ledgerConfig = null;
        final var adhocServiceEndpoint = ServiceEndpoint.newBuilder()
                .ipAddressV4(selfInfo.gossipEndpoints().getFirst().ipAddressV4())
                .port(configProvider
                        .getConfiguration()
                        .getConfigData(GrpcConfig.class)
                        .port())
                .build();
        try (final var client = connectionManager.createClient(adhocServiceEndpoint)) {
            System.out.println("CLPR Endpoint Routine: Connecting to " + adhocServiceEndpoint);
            ledgerConfig = client.getConfiguration();
        } catch (UnknownHostException e) {
            System.out.println(e);
            throw new RuntimeException(e);
        } catch (Throwable t) {
            System.err.println("CLPR Endpoint Routine: Failed to connect to " + adhocServiceEndpoint);
            System.err.println("CLPR Endpoint Routine: Error: " + t.getMessage());
        }

        final var end = System.nanoTime();
        final var duration = Duration.ofNanos(end - start);
        System.out.println("CLPR Endpoint Routine done in " + duration + " ns.");
        if (ledgerConfig == null) {
            System.out.printf("CLPR Endpoint Routine: No ledger configuration found at %s%n", hapiEndpoints.getFirst());
        } else {
            System.out.printf(
                    "CLPR Endpoint Routine: Ledger configuration found at %s: %s%n",
                    hapiEndpoints.getFirst(), ledgerConfig);
        }
    }

    private void scheduleRoutineActivity() {
        // Schedule the next wake-up for the CLPR Endpoint.
        final var interval = configProvider
                .getConfiguration()
                .getConfigData(ClprConfig.class)
                .connectionFrequency();
        routineFuture = scheduler.scheduleAtFixedRate(this::endpointRoutine, interval, interval, TimeUnit.MILLISECONDS);
    }

    public synchronized void start() {
        if (!started) {
            log.info("Starting CLPR Endpoint...");
            scheduleRoutineActivity();
            started = true;
        }
    }

    public synchronized void stop() {
        if (started) {
            log.info("Stopping CLPR Endpoint...");
            // TODO: decide if we want to cancel the scheduled task or let it finish
            routineFuture.cancel(true);
            started = false;
            routineFuture = null;
        }
    }
}
