// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static io.netty.handler.ssl.SupportedCipherSuiteFilter.INSTANCE;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsRequest;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.grpc.GrpcServerManager;
import com.hedera.node.app.grpc.impl.ClprStreamingSyncMethod;
import com.hedera.node.app.grpc.impl.usage.GrpcUsageTracker;
import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.workflows.clpr.ClprChannelManager;
import com.hedera.node.app.workflows.clpr.ClprLeafCertManager;
import com.hedera.node.app.workflows.clpr.ClprLeafCredentials;
import com.hedera.node.app.workflows.clpr.ClprSyncWorkflow;
import com.hedera.node.app.workflows.clpr.mtls.ClprMtlsContexts;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.app.workflows.query.annotations.OperatorQueries;
import com.hedera.node.app.workflows.query.annotations.UserQueries;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.data.NettyConfig;
import com.hedera.node.config.types.Profile;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import javax.security.auth.x500.X500Principal;
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
     * The max transaction size in bytes supported by gRPC.
     */
    public static final int MAX_TRANSACTION_SIZE = 133120; // 130 KB

    /**
     * The set of {@link ServiceDescriptor}s for services that the gRPC server will expose
     */
    private final Set<ServerServiceDefinition> services;

    /**
     * The set of {@link ServiceDescriptor}s for services that the node operator gRPC server will expose
     */
    private Set<ServerServiceDefinition> nodeOperatorServices = Collections.emptySet();

    /**
     * The set of {@link ServiceDescriptor}s exposed on the dedicated CLPR mTLS sync listener — the CLPR
     * {@code sync} method only. Empty unless mTLS is enabled (in which case {@code sync} is also removed
     * from {@link #services}).
     */
    private Set<ServerServiceDefinition> clprSyncServices = Collections.emptySet();

    /**
     * The configuration provider, so we can figure out ports and other information.
     */
    private final ConfigProvider configProvider;

    /**
     * Provides this node's CLPR leaf cert/key and whether mTLS is enabled. Used to build the dedicated
     * mTLS sync listener's server credentials.
     */
    private final Provider<ClprLeafCertManager> clprLeafCertManager;

    /**
     * Lazily resolves the CLPR channel manager, which holds the live set of known peer CA certs the
     * inbound sync trust manager pins against. A {@link Provider} avoids any construction-order coupling
     * with the gRPC server (both are singletons started from the {@code ACTIVE} platform-status branch).
     */
    private final Provider<ClprChannelManager> clprChannelManager;
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
     * The dedicated CLPR mTLS sync gRPC server (mutual TLS, ClientAuth.REQUIRE). Started only when mTLS
     * is enabled; {@code null} otherwise.
     */
    @VisibleForTesting
    Server clprSyncServer;

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
     * @param clprSyncWorkflow The implementation of the {@link ClprSyncWorkflow} to use for CLPR sync rpc methods
     * @param clprLeafCertManager Lazily supplies this node's CLPR leaf cert/key and whether mTLS is enabled
     * @param clprChannelManager Lazily supplies the live set of known peer CA certs for inbound sync trust
     * @param metrics Used to get/create metrics for each transaction and query method.
     */
    @Inject
    public NettyGrpcServerManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final ServicesRegistry servicesRegistry,
            @NonNull final IngestWorkflow ingestWorkflow,
            @NonNull @UserQueries final QueryWorkflow userQueryWorkflow,
            @NonNull @OperatorQueries final QueryWorkflow operatorQueryWorkflow,
            @NonNull final ClprSyncWorkflow clprSyncWorkflow,
            @NonNull final Provider<ClprLeafCertManager> clprLeafCertManager,
            @NonNull final Provider<ClprChannelManager> clprChannelManager,
            @NonNull final Metrics metrics) {
        this.configProvider = requireNonNull(configProvider);
        this.clprLeafCertManager = requireNonNull(clprLeafCertManager);
        this.clprChannelManager = requireNonNull(clprChannelManager);
        requireNonNull(ingestWorkflow);
        requireNonNull(userQueryWorkflow);
        requireNonNull(operatorQueryWorkflow);
        requireNonNull(clprSyncWorkflow);
        requireNonNull(metrics);

        final Supplier<Stream<RpcServiceDefinition>> rpcServiceDefinitions =
                () -> servicesRegistry.registrations().stream()
                        .map(ServicesRegistry.Registration::service)
                        // Not all services are RPC services, but here we need RPC services only. The main difference
                        // between RPC service and a service is that the RPC service has RPC definition.
                        .filter(v -> v instanceof RpcService)
                        .map(v -> (RpcService) v)
                        .flatMap(s -> s.rpcDefinitions().stream());

        // Convert the various RPC service definitions into transaction, query, or CLPR sync endpoints
        // using the GrpcServiceBuilder. When mTLS is enabled, the CLPR sync method is served only on the
        // dedicated mTLS listener (built below), so it is removed from the shared HAPI
        // ports here.
        services = buildServiceDefinitions(
                rpcServiceDefinitions,
                m -> !servesSyncOnMtlsListener(m),
                ingestWorkflow,
                userQueryWorkflow,
                clprSyncWorkflow,
                metrics);
        clprSyncServices = buildServiceDefinitions(
                        rpcServiceDefinitions,
                        this::servesSyncOnMtlsListener,
                        ingestWorkflow,
                        userQueryWorkflow,
                        clprSyncWorkflow,
                        metrics)
                .stream()
                .filter(d -> !d.getMethods().isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        final var grpcConfig = configProvider.getConfiguration().getConfigData(GrpcConfig.class);
        if (grpcConfig.nodeOperatorPortEnabled()) {
            // Convert the various RPC service definitions into query endpoints permitting unpaid queries for node
            // operators. CLPR sync methods are not exposed on the node operator port.
            nodeOperatorServices = buildServiceDefinitions(
                    rpcServiceDefinitions,
                    m -> Query.class.equals(m.requestType()),
                    ingestWorkflow,
                    operatorQueryWorkflow,
                    null,
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

    @VisibleForTesting
    Set<ServerServiceDefinition> hapiServices() {
        return Collections.unmodifiableSet(services);
    }

    @VisibleForTesting
    Set<ServerServiceDefinition> clprSyncServices() {
        return Collections.unmodifiableSet(clprSyncServices);
    }

    /**
     * The port the dedicated CLPR mTLS sync server is listening on, or {@code -1} when it is not running —
     * i.e. mTLS is disabled or the listener failed to start. Exposed primarily for tests and diagnostics.
     */
    public int clprSyncPort() {
        return clprSyncServer == null || clprSyncServer.isTerminated() ? -1 : clprSyncServer.getPort();
    }

    /**
     * Whether a given RPC method should be served on the dedicated CLPR mTLS listener rather
     * than the shared HAPI ports — i.e. mTLS is enabled and {@code m} is the CLPR {@code sync} method.
     */
    private boolean servesSyncOnMtlsListener(@NonNull final RpcMethodDefinition<?, ?> m) {
        return isClprSyncMethod(m)
                && isClprEnabled()
                && clprLeafCertManager.get().isMtlsEnabled();
    }

    /**
     * Whether {@code m} is the CLPR {@code sync} method. A pure, config-independent fact about the method
     * (kept {@code static} so the routing split can be unit-tested directly without a manager instance).
     * Every other method — including CLPR {@code discoverEndpoints} — is not sync.
     */
    static boolean isClprSyncMethod(@NonNull final RpcMethodDefinition<?, ?> m) {
        return ClprSyncPayload.class.equals(m.requestType());
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

        // Dedicated CLPR mTLS listener: mutual TLS with ClientAuth.REQUIRE, presenting this node's
        // ephemeral Ed25519 leaf and pinning connecting clients against the known peer CA set. Started
        // only when mTLS is configured; the advertised ClprEndpoint port must point here so peers dial
        // the sync listener rather than the (sync-less) shared HAPI ports.
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (clprConfig.enabled()) {
            final var leafCertManager = clprLeafCertManager.get();
            if (leafCertManager.isMtlsEnabled()) {
                final var mtlsPort = clprConfig.mtlsPort();
                try {
                    logger.info("Starting CLPR mTLS sync gRPC server on port {}", mtlsPort);
                    nettyBuilder = builderFor(mtlsPort, nettyConfig, profile, false);
                    configureClprMtls(
                            nettyBuilder,
                            requireNonNull(leafCertManager.leafCredentials()),
                            () -> clprChannelManager.get().knownPeerCaCertificatesByIssuer());
                    clprSyncServer =
                            startServerWithRetry(clprSyncServices, nettyBuilder, startRetries, startRetryIntervalMs);
                    logger.info("CLPR mTLS sync gRPC server listening on port {}", clprSyncServer.getPort());
                } catch (final Exception e) {
                    throw new IllegalStateException(
                            "CLPR mTLS is configured but the dedicated sync listener could not start; ", e);
                }
            }
        }
    }

    private boolean isClprEnabled() {
        return configProvider.getConfiguration().getConfigData(ClprConfig.class).enabled();
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

        if (clprSyncServer != null && !clprSyncServer.isTerminated()) {
            logger.info("Shutting down CLPR mTLS sync gRPC server on port {}", clprSyncServer.getPort());
            terminateServer(clprSyncServer);
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
                    .bossEventLoopGroup(new EpollEventLoopGroup(config.bossThreads()))
                    .workerEventLoopGroup(new EpollEventLoopGroup(config.workerThreads()));
            logger.info(
                    "Using Epoll for gRPC server (boss={}, worker={})", config.bossThreads(), config.workerThreads());
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

    /**
     * Configures mutual TLS for the dedicated CLPR sync listener (clpr-spec PR #46 §3.4). The server
     * presents this node's ephemeral Ed25519 leaf from {@code leafCredentials} and requires the connecting
     * client to present a certificate ({@link ClientAuth#REQUIRE}); the client's leaf is accepted only if it
     * was signed by the peer CA whose subject DN matches the leaf's issuer DN, looked up in the index
     * currently returned by {@code peerCasByIssuer} (read live on every handshake).
     */
    private void configureClprMtls(
            @NonNull final NettyServerBuilder builder,
            @NonNull final ClprLeafCredentials leafCredentials,
            @NonNull final Supplier<Map<X500Principal, List<X509Certificate>>> peerCasByIssuer)
            throws SSLException {
        // Built on the BouncyCastle JSSE provider because the CLPR leaf is Ed25519, which SunJSSE
        // cannot use as a local TLS identity and netty-tcnative/BoringSSL rejects. See ClprMtlsContexts.
        builder.sslContext(ClprMtlsContexts.serverContext(leafCredentials, peerCasByIssuer));
    }

    private Set<ServerServiceDefinition> buildServiceDefinitions(
            @NonNull final Supplier<Stream<RpcServiceDefinition>> rpcServiceDefinitions,
            @NonNull final Predicate<RpcMethodDefinition> methodFilter,
            @NonNull final IngestWorkflow ingestWorkflow,
            @NonNull final QueryWorkflow queryWorkflow,
            @Nullable final ClprSyncWorkflow clprSyncWorkflow,
            @NonNull final Metrics metrics) {

        final int maxTxnSize = configProvider
                .getConfiguration()
                .getConfigData(HederaConfig.class)
                .transactionMaxBytes();
        final boolean isJumboEnabled = configProvider
                .getConfiguration()
                .getConfigData(JumboTransactionsConfig.class)
                .isEnabled();
        final int jumboMaxTxnSize = isJumboEnabled
                ? configProvider
                        .getConfiguration()
                        .getConfigData(JumboTransactionsConfig.class)
                        .maxTxnSize()
                : maxTxnSize;

        // set buffer capacity to be big enough to hold the largest transaction
        final var bufferCapacity = isJumboEnabled ? jumboMaxTxnSize + 1 : maxTxnSize + 1;
        // set capacity and max transaction size for both normal and jumbo transactions
        final var dataBufferMarshaller = new DataBufferMarshaller(MAX_TRANSACTION_SIZE + 1, MAX_TRANSACTION_SIZE);
        final var jumboBufferMarshaller = new DataBufferMarshaller(bufferCapacity, jumboMaxTxnSize);
        return rpcServiceDefinitions
                .get()
                .map(d -> {
                    final var containsSyncEndpoint = new AtomicBoolean(false);
                    // create builder
                    final var builder = new GrpcServiceBuilder(
                            d.basePath(),
                            ingestWorkflow,
                            queryWorkflow,
                            clprSyncWorkflow,
                            dataBufferMarshaller,
                            jumboBufferMarshaller);
                    // add methods to builder
                    d.methods().stream().filter(methodFilter).forEach(m -> {
                        if (Transaction.class.equals(m.requestType())) {
                            builder.transaction(m.path());
                        } else if (ClprSyncPayload.class.equals(m.requestType())) {
                            containsSyncEndpoint.set(true);
                            builder.clprSync(m.path());
                        } else if (ClprDiscoverEndpointsRequest.class.equals(m.requestType())) {
                            builder.clprDiscovery(m.path());
                        } else {
                            builder.query(m.path());
                        }
                    });
                    // build service
                    final var service = builder.build(metrics, configProvider);
                    if (containsSyncEndpoint.get()) {
                        return addClprStreamingSync(service, requireNonNull(clprSyncWorkflow), jumboBufferMarshaller);
                    }
                    return service;
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns {@code service} with the bidirectional-streaming CLPR {@code streamingSync} method added.
     *
     * <p>Registered by hand rather than through {@link GrpcServiceBuilder}: that path hardcodes
     * {@link MethodType#UNARY} and dispatches on request type, so a streaming method routed through it would answer
     * the first message and close with {@code OK} — a silently truncated exchange that looks healthy. It is also why
     * the method is a bare constant on {@link ClprEndpointServiceDefinition} rather than an entry in its method set,
     * which drives that same unary auto-registration.
     *
     * <p>The method is merged into the existing service definition rather than added as a second one, because a gRPC
     * server keys its registry by service name and a second definition under the same name would displace the first.
     */
    @NonNull
    static ServerServiceDefinition addClprStreamingSync(
            @NonNull final ServerServiceDefinition service,
            @NonNull final ClprSyncWorkflow clprSyncWorkflow,
            @NonNull final DataBufferMarshaller marshaller) {
        final var descriptor = MethodDescriptor.<BufferedData, BufferedData>newBuilder()
                .setType(MethodType.BIDI_STREAMING)
                .setFullMethodName(ClprEndpointServiceDefinition.STREAMING_SYNC_FULL_METHOD_NAME)
                .setRequestMarshaller(marshaller)
                .setResponseMarshaller(marshaller)
                .build();
        final var builder =
                ServerServiceDefinition.builder(service.getServiceDescriptor().getName());
        service.getMethods().forEach(builder::addMethod);
        builder.addMethod(
                descriptor, ServerCalls.asyncBidiStreamingCall(new ClprStreamingSyncMethod(clprSyncWorkflow)));
        return builder.build();
    }
}
