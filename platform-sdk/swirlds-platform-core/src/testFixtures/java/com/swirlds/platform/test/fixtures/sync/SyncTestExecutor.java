// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.eventhandling.EventConfig_;
import com.swirlds.platform.gossip.shadowgraph.ShadowEvent;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitter;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterFactory;
import com.swirlds.platform.test.fixtures.event.emitter.ShuffledEventEmitter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * This class executes a single sync between two {@link SyncNode} instances. It defines the high level structure and
 * order of operations common to each sync test. Custom logic is injected via lambdas as necessary.
 */
public class SyncTestExecutor {

    private final SyncTestParams params;

    private SyncNode caller;
    private SyncNode listener;

    private Consumer<EventEmitterFactory> factoryConfig;
    private Supplier<ParallelExecutor> callerExecutorSupplier;
    private Supplier<ParallelExecutor> listenerExecutorSupplier;
    private ConnectionFactory connectionFactory;
    private Function<EventEmitterFactory, SyncNode> callerSupplier;
    private Function<EventEmitterFactory, SyncNode> listenerSupplier;
    private BiConsumer<SyncNode, SyncNode> initialGraphCreation;
    private BiConsumer<SyncNode, SyncNode> customInitialization;
    private BiConsumer<SyncNode, SyncNode> graphCustomization;
    private BiConsumer<SyncNode, SyncNode> customPreSyncConfiguration;
    private BiConsumer<SyncNode, SyncNode> eventWindowDefinitions;
    private Predicate<EventImpl> callerAddToGraphTest;
    private Predicate<EventImpl> listenerAddToGraphTest;
    private final AncientMode ancientMode;

    /**
     * A randomly generated roster from the number of nodes in the parameters of the test.
     */
    private Roster roster;

    public SyncTestExecutor(final SyncTestParams params) {
        this.params = params;
        this.ancientMode = params.getAncientMode();
        this.roster = RandomRosterBuilder.create(Randotron.create())
                .withSize(params.getNumNetworkNodes())
                .build();

        factoryConfig = (f) -> {};
        callerExecutorSupplier = () -> {
            final CachedPoolParallelExecutor executor =
                    new CachedPoolParallelExecutor(getStaticThreadManager(), "sync-node");
            executor.start();
            return executor;
        };
        listenerExecutorSupplier = () -> {
            final CachedPoolParallelExecutor executor =
                    new CachedPoolParallelExecutor(getStaticThreadManager(), "sync-node");
            executor.start();
            return executor;
        };
        callerSupplier = (factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                0,
                factory.newShuffledFromSourceFactory(),
                callerExecutorSupplier.get(),
                ancientMode);
        listenerSupplier = (factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                params.getNumNetworkNodes() - 1,
                factory.newShuffledFromSourceFactory(),
                listenerExecutorSupplier.get(),
                ancientMode);

        initialGraphCreation = (caller, listener) -> {
            for (final SyncNode node : List.of(caller, listener)) {
                node.getEmitter().setCheckpoint(params.getNumCommonEvents());
                node.generateAndAdd(params.getNumCommonEvents());
                node.setSaveGeneratedEvents(true);
            }
        };
        customInitialization = (caller, listener) -> {};
        graphCustomization = (caller, listener) -> {};
        eventWindowDefinitions = updateDefaultEventWindow();
        customPreSyncConfiguration = (caller, listener) -> {};
        callerAddToGraphTest = (indexedEvent -> true);
        listenerAddToGraphTest = (indexedEvent -> true);
        connectionFactory = ConnectionFactory::createLocalConnections;
    }

    @NonNull
    public Roster getRoster() {
        return roster;
    }

    /**
     * Executes the following test phases in order:
     * <ol>
     *     <li>Initialization</li>
     *     <li>Graph Creation</li>
     *     <li>Pre-Sync Configuration</li>
     *     <li>Sync</li>
     * </ol>
     *
     * @throws Exception
     */
    public void execute() throws Exception {
        initialize();
        createGraphs();
        preSyncConfiguration();
        sync();
    }

    /**
     * Creates and initializes the caller and listener nodes. At the completion of this method, the caller and listener
     * are ready to begin graph generation.
     */
    private void initialize() throws IOException {
        final Random random;
        if (params.getCustomSeed() == null) {
            random = RandomUtils.getRandomPrintSeed();
        } else {
            System.out.println("Using custom seed: " + params.getCustomSeed());
            random = new Random(params.getCustomSeed());
        }

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue(
                                EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD,
                                ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD)
                        .getOrCreateConfig())
                .build();

        final EventEmitterFactory factory = new EventEmitterFactory(platformContext, random, roster);

        factoryConfig.accept(factory);

        caller = callerSupplier.apply(factory);
        listener = listenerSupplier.apply(factory);

        final Pair<Connection, Connection> connections =
                connectionFactory.createConnections(caller.getNodeId(), listener.getNodeId());
        caller.setSyncConnection(connections.left());
        listener.setSyncConnection(connections.right());

        customInitialization.accept(caller, listener);
    }

    /**
     * Creates the graphs for the caller and listener. Consists of:
     *
     * <ol>
     *     <li>Initial Graph Creation</li>
     *     <li>Graph Customization</li>
     *     <li>Caller Graph Additions</li>
     *     <li>Listener Graph Additions</li>
     * </ol>
     * <p>
     * Does not include graph validation.
     */
    private void createGraphs() {
        initialGraphCreation.accept(caller, listener);
        graphCustomization.accept(caller, listener);
        caller.getEmitter().setCheckpoint(params.getNumCallerEvents());
        caller.generateAndAdd(params.getNumCallerEvents(), callerAddToGraphTest);
        listener.getEmitter().setCheckpoint(params.getNumListenerEvents());
        listener.generateAndAdd(params.getNumListenerEvents(), listenerAddToGraphTest);
        eventWindowDefinitions.accept(caller, listener);
    }

    private BiConsumer<SyncNode, SyncNode> updateDefaultEventWindow() {
        return (caller, listener) -> {
            final List<ShadowEvent> callerTips = caller.getShadowGraph().getTips();
            final List<ShadowEvent> listenerTips = listener.getShadowGraph().getTips();

            final long listenerExpiredThreshold = SyncTestUtils.getMinIndicator(
                    listener.getShadowGraph().findAncestors(listenerTips, (e) -> true), ancientMode);
            final long listenerMaxIndicator = SyncTestUtils.getMaxIndicator(listenerTips, ancientMode);
            final long callerExpiredThreshold = SyncTestUtils.getMinIndicator(
                    caller.getShadowGraph().findAncestors(callerTips, (e) -> true), ancientMode);
            final long callerMaxIndicator = SyncTestUtils.getMaxIndicator(callerTips, ancientMode);

            long listenerAncientThreshold = listenerExpiredThreshold;
            final double listenerDif = listenerMaxIndicator - listenerExpiredThreshold;
            if (listenerDif >= 3) {
                listenerAncientThreshold += (long) Math.floor(listenerDif / 3);
            } else if (listenerDif == 2) {
                listenerAncientThreshold++;
            }

            long callerAncientThreshold = callerExpiredThreshold;
            final double callerDif = callerMaxIndicator - callerExpiredThreshold;
            if (callerDif >= 3) {
                callerAncientThreshold += (long) Math.floor(callerDif / 3);
            } else if (callerDif == 2) {
                callerAncientThreshold++;
            }

            caller.updateEventWindow(new EventWindow(
                    ancientMode.getGenesisIndicator(),
                    Math.max(ancientMode.getGenesisIndicator(), callerAncientThreshold),
                    Math.max(ancientMode.getGenesisIndicator(), callerExpiredThreshold),
                    ancientMode));

            listener.updateEventWindow(new EventWindow(
                    ancientMode.getGenesisIndicator(),
                    Math.max(ancientMode.getGenesisIndicator(), listenerAncientThreshold),
                    Math.max(ancientMode.getGenesisIndicator(), listenerExpiredThreshold),
                    ancientMode));
        };
    }

    /**
     * Performs configuration necessary after graph creation but prior to syncing.
     */
    private void preSyncConfiguration() {
        customPreSyncConfiguration.accept(caller, listener);
    }

    /**
     * Performs a single sync between the caller and listener.
     *
     * @throws Exception
     */
    private void sync() throws Exception {
        final Synchronizer synchronizer = new Synchronizer();

        final long start = System.nanoTime();
        synchronizer.synchronize(caller, listener);
        final long stop = System.nanoTime();
        Duration syncDuration = Duration.ofNanos(stop - start);

        caller.drainReceivedEventQueue();
        listener.drainReceivedEventQueue();

        System.out.printf(
                "Synced %d events in %d ms%n",
                caller.getReceivedEvents().size() + listener.getReceivedEvents().size(), syncDuration.toMillis());
    }

    /**
     * @param executorSupplier supplies the ParallelExecutor for both of the {@link SyncNode}s
     */
    public void setExecutorSupplier(final Supplier<ParallelExecutor> executorSupplier) {
        this.callerExecutorSupplier = executorSupplier;
        this.listenerExecutorSupplier = executorSupplier;
    }

    /**
     * Supplies the {@link ParallelExecutor} for the caller {@link SyncNode}
     */
    public void setListenerExecutorSupplier(final Supplier<ParallelExecutor> listenerExecutorSupplier) {
        this.listenerExecutorSupplier = listenerExecutorSupplier;
    }

    /**
     * Supplies the {@link ParallelExecutor} for the listener {@link SyncNode}
     */
    public void setCallerExecutorSupplier(final Supplier<ParallelExecutor> callerExecutorSupplier) {
        this.callerExecutorSupplier = callerExecutorSupplier;
    }

    /**
     * @param factoryConfig a method that configures the event factory for the particular test
     */
    public void setFactoryConfig(final Consumer<EventEmitterFactory> factoryConfig) {
        this.factoryConfig = factoryConfig;
    }

    /**
     * Sets a custom event window definition function that defines what event window the caller and listener will use in
     * the sync.
     *
     * @param eventWindowDefinitions a function that defines the event window for the caller and listener
     */
    public void setEventWindowDefinitions(final BiConsumer<SyncNode, SyncNode> eventWindowDefinitions) {
        this.eventWindowDefinitions = eventWindowDefinitions;
    }

    /**
     * <p>Sets a custom supplier for the caller node.</p>
     *
     * <p>This method should be used if the caller {@link SyncNode} requires custom initialization such as a custom
     * {@link ParallelExecutor} or an {@link EventEmitter} other than a {@link ShuffledEventEmitter}.</p>
     *
     * <p>Please note that is an {@link EventEmitter} other than {@link ShuffledEventEmitter} is used to create this
     * node, the {@link SyncTestExecutor#setInitialGraphCreation(BiConsumer)} must be called with a compatible
     * {@link BiConsumer}.</p>
     *
     * @param callerSupplier
     */
    public void setCallerSupplier(final Function<EventEmitterFactory, SyncNode> callerSupplier) {
        this.callerSupplier = callerSupplier;
    }

    /**
     * Sets a custom supplier for the listener node.
     *
     * <p>This method should be used if the listener {@link SyncNode} requires custom initialization such as a custom
     * {@link ParallelExecutor} or an {@link EventEmitter} other than a {@link ShuffledEventEmitter}.</p>
     *
     * <p>Please note that is an {@link EventEmitter} other than {@link ShuffledEventEmitter} is used to create this
     * node, the {@link SyncTestExecutor#setInitialGraphCreation(BiConsumer)} must be called with a compatible
     * {@link BiConsumer} .</p>
     */
    public void setListenerSupplier(final Function<EventEmitterFactory, SyncNode> listenerSupplier) {
        this.listenerSupplier = listenerSupplier;
    }

    /**
     * <p>Defines the custom initialization function for creating the initial shadow graph for the nodes.</p>
     *
     * <p>Note that several validator functions require that {@link SyncNode#setSaveGeneratedEvents(boolean)} be called
     * with {@code true} during this step.</p>
     *
     * @param initialGraphCreation
     */
    public void setInitialGraphCreation(final BiConsumer<SyncNode, SyncNode> initialGraphCreation) {
        this.initialGraphCreation = initialGraphCreation;
    }

    /**
     * Defines the custom initialization function to be performed after common initialization of the caller and listener
     * nodes but before graph creation.
     * <p>
     * For example, this could be used to turn on saving events for one or both of the node prior to any event
     * creation.
     *
     * @param customInitialization
     */
    public void setCustomInitialization(final BiConsumer<SyncNode, SyncNode> customInitialization) {
        this.customInitialization = customInitialization;
    }

    /**
     * Defines a graph customization function to be performed after initial graph creation, but before
     * {@link SyncTestParams#getNumCallerEvents()} and {@link SyncTestParams#getNumListenerEvents()} events are added to
     * the caller and listener shadow graphs.
     * <p>
     * For example, this method could be used to setup the sync nodes to partition for the remaining events.
     *
     * @param graphCustomization
     */
    public void setGraphCustomization(final BiConsumer<SyncNode, SyncNode> graphCustomization) {
        this.graphCustomization = graphCustomization;
    }

    /**
     * Defines the custom pre-sync configuration function to be performed after shadow graph creation, but before sync.
     *
     * @param customPreSyncConfiguration
     */
    public void setCustomPreSyncConfiguration(final BiConsumer<SyncNode, SyncNode> customPreSyncConfiguration) {
        this.customPreSyncConfiguration = customPreSyncConfiguration;
    }

    /**
     * Defines a custom predicate that determines if caller events generated after the common events should be added to
     * the caller's shadow graph.
     *
     * @param callerAddToGraphTest
     */
    public void setCallerAddToGraphTest(final Predicate<EventImpl> callerAddToGraphTest) {
        this.callerAddToGraphTest = callerAddToGraphTest;
    }

    /**
     * Defines a custom predicate that determines if listener events generated after the common events should be added
     * to the caller's shadow graph.
     *
     * @param listenerAddToGraphTest
     */
    public void setListenerAddToGraphTest(final Predicate<EventImpl> listenerAddToGraphTest) {
        this.listenerAddToGraphTest = listenerAddToGraphTest;
    }

    public SyncNode getCaller() {
        return caller;
    }

    public SyncNode getListener() {
        return listener;
    }

    public void setConnectionFactory(final ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }
}
