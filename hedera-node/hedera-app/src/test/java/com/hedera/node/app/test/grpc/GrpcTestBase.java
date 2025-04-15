// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.test.grpc;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.node.app.grpc.impl.netty.NettyGrpcServerManager;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.data.NettyConfig;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.test.fixtures.TestBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.assertj.core.api.Assumptions;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;

/**
 * Base class for testing the gRPC handling engine. This implementation is not suitable for general integration testing,
 * but is tailored for testing the gRPC engine itself. Specifically, it does not use real workflow implementations, but
 * allows subclasses to mock them instead to test various failure scenarios.
 *
 * <p>Our use of gRPC deals in bytes -- we do not ask the gRPC system to serialize and deserialize
 * our protobuf objects. Because of this, we *can* actually test using any type of byte[] payload (including strings!)
 * rather than protobuf objects.
 */
abstract class GrpcTestBase extends TestBase {
    /** Used as a dependency to the {@link Metrics} system. */
    private static final ScheduledExecutorService METRIC_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    /** A built-in {@link IngestWorkflow} which succeeds and does nothing. */
    protected static final IngestWorkflow NOOP_INGEST_WORKFLOW = (requestBuffer, responseBuffer) -> {};
    /** A built-in {@link QueryWorkflow} which succeeds and does nothing. */
    protected static final QueryWorkflow NOOP_QUERY_WORKFLOW = (requestBuffer, responseBuffer) -> {};

    /**
     * Represents "this node" in our tests.
     */
    private final NodeId nodeSelfId = NodeId.of(7);

    /**
     * This {@link NettyGrpcServerManager} is used to handle the wire protocol tasks and delegate to our gRPC handlers
     */
    private NettyGrpcServerManager grpcServer;

    private final Configuration configuration = ConfigurationBuilder.create()
            .withConfigDataType(JumboTransactionsConfig.class)
            .withConfigDataType(MetricsConfig.class)
            .build();

    /**
     * The gRPC system has extensive metrics. This object allows us to inspect them and make sure they are being set
     * correctly for different types of calls.
     */
    protected final Metrics metrics = new DefaultPlatformMetrics(
            nodeSelfId,
            new MetricKeyRegistry(),
            METRIC_EXECUTOR,
            new PlatformMetricsFactoryImpl(configuration.getConfigData(MetricsConfig.class)),
            configuration.getConfigData(MetricsConfig.class));
    /** The query method to set up on the server. Only one method supported today */
    private String queryMethodName;
    /** The ingest method to set up on the server. Only one method supported today */
    private String ingestMethodName;
    /** The ingest workflow to use. */
    private IngestWorkflow ingestWorkflow = NOOP_INGEST_WORKFLOW;
    /** The query workflow to use. */
    private QueryWorkflow userQueryWorkflow = NOOP_QUERY_WORKFLOW;

    private QueryWorkflow operatorQueryWorkflow = NOOP_QUERY_WORKFLOW;
    /** The channel on the client to connect to the grpc server */
    private Channel channel;
    /** The channel on the client to connect to the node operator grpc server */
    private Channel nodeOperatorChannel;

    /**
     */
    protected void registerQuery(
            @NonNull final String methodName,
            @NonNull final IngestWorkflow ingestWorkflow,
            @NonNull final QueryWorkflow userQueryWorkflow,
            @NonNull final QueryWorkflow operatorQueryWorkflow) {
        this.queryMethodName = methodName;
        this.userQueryWorkflow = userQueryWorkflow;
        this.operatorQueryWorkflow = operatorQueryWorkflow;
        this.ingestWorkflow = ingestWorkflow;
    }

    protected void registerIngest(
            @NonNull final String methodName,
            @NonNull final IngestWorkflow ingestWorkflow,
            @NonNull final QueryWorkflow userQueryWorkflow,
            @NonNull final QueryWorkflow operatorQueryWorkflow) {
        this.ingestMethodName = methodName;
        this.ingestWorkflow = ingestWorkflow;
        this.userQueryWorkflow = userQueryWorkflow;
        this.operatorQueryWorkflow = operatorQueryWorkflow;
    }

    /** Starts the grpcServer and sets up the clients. */
    protected void startServer(boolean withNodeOperatorPort) {
        final var testService = new RpcService() {
            @NonNull
            @Override
            public String getServiceName() {
                return "TestService";
            }

            @NonNull
            @Override
            public Set<RpcServiceDefinition> rpcDefinitions() {
                return Set.of(new RpcServiceDefinition() {
                    @NonNull
                    @Override
                    public String basePath() {
                        return "proto.TestService";
                    }

                    @NonNull
                    @Override
                    public Set<RpcMethodDefinition<?, ?>> methods() {
                        final var set = new HashSet<RpcMethodDefinition<?, ?>>();
                        if (queryMethodName != null) {
                            set.add(new RpcMethodDefinition<>(queryMethodName, Query.class, Response.class));
                        }
                        if (ingestMethodName != null) {
                            set.add(new RpcMethodDefinition<>(
                                    ingestMethodName, Transaction.class, TransactionResponse.class));
                        }
                        return set;
                    }
                });
            }

            @Override
            public void registerSchemas(@NonNull SchemaRegistry registry) {
                // no-op
            }
        };

        final var servicesRegistry = new ServicesRegistryImpl(ConstructableRegistry.getInstance(), configuration);
        servicesRegistry.register(testService);
        final var config = createConfig(new TestSource().withNodeOperatorPortEnabled(withNodeOperatorPort));
        this.grpcServer = new NettyGrpcServerManager(
                () -> new VersionedConfigImpl(config, 1),
                servicesRegistry,
                ingestWorkflow,
                userQueryWorkflow,
                operatorQueryWorkflow,
                metrics);

        grpcServer.start();

        this.channel = NettyChannelBuilder.forAddress("localhost", grpcServer.port())
                .usePlaintext()
                .build();

        this.nodeOperatorChannel = NettyChannelBuilder.forAddress("localhost", grpcServer.nodeOperatorPort())
                .usePlaintext()
                .build();
    }

    @AfterEach
    void tearDown() {
        if (this.grpcServer != null) this.grpcServer.stop();
        grpcServer = null;
        ingestWorkflow = NOOP_INGEST_WORKFLOW;
        userQueryWorkflow = NOOP_QUERY_WORKFLOW;
        operatorQueryWorkflow = NOOP_QUERY_WORKFLOW;
        queryMethodName = null;
        ingestMethodName = null;
    }

    /**
     * Called to invoke a service's function that had been previously registered with one of the {@code register}
     * methods, using the given payload and receiving the given response. Since the gRPC code only deals in bytes, we
     * can test everything with just strings, no protobuf encoding required.
     *
     * @param service  The service to invoke
     * @param function The function on the service to invoke
     * @param payload  The payload to send to the function on the service
     * @return The response from the service function.
     */
    protected String send(final String service, final String function, final String payload) {
        return ClientCalls.blockingUnaryCall(
                channel,
                MethodDescriptor.<String, String>newBuilder()
                        .setFullMethodName(service + "/" + function)
                        .setRequestMarshaller(new StringMarshaller())
                        .setResponseMarshaller(new StringMarshaller())
                        .setType(MethodType.UNARY)
                        .build(),
                CallOptions.DEFAULT,
                payload);
    }

    /**
     * Sends a request as a node operator using the specified service and function.
     *
     * This method constructs a unary call to the specified service and function,
     * marshaling the request and response as strings. It blocks until the call is
     * completed and returns the response payload.
     *
     * @param service the name of the service to call
     * @param function the name of the function to invoke within the service
     * @param payload the request payload to send
     * @return the response payload received from the service
     */
    protected String sendAsNodeOperator(final String service, final String function, final String payload) {
        return ClientCalls.blockingUnaryCall(
                nodeOperatorChannel,
                MethodDescriptor.<String, String>newBuilder()
                        .setFullMethodName(service + "/" + function)
                        .setRequestMarshaller(new StringMarshaller())
                        .setResponseMarshaller(new StringMarshaller())
                        .setType(MethodType.UNARY)
                        .build(),
                CallOptions.DEFAULT,
                payload);
    }

    protected Configuration createConfig(@NonNull final TestSource testConfig) {
        return ConfigurationBuilder.create()
                .withConfigDataType(MetricsConfig.class)
                .withConfigDataType(GrpcConfig.class)
                .withConfigDataType(NettyConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(JumboTransactionsConfig.class)
                .withSource(testConfig)
                .build();
    }

    /**
     * Checks whether a server process is listening on the given port
     *
     * @param portNumber The port to check
     */
    protected static boolean isListening(int portNumber) {
        try (final var socket = new Socket("localhost", portNumber)) {
            return socket.isConnected();
        } catch (ConnectException connect) {
            return false;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unexpected error while checking whether the port '" + portNumber + "' was free", e);
        }
    }

    /**
     * A config source used by this test to specify the config values
     */
    protected static final class TestSource implements ConfigSource {
        private int port = 0;
        private int tlsPort = 0;
        private int startRetries = 3;
        private int startRetryIntervalMs = 100;
        private boolean nodeOperatorPortEnabled = false;

        @Override
        public int getOrdinal() {
            return 1000;
        }

        @NonNull
        @Override
        public Set<String> getPropertyNames() {
            return Set.of(
                    "grpc.port",
                    "grpc.tlsPort",
                    "grpc.nodeOperatorPortEnabled",
                    "netty.startRetryIntervalMs",
                    "netty.startRetries");
        }

        @Nullable
        @Override
        public String getValue(@NonNull String s) throws NoSuchElementException {
            return switch (s) {
                case "grpc.port" -> String.valueOf(port);
                case "grpc.nodeOperatorPortEnabled" -> String.valueOf(nodeOperatorPortEnabled);
                case "grpc.tlsPort" -> String.valueOf(tlsPort);
                case "netty.startRetryIntervalMs" -> String.valueOf(startRetryIntervalMs);
                case "netty.startRetries" -> String.valueOf(startRetries);
                default -> null;
            };
        }

        @Override
        public boolean isListProperty(@NonNull final String propertyName) throws NoSuchElementException {
            return false;
        }

        @NonNull
        @Override
        public List<String> getListValue(@NonNull final String propertyName) throws NoSuchElementException {
            return List.of();
        }

        public int port() {
            return port;
        }

        public TestSource withPort(final int value) {
            this.port = value;
            return this;
        }

        /**
         * Sets the flag indicating whether the node operator port is enabled.
         *
         * @param value true to enable the node operator port; false to disable it
         * @return the current instance of TestSource
         */
        public TestSource withNodeOperatorPortEnabled(boolean value) {
            this.nodeOperatorPortEnabled = value;
            return this;
        }

        // Locates a free port on its own
        public TestSource withFreePort() {
            this.port = findFreePort();
            Assumptions.assumeThat(this.port).isGreaterThan(0);
            return this;
        }

        public TestSource withTlsPort(final int value) {
            this.tlsPort = value;
            return this;
        }

        // Locates a free port on its own
        public TestSource withFreeTlsPort() {
            this.tlsPort = findFreePort();
            Assumptions.assumeThat(this.tlsPort).isGreaterThan(0);
            return this;
        }

        public TestSource withStartRetries(final int value) {
            this.startRetries = value;
            return this;
        }

        public TestSource withStartRetryIntervalMs(final int value) {
            this.startRetryIntervalMs = value;
            return this;
        }

        private int findFreePort() {
            for (int i = 1024; i < 10_000; i++) {
                if (i != port && i != tlsPort && isPortFree(i)) {
                    return i;
                }
            }

            return -1;
        }

        /**
         * Checks whether the given port is free
         *
         * @param portNumber The port to check
         */
        private boolean isPortFree(int portNumber) {
            return !isListening(portNumber);
        }
    }

    protected static final class StringMarshaller implements Marshaller<String> {

        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
