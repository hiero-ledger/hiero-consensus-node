// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static io.netty.handler.ssl.SupportedCipherSuiteFilter.INSTANCE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.grpc.GrpcServerManager;
import com.hedera.node.app.grpc.impl.usage.GrpcUsageTracker;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.app.workflows.query.annotations.OperatorQueries;
import com.hedera.node.app.workflows.query.annotations.UserQueries;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.data.NettyConfig;
import com.hedera.node.config.types.Profile;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An implementation of {@link GrpcServerManager} based on Helidon gRPC.
 *
 * <p>This implementation uses two different ports for gRPC and gRPC+TLS. If the TLS server cannot be started, then
 * a warning is logged, but we continue to function without TLS. This is useful during testing and local development
 * where TLS may not be available.
 */
@Singleton
public final class NettyGrpcServerManager implements GrpcServerManager {
    /**
     * The logger instance for this class.
     */
    private static final Logger logger = LogManager.getLogger(NettyGrpcServerManager.class);
    /**
     * The supported ciphers for TLS
     */
    private static final List<String> SUPPORTED_CIPHERS = List.of(
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_AES_256_GCM_SHA384");
    /**
     * The supported protocols for TLS
     */
    private static final List<String> SUPPORTED_PROTOCOLS = List.of("TLSv1.2", "TLSv1.3");

    /**
     * The set of {@link ServiceDescriptor}s for services that the gRPC server will expose
     */
    private final Set<ServerServiceDefinition> services;

    /**
     * The set of {@link ServiceDescriptor}s for services that the node operator gRPC server will expose
     */
    private Set<ServerServiceDefinition> nodeOperatorServices = Collections.emptySet();

    /**
     * The configuration provider, so we can figure out ports and other information.
     */
    private final ConfigProvider configProvider;
    /**
     * The gRPC server listening on the plain (non-tls) port
     */
    private Server plainServer;
    /**
     * The gRPC server listening on the plain TLS port
     */
    private Server tlsServer;

    /**
     * The node operator gRPC server listening on localhost port
     */
    private Server nodeOperatorServer;

    /**
     * Utility to collect and periodically log gRPC usage data.
     */
    private final GrpcUsageTracker usageTracker;

    /**
     * Create a new instance.
     *
     * @param configProvider The config provider, so we can figure out ports and other information.
     * @param servicesRegistry The set of all services registered with the system
     * @param ingestWorkflow The implementation of the {@link IngestWorkflow} to use for transaction rpc methods
     * @param userQueryWorkflow The implementation of the {@link QueryWorkflow} to use for user query rpc methods
     * @param operatorQueryWorkflow The implementation of the {@link QueryWorkflow} to use for node operator query rpc methods
     * @param metrics Used to get/create metrics for each transaction and query method.
     */
    @Inject
    public NettyGrpcServerManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final ServicesRegistry servicesRegistry,
            @NonNull final IngestWorkflow ingestWorkflow,
            @NonNull @UserQueries final QueryWorkflow userQueryWorkflow,
            @NonNull @OperatorQueries final QueryWorkflow operatorQueryWorkflow,
            @NonNull final Metrics metrics) {
        this.configProvider = requireNonNull(configProvider);
        requireNonNull(ingestWorkflow);
        requireNonNull(userQueryWorkflow);
        requireNonNull(operatorQueryWorkflow);
        requireNonNull(metrics);

        final Supplier<Stream<RpcServiceDefinition>> rpcServiceDefinitions =
                () -> servicesRegistry.registrations().stream()
                        .map(ServicesRegistry.Registration::service)
                        // Not all services are RPC services, but here we need RPC services only. The main difference
                        // between RPC service and a service is that the RPC service has RPC definition.
                        .filter(v -> v instanceof RpcService)
                        .map(v -> (RpcService) v)
                        .flatMap(s -> s.rpcDefinitions().stream());

        // Convert the various RPC service definitions into transaction or query endpoints using the
        // GrpcServiceBuilder.
        services =
                buildServiceDefinitions(rpcServiceDefinitions, m -> true, ingestWorkflow, userQueryWorkflow, metrics);

        final var grpcConfig = configProvider.getConfiguration().getConfigData(GrpcConfig.class);
        if (grpcConfig.nodeOperatorPortEnabled()) {
            // Convert the various RPC service definitions into query endpoints permitting unpaid queries for node
            // operators
            nodeOperatorServices = buildServiceDefinitions(
                    rpcServiceDefinitions,
                    m -> Query.class.equals(m.requestType()),
                    ingestWorkflow,
                    operatorQueryWorkflow,
                    metrics);
        }

        usageTracker = new GrpcUsageTracker(configProvider);
    }

    @Override
    public int port() {
        return plainServer == null || plainServer.isTerminated() ? -1 : plainServer.getPort();
    }

    @Override
    public int tlsPort() {
        return tlsServer == null ? -1 : tlsServer.getPort();
    }

    @Override
    public int nodeOperatorPort() {
        return nodeOperatorServer == null || nodeOperatorServer.isTerminated() ? -1 : nodeOperatorServer.getPort();
    }

    @Override
    public boolean isRunning() {
        return plainServer != null && !plainServer.isShutdown();
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            logger.error("Cannot start gRPC servers, they have already been started!");
            throw new IllegalStateException("Server already started");
        }

        logger.info("Starting gRPC servers");
        final var nettyConfig = configProvider.getConfiguration().getConfigData(NettyConfig.class);
        final var startRetries = nettyConfig.startRetries();
        final var startRetryIntervalMs = nettyConfig.startRetryIntervalMs();
        final var grpcConfig = configProvider.getConfiguration().getConfigData(GrpcConfig.class);
        final var port = grpcConfig.port();
        final var profile = configProvider
                .getConfiguration()
                .getConfigData(HederaConfig.class)
                .activeProfile();

        // Start the plain-port server
        logger.info("Starting gRPC server on port {}", port);
        var nettyBuilder = builderFor(port, nettyConfig, profile, false);
        plainServer = startServerWithRetry(services, nettyBuilder, startRetries, startRetryIntervalMs);
        logger.info("gRPC server listening on port {}", plainServer.getPort());

        // Try to start the server listening on the tls port. If this doesn't start, then we just keep going. We should
        // rethink whether we want to have two ports per consensus node like this. We do expose both via the proxies,
        // but we could have either TLS or non-TLS only on the node itself and have the proxy manage making a TLS
        // connection or terminating it, as appropriate. But for now, we support both, with the TLS port being optional.
        try {
            final var tlsPort = grpcConfig.tlsPort();
            logger.info("Starting TLS gRPC server on port {}", tlsPort);
            nettyBuilder = builderFor(tlsPort, nettyConfig, profile, false);
            configureTls(nettyBuilder, nettyConfig);
            tlsServer = startServerWithRetry(services, nettyBuilder, startRetries, startRetryIntervalMs);
            logger.info("TLS gRPC server listening on port {}", tlsServer.getPort());
        } catch (SSLException | FileNotFoundException e) {
            tlsServer = null;
            logger.warn("Could not start TLS server, will continue without it: {}", e.getMessage());
        }

        if (grpcConfig.nodeOperatorPortEnabled()) {
            try {
                final var nodeOperatorPort = grpcConfig.nodeOperatorPort();
                logger.info("Starting node operator gRPC server on port {}", nodeOperatorPort);
                nettyBuilder = builderFor(nodeOperatorPort, nettyConfig, profile, true);
                nodeOperatorServer =
                        startServerWithRetry(nodeOperatorServices, nettyBuilder, startRetries, startRetryIntervalMs);
                logger.info("Node operator gRPC server listening on port {}", nodeOperatorServer.getPort());
            } catch (Exception e) {
                nodeOperatorServer = null;
                logger.warn("Could not start node operator gRPC server, will continue without it: {}", e.getMessage());
            }
        }
    }

    @Override
    public synchronized void stop() {
        // Do not attempt to shut down if we have already done so
        if (plainServer != null && !plainServer.isTerminated()) {
            logger.info("Shutting down gRPC server on port {}", plainServer.getPort());
            terminateServer(plainServer);
        } else {
            logger.info("Cannot shut down an already stopped gRPC server");
        }

        if (tlsServer != null && !tlsServer.isTerminated()) {
            logger.info("Shutting down TLS gRPC server on port {}", tlsServer.getPort());
            terminateServer(tlsServer);
        } else {
            logger.info("Cannot shut down an already stopped gRPC server");
        }

        if (nodeOperatorServer != null && !nodeOperatorServer.isTerminated()) {
            logger.info("Shutting down node operator gRPC server on port {}", nodeOperatorServer.getPort());
            terminateServer(nodeOperatorServer);
        } else {
            logger.info("Cannot shut down an already stopped node operator gRPC server");
        }
    }

    /**
     * Attempts to start the server. It will retry {@code startRetries} times until it finally gives up with
     * {@code startRetryIntervalMs} between attempts.
     *
     * @param serviceDefinitions The service definitions to register with the server
     * @param nettyBuilder The builder used to create the server to start
     * @param startRetries The number of times to retry, if needed. Non-negative (enforced by config).
     * @param startRetryIntervalMs The time interval between retries. Positive (enforced by config).
     */
    Server startServerWithRetry(
            @NonNull final Iterable<ServerServiceDefinition> serviceDefinitions,
            @NonNull final NettyServerBuilder nettyBuilder,
            final int startRetries,
            final long startRetryIntervalMs) {
        requireNonNull(serviceDefinitions);
        requireNonNull(nettyBuilder);

        // Setup the GRPC Routing, such that all grpc services are registered
        serviceDefinitions.forEach(nettyBuilder::addService);
        final var server = nettyBuilder.build();

        var remaining = startRetries;
        while (remaining > 0) {
            try {
                server.start();
                return server;
            } catch (IOException e) {
                remaining--;
                if (remaining == 0) {
                    throw new RuntimeException("Failed to start gRPC server");
                }
                logger.info("Still trying to start server... {} tries remaining", remaining, e);

                // Wait a bit before retrying. In the FUTURE we should consider removing this functionality, it isn't
                // clear that it is actually helpful, and it complicates the code. But for now we will keep it so as
                // to remain as compatible as we can with previous non-modular releases.
                try {
                    Thread.sleep(startRetryIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting to retry server start", ie);
                }
            }
        }

        throw new RuntimeException("Failed to start gRPC server");
    }

    /**
     * Terminates the given server
     *
     * @param server the server to terminate
     */
    private void terminateServer(@Nullable final Server server) {
        if (server == null) {
            return;
        }

        final var nettyConfig = configProvider.getConfiguration().getConfigData(NettyConfig.class);
        final var terminationTimeout = nettyConfig.terminationTimeout();

        try {
            server.shutdownNow();
            server.awaitTermination(terminationTimeout, TimeUnit.SECONDS);
            logger.info("gRPC server stopped");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for gRPC to terminate!", ie);
        } catch (Exception e) {
            logger.warn("Exception while waiting for gRPC to terminate!", e);
        }
    }

    /**
     * Utility for setting up various shared configuration settings for all servers
     */
    private NettyServerBuilder builderFor(
            final int port,
            @NonNull final NettyConfig config,
            @NonNull final Profile activeProfile,
            boolean localHostOnly) {
        NettyServerBuilder builder = null;
        try {
            builder = withConfigForActiveProfile(getInitialServerBuilder(port, localHostOnly), config, activeProfile)
                    .channelType(EpollServerSocketChannel.class)
                    .bossEventLoopGroup(new EpollEventLoopGroup())
                    .workerEventLoopGroup(new EpollEventLoopGroup());
            logger.info("Using Epoll for gRPC server");
        } catch (final UnsatisfiedLinkError | NoClassDefFoundError ignored) {
            // If we can't use Epoll, then just use NIO
            logger.info("Epoll not available, using NIO");
            builder = withConfigForActiveProfile(getInitialServerBuilder(port, localHostOnly), config, activeProfile);
        } catch (final Exception unexpected) {
            logger.info("Unexpected exception initializing Netty", unexpected);
        }

        if (builder != null) {
            // attach logging interceptor
            builder.intercept(usageTracker);
        }

        return builder;
    }

    private static @NonNull NettyServerBuilder getInitialServerBuilder(int port, boolean localHostOnly) {
        if (localHostOnly) {
            return NettyServerBuilder.forAddress(new InetSocketAddress("localhost", port));
        }

        return NettyServerBuilder.forPort(port);
    }

    private NettyServerBuilder withConfigForActiveProfile(
            @NonNull final NettyServerBuilder builder,
            @NonNull final NettyConfig config,
            @NonNull final Profile activeProfile) {
        if (activeProfile != Profile.DEV) {
            builder.keepAliveTime(config.prodKeepAliveTime(), TimeUnit.SECONDS)
                    .permitKeepAliveTime(config.prodKeepAliveTime(), TimeUnit.SECONDS)
                    .keepAliveTimeout(config.prodKeepAliveTimeout(), TimeUnit.SECONDS)
                    .maxConnectionAge(config.prodMaxConnectionAge(), TimeUnit.SECONDS)
                    .maxConnectionAgeGrace(config.prodMaxConnectionAgeGrace(), TimeUnit.SECONDS)
                    .maxConnectionIdle(config.prodMaxConnectionIdle(), TimeUnit.SECONDS)
                    .maxConcurrentCallsPerConnection(config.prodMaxConcurrentCalls())
                    .flowControlWindow(config.prodFlowControlWindow());
        }
        return builder.directExecutor();
    }

    /**
     * Utility for setting up TLS configuration
     */
    private void configureTls(final NettyServerBuilder builder, NettyConfig config)
            throws SSLException, FileNotFoundException {
        final var tlsCrtPath = config.tlsCrtPath();
        final var crt = new File(tlsCrtPath);
        if (!crt.exists()) {
            logger.warn("Specified TLS cert '{}' doesn't exist!", tlsCrtPath);
            throw new FileNotFoundException(tlsCrtPath);
        }

        final var tlsKeyPath = config.tlsKeyPath();
        final var key = new File(tlsKeyPath);
        if (!key.exists()) {
            logger.warn("Specified TLS key '{}' doesn't exist!", tlsKeyPath);
            throw new FileNotFoundException(tlsKeyPath);
        }

        final var sslContext = GrpcSslContexts.configure(SslContextBuilder.forServer(crt, key))
                .protocols(SUPPORTED_PROTOCOLS)
                .ciphers(SUPPORTED_CIPHERS, INSTANCE)
                .build();

        builder.sslContext(sslContext);
    }

    private Set<ServerServiceDefinition> buildServiceDefinitions(
            @NonNull final Supplier<Stream<RpcServiceDefinition>> rpcServiceDefinitions,
            @NonNull final Predicate<RpcMethodDefinition> methodFilter,
            @NonNull final IngestWorkflow ingestWorkflow,
            @NonNull final QueryWorkflow queryWorkflow,
            @NonNull final Metrics metrics) {

        final var maxTxnSize = configProvider
                .getConfiguration()
                .getConfigData(HederaConfig.class)
                .transactionMaxBytes();
        final var isJumboEnabled = configProvider
                .getConfiguration()
                .getConfigData(JumboTransactionsConfig.class)
                .isEnabled();
        final var jumboMaxTxnSize = isJumboEnabled
                ? configProvider
                        .getConfiguration()
                        .getConfigData(JumboTransactionsConfig.class)
                        .maxTxnSize()
                : maxTxnSize;

        // set buffer capacity to be big enough to hold the largest transaction
        final var bufferCapacity = isJumboEnabled ? jumboMaxTxnSize + 1 : maxTxnSize + 1;
        // set capacity and max transaction size for both normal and jumbo transactions
        final var dataBufferMarshaller = new DataBufferMarshaller(bufferCapacity, maxTxnSize);
        final var jumboBufferMarshaller = new DataBufferMarshaller(bufferCapacity, jumboMaxTxnSize);
        return rpcServiceDefinitions
                .get()
                .map(d -> {
                    // create builder
                    final var builder = new GrpcServiceBuilder(
                            d.basePath(), ingestWorkflow, queryWorkflow, dataBufferMarshaller, jumboBufferMarshaller);
                    // add methods to builder
                    d.methods().stream().filter(methodFilter).forEach(m -> {
                        if (Transaction.class.equals(m.requestType())) {
                            builder.transaction(m.path());
                        } else {
                            builder.query(m.path());
                        }
                    });
                    // build service
                    return builder.build(metrics, configProvider);
                })
                .collect(Collectors.toUnmodifiableSet());
    }
}
