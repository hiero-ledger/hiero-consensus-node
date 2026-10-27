// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.workflows.clpr.ClprStreamingSyncSession;
import com.hedera.node.app.workflows.clpr.ClprSyncWorkflow;
import com.hedera.node.app.workflows.clpr.ClprTestTlsCert;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.constructable.ConstructableRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class NettyGrpcServerManagerTest {

    private static final String SYNC_FULL_METHOD_NAME = ClprEndpointServiceDefinition.SERVICE_NAME + "/sync";

    private ConfigProvider configProvider;
    private ServicesRegistry services;
    private IngestWorkflow ingestWorkflow;
    private QueryWorkflow userQueryWorkflow;
    private QueryWorkflow operatorQueryWorkflow;
    private ClprSyncWorkflow clprSyncWorkflow;
    private Metrics metrics;

    @BeforeEach
    void setUp(@Mock @NonNull final Metrics metrics) {
        final var config = HederaTestConfigBuilder.createConfig();

        this.configProvider = () -> new VersionedConfigImpl(config, 1);
        this.metrics = metrics;
        this.services =
                new ServicesRegistryImpl(ConstructableRegistry.getInstance(), config); // An empty set of services
        this.ingestWorkflow = (req, res) -> {};
        this.userQueryWorkflow = (req, res) -> {};
        this.operatorQueryWorkflow = (req, res) -> {};
        this.clprSyncWorkflow = new ClprSyncWorkflow() {

            @Override
            public ClprStreamingSyncSession openStreamingSync() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    @DisplayName("Null arguments are not allowed")
    @SuppressWarnings("DataFlowIssue")
    void nullArgsThrow() {
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        null,
                        services,
                        ingestWorkflow,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        null,
                        ingestWorkflow,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        null,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        ingestWorkflow,
                        null,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider, services, ingestWorkflow, userQueryWorkflow, null, clprSyncWorkflow, metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        ingestWorkflow,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        null,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        ingestWorkflow,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Ports are -1 when not started")
    void portsAreMinusOneWhenNotStarted() {
        final var subject = new NettyGrpcServerManager(
                configProvider,
                services,
                ingestWorkflow,
                userQueryWorkflow,
                operatorQueryWorkflow,
                clprSyncWorkflow,
                metrics);
        assertThat(subject.port()).isEqualTo(-1);
        assertThat(subject.tlsPort()).isEqualTo(-1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("given CLPR enabled or disabled, then sync is served only as a bidirectional stream on the HAPI ports")
    void givenClprEnabledOrDisabled_thenSyncIsServedOnlyAsABidirectionalStreamOnTheHapiPorts(final boolean enabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", enabled)
                .getOrCreateConfig();

        final var subject = managerWithClprEndpointService(() -> new VersionedConfigImpl(config, 1));

        // Exactly one registration: the bidi stream, never a unary sync alongside it.
        assertThat(syncMethodTypes(subject.hapiServices())).containsExactly(MethodType.BIDI_STREAMING);
    }

    @Test
    @DisplayName("given a HAPI TLS certificate, then the TLS listener serving CLPR sync starts, then stops on stop()")
    void givenHapiTlsCertificate_thenTlsListenerServingClprSyncStartsThenStopsOnStop(@TempDir final Path tempDir)
            throws Exception {
        final var crt = tempDir.resolve("hedera.crt");
        final var key = tempDir.resolve("hedera.key");
        new ClprTestTlsCert("test-hapi-tls").writePem(crt, key);
        final var config = HederaTestConfigBuilder.create()
                .withValue("grpc.port", 0)
                .withValue("grpc.tlsPort", 0)
                .withValue("grpc.nodeOperatorPortEnabled", false)
                .withValue("clpr.enabled", true)
                .withValue("netty.tlsCrt.path", crt.toString())
                .withValue("netty.tlsKey.path", key.toString())
                .getOrCreateConfig();

        final var subject = managerWithClprEndpointService(() -> new VersionedConfigImpl(config, 1));
        try {
            subject.start();
            assertThat(subject.tlsPort()).isGreaterThan(0);
            assertThat(fullMethodNames(subject.hapiServices())).contains(SYNC_FULL_METHOD_NAME);
        } finally {
            subject.stop();
        }
    }

    /** The method types registered under the CLPR sync method name, one per registration. */
    private static List<MethodType> syncMethodTypes(final Set<ServerServiceDefinition> definitions) {
        return definitions.stream()
                .flatMap(d -> d.getMethods().stream())
                .map(ServerMethodDefinition::getMethodDescriptor)
                .filter(descriptor -> SYNC_FULL_METHOD_NAME.equals(descriptor.getFullMethodName()))
                .map(MethodDescriptor::getType)
                .toList();
    }

    private static Set<String> fullMethodNames(final Set<ServerServiceDefinition> definitions) {
        return definitions.stream()
                .flatMap(d -> d.getMethods().stream())
                .map(m -> m.getMethodDescriptor().getFullMethodName())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Builds a manager whose only registered RPC service is the real CLPR endpoint service. */
    private NettyGrpcServerManager managerWithClprEndpointService(final ConfigProvider provider) {
        final var clprRpcService = new RpcService() {
            @NonNull
            @Override
            public String getServiceName() {
                return "ClprEndpointService";
            }

            @NonNull
            @Override
            public Set<RpcServiceDefinition> rpcDefinitions() {
                return Set.of(ClprEndpointServiceDefinition.INSTANCE);
            }

            @Override
            public void registerSchemas(@NonNull final SchemaRegistry registry) {
                // no schemas needed: only rpcDefinitions() is read here
            }
        };
        final var registryWithClpr =
                new ServicesRegistryImpl(ConstructableRegistry.getInstance(), provider.getConfiguration());
        registryWithClpr.register(clprRpcService);
        return new NettyGrpcServerManager(
                provider,
                registryWithClpr,
                ingestWorkflow,
                userQueryWorkflow,
                operatorQueryWorkflow,
                clprSyncWorkflow,
                metrics);
    }
}
