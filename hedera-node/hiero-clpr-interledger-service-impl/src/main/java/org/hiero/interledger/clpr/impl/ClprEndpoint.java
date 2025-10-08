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
    private final ConfigProvider configProvider;
    private final ClprConnectionManager connectionManager;

    /**
     * Constructs a new {@code ClprEndpoint} instance.
     *
     * <p>This constructor is intended to be invoked by dependency injection. All parameters are
     * required and must be non-null. The endpoint uses the provided {@link NetworkInfo} to
     * determine local node endpoints, the {@link ConfigProvider} to read CLPR/GRPC configuration,
     * and the {@link ClprConnectionManager} to create local clients for fetching ledger
     * configuration from remote endpoints. The constructor also accepts additional runtime
     * collaborators that are not stored on this class but are validated for non-null to ensure
     * correct wiring by dependency injection frameworks.</p>
     *
     * @param networkInfo the network information for the local node; must not be null
     * @param configProvider provider for runtime configuration (CLPR, gRPC, etc.); must not be null
     * @param executor an executor service used by the caller; validated for non-null but not stored locally
     * @param stateProofManager manager responsible for producing/consuming state proofs; validated for non-null but not stored locally
     * @param metrics metrics registry used elsewhere; validated for non-null but not stored locally
     * @param clprConnectionManager factory/manager used to create clients connecting to other CLPR endpoints; must not be null
     * @throws NullPointerException if any parameter is null
     */
    @Inject
    public ClprEndpoint(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ExecutorService executor,
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final Metrics metrics,
            @NonNull final ClprConnectionManager clprConnectionManager) {
        this.networkInfo = requireNonNull(networkInfo);
        this.configProvider = requireNonNull(configProvider);
        // Validate these collaborators to ensure DI wiring is correct. They are not
        // stored as fields on this class but must be non-null when injected.
        requireNonNull(executor);
        requireNonNull(stateProofManager);
        requireNonNull(metrics);
        this.connectionManager = requireNonNull(clprConnectionManager);
    }

    /**
     * The main work routine that runs periodically for the CLPR endpoint.
     *
     * <p>This method is invoked on a scheduled executor thread. It attempts to create a
     * temporary (adhoc) client connected to a local HAPI/CLPR endpoint and fetch the current
     * {@link org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration}. The routine logs
     * timing and outcome information. Any transient connection errors are caught and logged.
     *</p>
     *
     * <p>Important: this method performs networking and I/O and should not be called on time-
     * sensitive threads unless the caller expects blocking operations. Exceptions are caught
     * inside the method to prevent the scheduled executor from being suppressed.</p>
     */
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
        } catch (final UnknownHostException e) {
            log.warn(
                    "CLPR Endpoint Routine: Unknown host when connecting to {}: {}",
                    adhocServiceEndpoint,
                    e.getMessage(),
                    e);
            throw new RuntimeException(e);
        } catch (final Throwable t) {
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

    /**
     * Schedule the periodic execution of {@link #endpointRoutine()} using the configured
     * connection frequency.
     *
     * <p>This method reads the {@link ClprConfig#connectionFrequency()} from the configured
     * {@code ConfigProvider} and schedules {@link #endpointRoutine()} to run repeatedly at that
     * fixed interval. The returned {@link Future} is stored in {@link #routineFuture} so the
     * scheduled task can be cancelled when the endpoint is stopped.</p>
     *
     * <p>This method is private and intended to be used only by lifecycle methods of this
     * instance. It assumes the {@link #scheduler} has been created and is healthy.</p>
     */
    private void scheduleRoutineActivity() {
        // Schedule the next wake-up for the CLPR Endpoint.
        final var interval = configProvider
                .getConfiguration()
                .getConfigData(ClprConfig.class)
                .connectionFrequency();
        routineFuture = scheduler.scheduleAtFixedRate(this::endpointRoutine, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Start the CLPR endpoint and begin scheduled routine activity.
     *
     * <p>This method is thread-safe and idempotent: calling it multiple times has the same
     * effect as calling it once. If the endpoint is not already started, this method schedules
     * the periodic activity and marks the endpoint as started. Any exceptions thrown during
     * scheduling will be propagated to the caller.</p>
     */
    public synchronized void start() {
        if (!started) {
            log.info("Starting CLPR Endpoint...");
            scheduleRoutineActivity();
            started = true;
        }
    }

    /**
     * Stop the CLPR endpoint and cancel scheduled activity.
     *
     * <p>This method is thread-safe and idempotent. If the endpoint is started, the scheduled
     * periodic task (if any) will be cancelled via {@link Future#cancel(boolean)} and internal
     * state will be updated to reflect the stopped status. Cancellation is performed with
     * {@code mayInterruptIfRunning=true}, which may interrupt a currently running routine.
     *</p>
     */
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
