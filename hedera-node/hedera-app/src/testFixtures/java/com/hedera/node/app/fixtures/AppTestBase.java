// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures;

import static com.swirlds.platform.roster.RosterRetriever.buildRoster;
import static com.swirlds.platform.system.address.AddressBookUtils.endpointFor;
import static com.swirlds.state.test.fixtures.merkle.TestSchema.CURRENT_VERSION;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.fixtures.state.FakePlatform;
import com.hedera.node.app.fixtures.state.FakeSchemaRegistry;
import com.hedera.node.app.fixtures.state.FakeStartupNetworks;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.fixtures.TransactionFactory;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import com.swirlds.state.test.fixtures.TestBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.hiero.consensus.model.node.NodeId;

/**
 * Most of the components in this module have rich and interesting dependencies. While we can (and at times must) mock
 * these dependencies out, especially to test a variety of negative test scenarios, it is often better to test using
 * something more approximating a real environment. Such tests are less brittle to changes in the codebase, and also
 * tend to find issues earlier in the development cycle. They may also result in test failures in seemingly unrelated
 * tests. For example, if all tests use {@link State}, and the implementation of that has a bug, a large number
 * of tests that are only indirectly related to {@link State} will still fail.
 *
 * <p>The real challenge is that many of these dependencies are not easy to set up. In addition, from test to test,
 * you may want *almost* everything setup as normal, but with a small tweak in one place or another.
 *
 * <p>This test base class is designed to make it easy to set up a test environment that is as close to the real thing
 * as _necessary_. For example, it uses the fake Map-based implementations of the {@link WritableStates} and the
 * {@link ReadableStates} interfaces rather than the merkle tree implementation. But it still uses a real-ish startup
 * sequence for loading schemas and so forth.
 *
 * <p>It provides a builder for setting up the test environment. This builder will allow you to specify the config, the
 * services you want to initialize, the initial starting state you want to use, etc. It will build an object with
 * methods for getting implementations of various interfaces that you can use in your tests.
 */
public class AppTestBase extends TestBase implements TransactionFactory, Scenarios {

    // For many of our tests we need to have metrics available, and an easy way to test the metrics
    // are being set appropriately.
    /** Used as a dependency to the {@link Metrics} system. */
    public static final ScheduledExecutorService METRIC_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    public static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();

    private static final String ACCOUNTS_KEY = "ACCOUNTS";
    private static final String ALIASES_KEY = "ALIASES";
    protected MapWritableKVState<AccountID, Account> accountsState;
    protected MapWritableKVState<ProtoBytes, AccountID> aliasesState;
    protected WritableSingletonState<EntityCounts> entityCountsState;
    protected WritableSingletonState<EntityNumber> entityIdState;
    protected State state;

    protected void setupStandardStates() {
        accountsState = new MapWritableKVState<>(ACCOUNTS_KEY);
        accountsState.put(ALICE.accountID(), ALICE.account());
        accountsState.put(ERIN.accountID(), ERIN.account());
        accountsState.put(STAKING_REWARD_ACCOUNT.accountID(), STAKING_REWARD_ACCOUNT.account());
        accountsState.put(FUNDING_ACCOUNT.accountID(), FUNDING_ACCOUNT.account());
        accountsState.put(nodeSelfAccountId, nodeSelfAccount);
        accountsState.commit();
        aliasesState = new MapWritableKVState<>(ALIASES_KEY);

        entityIdState = new WritableSingletonStateBase<>("ENTITY_ID", () -> null, (a) -> {});
        entityCountsState = new WritableSingletonStateBase<>("ENTITY_COUNTS", () -> EntityCounts.DEFAULT, (a) -> {});
        final var writableStates = MapWritableStates.builder()
                .state(accountsState)
                .state(aliasesState)
                .state(entityIdState)
                .state(entityCountsState)
                .build();

        state = new TestMerkleStateRoot() {
            @NonNull
            @Override
            public ReadableStates getReadableStates(@NonNull String serviceName) {
                return TokenService.NAME.equals(serviceName) || EntityIdService.NAME.equals(serviceName)
                        ? writableStates
                        : null;
            }

            @NonNull
            @Override
            public WritableStates getWritableStates(@NonNull String serviceName) {
                return TokenService.NAME.equals(serviceName) || EntityIdService.NAME.equals(serviceName)
                        ? writableStates
                        : null;
            }
        };
    }

    private final SemanticVersion hapiVersion =
            SemanticVersion.newBuilder().major(1).minor(2).patch(3).build();
    /** Represents "this node" in our tests. */
    protected final NodeId nodeSelfId = NodeId.of(7);
    /** The AccountID of "this node" in our tests. */
    protected final AccountID nodeSelfAccountId =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(8).build();

    protected Account nodeSelfAccount = Account.newBuilder()
            .accountId(nodeSelfAccountId)
            .key(FAKE_ED25519_KEY_INFOS[0].publicKey())
            .declineReward(true)
            .build();

    protected final NodeInfo selfNodeInfo = new NodeInfoImpl(
            7,
            nodeSelfAccountId,
            10,
            List.of(endpointFor("127.0.0.1", 50211), endpointFor("127.0.0.1", 23456)),
            Bytes.wrap("cert7"),
            List.of(endpointFor("127.0.0.1", 50211), endpointFor("127.0.0.1", 23456)));

    /**
     * The gRPC system has extensive metrics. This object allows us to inspect them and make sure they are being set
     * correctly for different types of calls.
     */
    protected final Metrics metrics;

    public AppTestBase() {
        final Configuration configuration = HederaTestConfigBuilder.createConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        this.metrics = new DefaultPlatformMetrics(
                nodeSelfId,
                new MetricKeyRegistry(),
                METRIC_EXECUTOR,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }

    protected Counter counterMetric(final String name) {
        return (Counter) metrics.getMetric("app", name);
    }

    protected SpeedometerMetric speedometerMetric(final String name) {
        return (SpeedometerMetric) metrics.getMetric("app", name);
    }

    protected TestAppBuilder appBuilder() {
        return new TestAppBuilder();
    }

    public interface App {
        @NonNull
        SemanticVersion hapiVersion();

        @NonNull
        WorkingStateAccessor workingStateAccessor();

        @NonNull
        NetworkInfo networkInfo();

        @NonNull
        ConfigProvider configProvider();

        @NonNull
        Platform platform();

        @NonNull
        StateMutator stateMutator(@NonNull final String serviceName);
    }

    public static final class StateMutator {
        private final MapWritableStates writableStates;

        private StateMutator(@NonNull final MapWritableStates states) {
            this.writableStates = states;
        }

        public <T> StateMutator withSingletonState(@NonNull final String stateKey, @NonNull final T value) {
            writableStates.getSingleton(stateKey).put(value);
            return this;
        }

        public <K, V> StateMutator withKVState(
                @NonNull final String stateKey, @NonNull final K key, @NonNull final V value) {
            writableStates.get(stateKey).put(key, value);
            return this;
        }

        public <T> StateMutator withQueueState(@NonNull final String stateKey, @NonNull final T value) {
            writableStates.getQueue(stateKey).add(value);
            return this;
        }

        public void commit() {
            writableStates.commit();
        }
    }

    public static final class TestAppBuilder {
        private SemanticVersion hapiVersion = CURRENT_VERSION;
        private Set<Service> services = new LinkedHashSet<>();
        private TestConfigBuilder configBuilder = HederaTestConfigBuilder.create();
        private NodeInfo selfNodeInfo = new NodeInfoImpl(
                0,
                AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(8).build(),
                10,
                List.of(),
                Bytes.EMPTY,
                List.of());
        private Set<NodeInfo> nodes = new LinkedHashSet<>();

        private TestAppBuilder() {}

        /**
         * Specify a service to include in this test application configuration. The schemas for this service will be
         * configured.
         *
         * @param service The service to include.
         * @return a reference to this builder
         */
        public TestAppBuilder withService(@NonNull final Service service) {
            services.add(service);
            return this;
        }

        public TestAppBuilder withHapiVersion(@NonNull final SemanticVersion version) {
            this.hapiVersion = version;
            return this;
        }

        public TestAppBuilder withConfigSource(@NonNull final ConfigSource source) {
            configBuilder.withSource(source);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, @Nullable final String value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, final boolean value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, final int value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, final long value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, final double value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withConfigValue(@NonNull final String name, @NonNull final Object value) {
            configBuilder.withValue(name, value);
            return this;
        }

        public TestAppBuilder withNode(@NonNull final NodeInfo nodeInfo) {
            nodes.add(nodeInfo);
            return this;
        }

        public TestAppBuilder withSelfNode(@NonNull final NodeInfo selfNodeInfo) {
            this.selfNodeInfo = selfNodeInfo;
            return this;
        }

        public App build() {
            final NodeInfo realSelfNodeInfo;
            if (this.selfNodeInfo == null) {
                final var nodeSelfAccountId = AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(8)
                        .build();
                realSelfNodeInfo = new NodeInfoImpl(
                        7,
                        nodeSelfAccountId,
                        10,
                        List.of(endpointFor("127.0.0.1", 50211), endpointFor("127.0.0.4", 23456)),
                        Bytes.wrap("cert7"),
                        List.of(endpointFor("127.0.0.1", 50211), endpointFor("127.0.0.4", 23456)));
            } else {
                realSelfNodeInfo = new NodeInfoImpl(
                        selfNodeInfo.nodeId(),
                        selfNodeInfo.accountId(),
                        selfNodeInfo.weight(),
                        selfNodeInfo.gossipEndpoints(),
                        selfNodeInfo.sigCertBytes(),
                        selfNodeInfo.hapiEndpoints());
            }

            final var workingStateAccessor = new WorkingStateAccessor();

            final ConfigProvider configProvider = () -> new VersionedConfigImpl(configBuilder.getOrCreateConfig(), 1);
            final var addresses = nodes.stream()
                    .map(nodeInfo -> new Address()
                            .copySetNodeId(NodeId.of(nodeInfo.nodeId()))
                            .copySetWeight(nodeInfo.zeroWeight() ? 0 : 10))
                    .toList();
            final var addressBook = new AddressBook(addresses);
            final var platform = new FakePlatform(realSelfNodeInfo.nodeId(), addressBook);
            final var initialState = new FakeState();
            final var genesisRoster = buildRoster(addressBook);
            final var genesisNetwork = Network.newBuilder()
                    .nodeMetadata(genesisRoster.rosterEntries().stream()
                            .map(entry ->
                                    NodeMetadata.newBuilder().rosterEntry(entry).build())
                            .toList())
                    .build();
            final var networkInfo = new GenesisNetworkInfo(genesisNetwork, Bytes.fromHex("03"));
            final var startupNetworks = new FakeStartupNetworks(genesisNetwork);
            services.forEach(svc -> {
                final var reg = new FakeSchemaRegistry();
                svc.registerSchemas(reg);
                reg.migrate(svc.getServiceName(), initialState, startupNetworks);
            });
            workingStateAccessor.setState(initialState);

            return new App() {
                @NonNull
                @Override
                public SemanticVersion hapiVersion() {
                    return hapiVersion;
                }

                @NonNull
                @Override
                public WorkingStateAccessor workingStateAccessor() {
                    return workingStateAccessor;
                }

                @NonNull
                @Override
                public NetworkInfo networkInfo() {
                    return networkInfo;
                }

                @NonNull
                @Override
                public ConfigProvider configProvider() {
                    return configProvider;
                }

                @NonNull
                @Override
                public Platform platform() {
                    return platform;
                }

                @NonNull
                @Override
                public StateMutator stateMutator(@NonNull final String serviceName) {
                    final var fakeMerkleState = requireNonNull(workingStateAccessor.getState());
                    final var writableStates = (MapWritableStates) fakeMerkleState.getWritableStates(serviceName);
                    return new StateMutator(writableStates);
                }
            };
        }
    }

    /**
     * Provides information about the network based on the given roster and ledger ID.
     */
    public static class GenesisNetworkInfo implements NetworkInfo {
        private final Bytes ledgerId;
        private final Map<Long, NodeInfo> nodeInfos;

        /**
         * Constructs a new {@link GenesisNetworkInfo} instance.
         *
         * @param genesisNetwork The genesis network
         * @param ledgerId      The ledger ID
         */
        public GenesisNetworkInfo(@NonNull final Network genesisNetwork, @NonNull final Bytes ledgerId) {
            this.ledgerId = requireNonNull(ledgerId);
            this.nodeInfos = nodeInfosFrom(genesisNetwork);
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Bytes ledgerId() {
            return ledgerId;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public NodeInfo selfNodeInfo() {
            throw new UnsupportedOperationException("Not implemented");
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public List<NodeInfo> addressBook() {
            return List.copyOf(nodeInfos.values());
        }

        /**
         * {@inheritDoc}
         */
        @Nullable
        @Override
        public NodeInfo nodeInfo(final long nodeId) {
            return nodeInfos.get(nodeId);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean containsNode(final long nodeId) {
            return nodeInfos.containsKey(nodeId);
        }

        @Override
        public void updateFrom(final State state) {
            throw new UnsupportedOperationException("Not implemented");
        }

        private static Map<Long, NodeInfo> nodeInfosFrom(@NonNull final Network network) {
            final var nodeInfos = new LinkedHashMap<Long, NodeInfo>();
            for (final var metadata : network.nodeMetadata()) {
                final var node = metadata.nodeOrThrow();
                final var nodeInfo = new NodeInfoImpl(
                        node.nodeId(),
                        node.accountIdOrThrow(),
                        node.weight(),
                        node.gossipEndpoint(),
                        node.gossipCaCertificate(),
                        node.serviceEndpoint());
                nodeInfos.put(node.nodeId(), nodeInfo);
            }
            return nodeInfos;
        }
    }
}
