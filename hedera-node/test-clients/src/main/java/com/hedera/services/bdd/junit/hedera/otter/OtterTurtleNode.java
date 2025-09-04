// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.otter;

import static com.hedera.services.bdd.junit.hedera.embedded.AbstractEmbeddedHedera.MAX_PLATFORM_TXN_SIZE;
import static com.hedera.services.bdd.junit.hedera.embedded.AbstractEmbeddedHedera.MAX_QUERY_RESPONSE_SIZE;
import static com.hedera.services.bdd.junit.hedera.embedded.AbstractEmbeddedHedera.parseQueryResponse;
import static com.hedera.services.bdd.junit.hedera.embedded.AbstractEmbeddedHedera.parseTransactionResponse;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.conditionFuture;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_CONFIG_FILE_NAME;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.Hedera;
import com.hedera.node.app.HederaVirtualMapState;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.app.services.OrderedServiceMigrator;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedNode;
import com.hedera.services.bdd.junit.hedera.embedded.fakes.FakeHintsService;
import com.hedera.services.bdd.junit.hedera.embedded.fakes.FakeHistoryService;
import com.hedera.services.bdd.junit.hedera.embedded.fakes.LapsingBlockHashSigner;
import com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.config.legacy.LegacyConfigProperties;
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.turtle.TurtleNode;

/**
 * A Hedera node that runs on Turtle using the Otter test framework.
 */
@SuppressWarnings("removal")
public class OtterTurtleNode extends EmbeddedNode implements HederaNode {

    private final TurtleNode node;
    private final Executor executor;

    @Nullable
    private Hedera hedera;

    /**
     * Constructs a new OtterTurtleNode.
     *
     * @param metadata the node metadata
     * @param node the otter Turtle node
     * @param executor the executor for async operations
     */
    public OtterTurtleNode(
            @NonNull final NodeMetadata metadata, @NonNull final TurtleNode node, @NonNull final Executor executor) {
        super(metadata);
        this.node = requireNonNull(node);
        this.executor = executor;
    }

    @Nullable
    public Hedera hedera() {
        return hedera;
    }

    @Override
    protected OtterTurtleNode self() {
        return this;
    }

    @Override
    public HederaNode start() {
        super.start();
        doStart();
        return this;
    }

    @Override
    public CompletableFuture<Void> statusFuture(
            @Nullable final Consumer<NodeStatus> nodeStatusObserver, @NonNull final PlatformStatus... statuses) {
        if (statuses.length != 1 || statuses[0] != PlatformStatus.ACTIVE) {
            throw new UnsupportedOperationException("Only ACTIVE status is supported");
        }

        return conditionFuture(() -> node.isInStatus(statuses[0]));
    }

    private void doStart() {
        final Configuration platformConfig = node.configuration().current();
        final org.hiero.consensus.model.node.NodeId legacyNodeId =
                org.hiero.consensus.model.node.NodeId.of(node.selfId().id());

        setupGlobalMetrics(platformConfig);

        final Time time = node.time();

        MerkleDb.resetDefaultInstancePath();
        final Metrics metrics = getMetricsProvider().createPlatformMetrics(legacyNodeId);
        final FileSystemManager fileSystemManager = FileSystemManager.create(platformConfig);
        final RecycleBin recycleBin = RecycleBin.create(
                metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, legacyNodeId);

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withTime(time)
                .withConfiguration(platformConfig)
                .withFileSystemManager(fileSystemManager)
                .withMetrics(metrics)
                .withRecycleBin(recycleBin)
                .build();

        hedera = new Hedera(
                ConstructableRegistry.getInstance(),
                ServicesRegistryImpl::new,
                new OrderedServiceMigrator(),
                time::now,
                DiskStartupNetworks::new,
                FakeHintsService::new,
                FakeHistoryService::new,
                LapsingBlockHashSigner::new,
                metrics,
                new PlatformStateFacade(),
                () -> new HederaVirtualMapState(platformConfig, metrics));

        final LegacyConfigProperties props = LegacyConfigPropertiesLoader.loadConfigFile(
                this.metadata().workingDir().resolve(DEFAULT_CONFIG_FILE_NAME));
        props.appConfig().ifPresent(c -> ParameterProvider.getInstance().setParameters(c.params()));
        final AddressBook addressBook = props.getAddressBook();

        node.initHedera(hedera, platformContext, addressBook);
        hedera.init(node.platform(), legacyNodeId);

        node.start();

        hedera.run();
    }

    @Override
    public CompletableFuture<Void> stopFuture() {
        return CompletableFuture.runAsync(node::killImmediately, executor);
    }

    /**
     * Sends a query to this node.
     *
     * @param query the query to send
     * @param asNodeOperator whether to send the query as the node operator
     * @return the response from the node
     */
    @NonNull
    public Response send(@NonNull final Query query, final boolean asNodeOperator) {
        final var responseBuffer = BufferedData.allocate(MAX_QUERY_RESPONSE_SIZE);
        if (asNodeOperator) {
            hedera.operatorQueryWorkflow().handleQuery(Bytes.wrap(query.toByteArray()), responseBuffer);
        } else {
            hedera.queryWorkflow().handleQuery(Bytes.wrap(query.toByteArray()), responseBuffer);
        }
        return parseQueryResponse(responseBuffer);
    }

    /**
     * Submits a transaction to this node.
     *
     * @param transaction the transaction to submit
     * @return the response from the node
     */
    @NonNull
    public TransactionResponse submit(@NonNull final Transaction transaction) {
        final var responseBuffer = BufferedData.allocate(MAX_PLATFORM_TXN_SIZE);
        hedera.ingestWorkflow().submitTransaction(Bytes.wrap(transaction.toByteArray()), responseBuffer);
        return parseTransactionResponse(responseBuffer);
    }

    public AutoCloseableWrapper<State> state(@NonNull final String reason) {
        return node.platform().getLatestImmutableState(reason);
    }
}
