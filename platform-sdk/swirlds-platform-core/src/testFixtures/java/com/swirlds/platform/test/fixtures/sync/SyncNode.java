// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.event.hashing.DefaultEventHasher;
import com.swirlds.platform.event.hashing.EventHasher;
import com.swirlds.platform.eventhandling.EventConfig_;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphInsertionException;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.sync.config.SyncConfig_;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Represents a node in a sync for tests. This node can be the caller or the listener.
 */
public class SyncNode {

    private final BlockingQueue<PlatformEvent> receivedEventQueue;
    private final List<EventImpl> generatedEvents;
    private final List<EventImpl> discardedEvents;

    private final List<PlatformEvent> receivedEvents;

    private final NodeId nodeId;

    private final int numNodes;
    private final EventEmitter eventEmitter;
    private int eventsEmitted = 0;
    private final TestingSyncManager syncManager;
    private final Shadowgraph shadowGraph;
    private ParallelExecutor executor;
    private Connection connection;
    private boolean saveGeneratedEvents;
    private boolean shouldAcceptSync = true;
    private boolean reconnected = false;
    private final AncientMode ancientMode;

    private long expirationThreshold;

    private Exception syncException;
    private final AtomicInteger sleepAfterEventReadMillis = new AtomicInteger(0);
    /**
     * the value returned by the {@link ShadowgraphSynchronizer}, set to null if: no sync occurred OR an exception was
     * thrown
     */
    private final AtomicReference<Boolean> synchronizerReturn = new AtomicReference<>(null);

    private final PlatformContext platformContext;

    public SyncNode(
            final int numNodes,
            final long nodeId,
            final EventEmitter eventEmitter,
            @NonNull final AncientMode ancientMode) {

        this(
                numNodes,
                nodeId,
                eventEmitter,
                new CachedPoolParallelExecutor(getStaticThreadManager(), "sync-node"),
                ancientMode);
    }

    public SyncNode(
            final int numNodes,
            final long nodeId,
            final EventEmitter eventEmitter,
            final ParallelExecutor executor,
            @NonNull final AncientMode ancientMode) {

        if (executor.isMutable()) {
            executor.start();
        }

        this.ancientMode = Objects.requireNonNull(ancientMode);
        this.numNodes = numNodes;
        this.nodeId = NodeId.of(nodeId);
        this.eventEmitter = eventEmitter;

        syncManager = new TestingSyncManager();

        receivedEventQueue = new LinkedBlockingQueue<>();
        receivedEvents = new ArrayList<>();
        generatedEvents = new LinkedList<>();
        discardedEvents = new LinkedList<>();
        saveGeneratedEvents = false;

        // The original sync tests are incompatible with event filtering and reduced sync event counts.
        final Configuration configuration = new TestConfigBuilder()
                .withValue(SyncConfig_.FILTER_LIKELY_DUPLICATES, false)
                .withValue(SyncConfig_.MAX_SYNC_EVENT_COUNT, 0)
                .withValue(
                        EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD,
                        ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();

        platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        shadowGraph = new Shadowgraph(platformContext, numNodes, new NoOpIntakeEventCounter());
        shadowGraph.updateEventWindow(EventWindow.getGenesisEventWindow(ancientMode));
        this.executor = executor;
    }

    public void setSyncConnection(final Connection connection) {
        this.connection = connection;
    }

    public Connection getConnection() {
        return connection;
    }

    /**
     * Generates new events using the current seed value provided and adds the events to the current
     * {@link Shadowgraph}'s queue to be inserted into the shadow graph. The {@link SyncNode#eventEmitter} should be
     * setup such that it only generates events that can be added to the {@link Shadowgraph} (i.e. no events with an
     * other parent that is unknown to this node). Failure to do so may result in invalid test results.
     *
     * @param numEvents the number of events to generate and add to the {@link Shadowgraph}
     * @return an immutable list of the events added to the {@link Shadowgraph}
     */
    public List<EventImpl> generateAndAdd(final int numEvents) {
        return generateAndAdd(numEvents, (e) -> true);
    }

    /**
     * <p>Generates new events using the current seed value provided. Each event is added current {@link
     * Shadowgraph}'s queue if the provided {@code shouldAddToGraph} predicate passes. Any events that do not pass the
     * {@code shouldAddToGraph} predicate are added to {@link SyncNode#discardedEvents}. Events that do pass the
     * {@code shouldAddToGraph} predicate are added to {@link SyncNode#generatedEvents}.</p>
     *
     * <p>The {@link SyncNode#eventEmitter} should be setup such that it only generates events that can be added to
     * the {@link Shadowgraph} (i.e. no events with an other parent that is unknown to this node). Failure to do so may
     * result in invalid test results.</p>
     *
     * @param numEvents the number of events to generate and add to the {@link Shadowgraph}
     * @return an immutable list of the events added to the {@link Shadowgraph}
     */
    public List<EventImpl> generateAndAdd(final int numEvents, final Predicate<EventImpl> shouldAddToGraph) {
        if (eventEmitter == null) {
            throw new IllegalStateException(
                    "SyncNode.setEventGenerator(ShuffledEventGenerator) must be called prior to generateAndAdd"
                            + "(int)");
        }
        eventsEmitted += numEvents;
        eventEmitter.setCheckpoint(eventsEmitted);
        final List<EventImpl> newEvents = eventEmitter.emitEvents(numEvents);

        for (final EventImpl newEvent : newEvents) {

            // Only add the event to the graphs and the list of generated events if the test passes
            if (shouldAddToGraph.test(newEvent)) {
                addToShadowGraph(newEvent.getBaseEvent());
                if (saveGeneratedEvents) {
                    generatedEvents.add(newEvent);
                }
            } else {
                discardedEvents.add(newEvent);
            }
        }

        return List.copyOf(newEvents);
    }

    private void addToShadowGraph(final PlatformEvent newEvent) {
        try {
            shadowGraph.addEvent(newEvent);
        } catch (ShadowgraphInsertionException e) {
            fail("Something went wrong adding initial events to the shadow graph.", e);
        }
    }

    /**
     * Drains the event queue (events received in a sync), calculates the hash for each, and returns them in a
     * {@code List}.
     */
    public void drainReceivedEventQueue() {
        receivedEventQueue.drainTo(receivedEvents);
        final EventHasher hasher = new DefaultEventHasher();
        receivedEvents.forEach(hasher::hashEvent);
    }

    /**
     * Creates a new instance of {@link ShadowgraphSynchronizer} with the current {@link SyncNode} settings and returns
     * it.
     */
    public ShadowgraphSynchronizer getSynchronizer() {
        final Consumer<PlatformEvent> eventHandler = event -> {
            if (sleepAfterEventReadMillis.get() > 0) {
                try {
                    Thread.sleep(sleepAfterEventReadMillis.get());
                } catch (final InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            receivedEventQueue.add(event);
        };

        // The original sync tests are incompatible with event filtering and reduced sync event counts.
        final Configuration configuration = new TestConfigBuilder()
                .withValue(SyncConfig_.FILTER_LIKELY_DUPLICATES, false)
                .withValue(SyncConfig_.MAX_SYNC_EVENT_COUNT, 0)
                .withValue(
                        EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD,
                        ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        // Lazy initialize this in case the parallel executor changes after construction
        return new ShadowgraphSynchronizer(
                platformContext,
                shadowGraph,
                numNodes,
                mock(SyncMetrics.class),
                eventHandler,
                syncManager,
                mock(IntakeEventCounter.class),
                executor);
    }

    /**
     * <p>Calls the
     * {@link Shadowgraph#updateEventWindow(EventWindow)} method and saves the
     * {@code expireBelow} value for use in validation. For the purposes of these tests, the {@code expireBelow} value
     * becomes the oldest non-expired ancient indicator in the shadow graph returned by
     * {@link SyncNode#getExpirationThreshold()} . In order words, these tests assume there are no reservations prior to
     * the sync that occurs in the test.</p>
     *
     * <p>The {@link SyncNode#getExpirationThreshold()} value is used to determine which events should not be send
     * to the peer because they are expired.</p>
     */
    public void expireBelow(final long expirationThreshold) {
        this.expirationThreshold = expirationThreshold;

        final long ancientThreshold = Math.max(shadowGraph.getEventWindow().getAncientThreshold(), expirationThreshold);

        final EventWindow eventWindow =
                new EventWindow(0 /* ignored by shadowgraph */, ancientThreshold, expirationThreshold, ancientMode);

        updateEventWindow(eventWindow);
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public int getNumNodes() {
        return numNodes;
    }

    public EventEmitter getEmitter() {
        return eventEmitter;
    }

    public Shadowgraph getShadowGraph() {
        return shadowGraph;
    }

    /**
     * Sets the current {@link EventWindow} for the {@link Shadowgraph}.
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        shadowGraph.updateEventWindow(eventWindow);
    }

    public TestingSyncManager getSyncManager() {
        return syncManager;
    }

    public List<PlatformEvent> getReceivedEvents() {
        return receivedEvents;
    }

    public List<EventImpl> getGeneratedEvents() {
        return generatedEvents;
    }

    public void setSaveGeneratedEvents(final boolean saveGeneratedEvents) {
        this.saveGeneratedEvents = saveGeneratedEvents;
    }

    public boolean isCanAcceptSync() {
        return shouldAcceptSync;
    }

    public void setCanAcceptSync(final boolean canAcceptSync) {
        this.shouldAcceptSync = canAcceptSync;
    }

    public boolean isReconnected() {
        return reconnected;
    }

    public void setReconnected(final boolean reconnected) {
        this.reconnected = reconnected;
    }

    public Exception getSyncException() {
        return syncException;
    }

    public void setSyncException(final Exception syncException) {
        this.syncException = syncException;
    }

    public void setParallelExecutor(final ParallelExecutor executor) {
        this.executor = executor;
    }

    public long getCurrentAncientThreshold() {
        return shadowGraph.getEventWindow().getAncientThreshold();
    }

    public long getExpirationThreshold() {
        return expirationThreshold;
    }

    public int getSleepAfterEventReadMillis() {
        return sleepAfterEventReadMillis.get();
    }

    public void setSleepAfterEventReadMillis(final int sleepAfterEventReadMillis) {
        this.sleepAfterEventReadMillis.set(sleepAfterEventReadMillis);
    }

    public void setSynchronizerReturn(final Boolean value) {
        synchronizerReturn.set(value);
    }

    public Boolean getSynchronizerReturn() {
        return synchronizerReturn.get();
    }
}
