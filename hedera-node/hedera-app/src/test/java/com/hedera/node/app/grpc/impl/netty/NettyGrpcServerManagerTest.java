// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsRequest;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.workflows.clpr.ClprCaCertManager;
import com.hedera.node.app.workflows.clpr.ClprChannelManager;
import com.hedera.node.app.workflows.clpr.ClprLeafCertManager;
import com.hedera.node.app.workflows.clpr.ClprStreamingSyncSession;
import com.hedera.node.app.workflows.clpr.ClprSyncWorkflow;
import com.hedera.node.app.workflows.clpr.ClprTestCa;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.ServerServiceDefinition;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Provider;
import org.hiero.base.constructable.ConstructableRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
    private Provider<ClprLeafCertManager> clprLeafCertManager;
    private Provider<ClprChannelManager> clprChannelManager;
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
            public void handleSync(Bytes req, BufferedData res) {}

            @Override
            public void handleDiscovery(Bytes req, BufferedData res) {}

            @Override
            public ClprStreamingSyncSession openStreamingSync() {
                throw new UnsupportedOperationException();
            }
        };
        // No CLPR CA configured in the test config -> mTLS disabled; the dedicated sync listener is not built.
        this.clprLeafCertManager = () -> new ClprLeafCertManager(new ClprCaCertManager(configProvider));
        this.clprChannelManager = () -> null;
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
                        clprLeafCertManager,
                        clprChannelManager,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        null,
                        ingestWorkflow,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        clprLeafCertManager,
                        clprChannelManager,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        null,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        clprLeafCertManager,
                        clprChannelManager,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        ingestWorkflow,
                        null,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        clprLeafCertManager,
                        clprChannelManager,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        ingestWorkflow,
                        userQueryWorkflow,
                        null,
                        clprSyncWorkflow,
                        clprLeafCertManager,
                        clprChannelManager,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        ingestWorkflow,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        null,
                        clprLeafCertManager,
                        clprChannelManager,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        ingestWorkflow,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        null,
                        clprChannelManager,
                        metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NettyGrpcServerManager(
                        configProvider,
                        services,
                        ingestWorkflow,
                        userQueryWorkflow,
                        operatorQueryWorkflow,
                        clprSyncWorkflow,
                        clprLeafCertManager,
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
                        clprLeafCertManager,
                        clprChannelManager,
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
                clprLeafCertManager,
                clprChannelManager,
                metrics);
        assertThat(subject.port()).isEqualTo(-1);
        assertThat(subject.tlsPort()).isEqualTo(-1);
    }

    @Test
    @DisplayName("isClprSyncMethod matches only the CLPR sync method")
    void isClprSyncMethodMatchesOnlySync() {
        assertThat(NettyGrpcServerManager.isClprSyncMethod(
                        new RpcMethodDefinition<>("sync", ClprSyncPayload.class, ClprSyncPayload.class)))
                .isTrue();
        // discoverEndpoints is CLPR but not sync — it stays on the shared (non-mTLS) ports.
        assertThat(NettyGrpcServerManager.isClprSyncMethod(new RpcMethodDefinition<>(
                        "discoverEndpoints", ClprDiscoverEndpointsRequest.class, ClprDiscoverEndpointsRequest.class)))
                .isFalse();
        assertThat(NettyGrpcServerManager.isClprSyncMethod(
                        new RpcMethodDefinition<>("submit", Transaction.class, Transaction.class)))
                .isFalse();
        assertThat(NettyGrpcServerManager.isClprSyncMethod(
                        new RpcMethodDefinition<>("query", Query.class, Query.class)))
                .isFalse();
    }

    @Test
    @DisplayName("streaming and unary sync share ports when mtls is disabled")
    void streamingSyncSharesTheListenerWithSyncWhenMtlsIsOff() {
        // No CLPR CA is configured, so mTLS is off and the CLPR endpoint service stays on the shared HAPI ports.
        final var subject = managerWithClprEndpointService(configProvider, clprLeafCertManager);

        assertThat(fullMethodNames(subject.hapiServices()))
                .contains(SYNC_FULL_METHOD_NAME, ClprEndpointServiceDefinition.STREAMING_SYNC_FULL_METHOD_NAME);
        assertThat(subject.clprSyncServices()).isEmpty();
    }

    @Test
    @DisplayName("disabled CLPR does not instantiate the leaf certificate manager")
    void disabledClprDoesNotInstantiateLeafCertificateManager() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", false)
                .getOrCreateConfig();
        final ConfigProvider provider = () -> new VersionedConfigImpl(config, 1);
        final Provider<ClprLeafCertManager> unusedLeafCertManager = () -> {
            throw new AssertionError("CLPR leaf certificate manager must remain lazy while CLPR is disabled");
        };

        final var subject = managerWithClprEndpointService(provider, unusedLeafCertManager);

        assertThat(fullMethodNames(subject.hapiServices()))
                .contains(SYNC_FULL_METHOD_NAME, ClprEndpointServiceDefinition.STREAMING_SYNC_FULL_METHOD_NAME);
        assertThat(subject.clprSyncServices()).isEmpty();
    }

    @Test
    @DisplayName("streaming and unary sync share dedicated ports when mtls is enabled")
    void streamingSyncFollowsSyncToTheMtlsListener(@TempDir final Path tempDir) throws Exception {
        final var caCrt = tempDir.resolve("clpr-ca.crt");
        final var caKey = tempDir.resolve("clpr-ca.key");
        new ClprTestCa("test-clpr-ca").writePem(caCrt, caKey);
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", true)
                .withValue("clpr.caCrtPath", caCrt.toString())
                .withValue("clpr.caKeyPath", caKey.toString())
                .getOrCreateConfig();
        final ConfigProvider provider = () -> new VersionedConfigImpl(config, 1);
        final var leafCertManager = new ClprLeafCertManager(new ClprCaCertManager(provider));
        // Guard: if the CA failed to load, mTLS would be off and the assertions below would be vacuous.
        assertThat(leafCertManager.isMtlsEnabled()).isTrue();

        final var subject = managerWithClprEndpointService(provider, () -> leafCertManager);

        // Both sync methods move to the dedicated listener together — the streaming one must not be left
        // behind on the shared ports, where peers dialing the advertised ClprEndpoint port would never find it.
        assertThat(fullMethodNames(subject.clprSyncServices()))
                .contains(SYNC_FULL_METHOD_NAME, ClprEndpointServiceDefinition.STREAMING_SYNC_FULL_METHOD_NAME);
        assertThat(fullMethodNames(subject.hapiServices()))
                .doesNotContain(SYNC_FULL_METHOD_NAME, ClprEndpointServiceDefinition.STREAMING_SYNC_FULL_METHOD_NAME)
                // discoverEndpoints is CLPR but not sync, so it stays on the shared ports.
                .contains(ClprEndpointServiceDefinition.SERVICE_NAME + "/discoverEndpoints");
    }

    @Test
    @DisplayName("with mTLS disabled the dedicated CLPR sync listener is not started, and stop() is clean")
    void mtlsListenerNotStartedWhenDisabled() {
        // Bind ephemeral ports (0) so the test never collides with a real listener. No CLPR CA is
        // configured, so mTLS is disabled and the dedicated sync listener must not be created.
        final var config = HederaTestConfigBuilder.create()
                .withValue("grpc.port", "0")
                .withValue("grpc.tlsPort", "0")
                .getOrCreateConfig();
        final ConfigProvider provider = () -> new VersionedConfigImpl(config, 1);
        final var subject = new NettyGrpcServerManager(
                provider,
                services,
                ingestWorkflow,
                userQueryWorkflow,
                operatorQueryWorkflow,
                clprSyncWorkflow,
                () -> new ClprLeafCertManager(new ClprCaCertManager(provider)),
                clprChannelManager,
                metrics);
        try {
            subject.start();
            // The plain server did start (proves start() ran); the mTLS sync listener did not.
            assertThat(subject.port()).isGreaterThan(0);
            assertThat(subject.clprSyncPort()).isEqualTo(-1);
            assertThat(subject.clprSyncServer).isNull();
        } finally {
            subject.stop();
        }
        assertThat(subject.clprSyncPort()).isEqualTo(-1);
    }

    @Test
    @DisplayName("with mTLS enabled the dedicated CLPR sync listener starts, then stops on stop()")
    void mtlsListenerStartedWhenEnabled(@TempDir final Path tempDir) throws Exception {
        // Provision a real ECDSA P-384 CA so ClprLeafCertManager can sign its ephemeral leaf and the
        // dedicated mTLS listener can actually build its SslContext. All ports are ephemeral (0).
        final var caCrt = tempDir.resolve("clpr-ca.crt");
        final var caKey = tempDir.resolve("clpr-ca.key");
        new ClprTestCa("test-clpr-ca").writePem(caCrt, caKey);

        final var config = HederaTestConfigBuilder.create()
                .withValue("grpc.port", "0")
                .withValue("grpc.tlsPort", "0")
                .withValue("clpr.enabled", true)
                .withValue("clpr.caCrtPath", caCrt.toString())
                .withValue("clpr.caKeyPath", caKey.toString())
                .withValue("clpr.mtlsPort", "0")
                .getOrCreateConfig();
        final ConfigProvider provider = () -> new VersionedConfigImpl(config, 1);
        final var leafCertManager = new ClprLeafCertManager(new ClprCaCertManager(provider));
        // Guard: if the CA failed to load, mTLS would be off and the assertions below would be vacuous.
        assertThat(leafCertManager.isMtlsEnabled()).isTrue();

        final var subject = new NettyGrpcServerManager(
                provider,
                services,
                ingestWorkflow,
                userQueryWorkflow,
                operatorQueryWorkflow,
                clprSyncWorkflow,
                () -> leafCertManager,
                clprChannelManager,
                metrics);
        try {
            subject.start();
            assertThat(subject.clprSyncServer).isNotNull();
            assertThat(subject.clprSyncServer.isTerminated()).isFalse();
            assertThat(subject.clprSyncPort()).isGreaterThan(0);
        } finally {
            subject.stop();
        }
        // stop() terminates the listener (so clprSyncPort() reports -1) but, like the other servers,
        // does not null the field.
        assertThat(subject.clprSyncPort()).isEqualTo(-1);
        assertThat(subject.clprSyncServer.isTerminated()).isTrue();
    }

    private static Set<String> fullMethodNames(final Set<ServerServiceDefinition> definitions) {
        return definitions.stream()
                .flatMap(d -> d.getMethods().stream())
                .map(m -> m.getMethodDescriptor().getFullMethodName())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Builds a manager whose only registered RPC service is the real CLPR endpoint service. */
    private NettyGrpcServerManager managerWithClprEndpointService(
            final ConfigProvider provider, final Provider<ClprLeafCertManager> leafCertManager) {
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
                leafCertManager,
                clprChannelManager,
                metrics);
    }
}
