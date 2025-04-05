// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.logging.legacy.LogMarker.SYNC_INFO;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.filterLikelyDuplicates;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getMyTipsTheyKnow;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getTheirTipsIHave;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.readEventsINeed;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.readMyTipsTheyHave;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.readTheirTipsAndEventWindow;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.sendEventsTheyNeed;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.writeMyTipsAndEventWindow;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.writeTheirTipsIHave;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.framework.Stoppable;
import com.swirlds.common.threading.framework.Stoppable.StopBehavior;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.SyncException;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * The goal of the ShadowgraphSynchronizer is to compare graphs with a remote node, and update them so both sides have
 * the same events in the graph. This process is called a sync.
 * <p>
 * This instance can be called by multiple threads at the same time. To avoid accidental concurrency issues, all the
 * variables in this class are final. The ones that are used for storing information about an ongoing sync are method
 * local.
 */
public class ShadowgraphSynchronizer {

    private static final Logger logger = LogManager.getLogger();

    /**
     * The shadow graph manager to use for this sync
     */
    private final Shadowgraph shadowGraph;

    /**
     * Number of member nodes in the network for this sync
     */
    private final int numberOfNodes;

    /**
     * All sync stats
     */
    private final SyncMetrics syncMetrics;

    /**
     * consumes events received by the peer
     */
    private final Consumer<PlatformEvent> eventHandler;

    /**
     * manages sync related decisions
     */
    private final FallenBehindManager fallenBehindManager;

    /**
     * Keeps track of how many events from each peer have been received, but haven't yet made it through the intake
     * pipeline
     */
    private final IntakeEventCounter intakeEventCounter;

    /**
     * executes tasks in parallel
     */
    private final ParallelExecutor executor;

    private final Time time;

    /**
     * If true then we do not send all events during a sync that the peer says we need. Instead, we send events that we
     * know are unlikely to be duplicates (e.g. self events), and only send other events if we have had them for a long
     * time and the peer still needs them.
     */
    private final boolean filterLikelyDuplicates;

    /**
     * For events that are neither self events nor ancestors of self events, we must have had this event for at least
     * this amount of time before it is eligible to be sent. Ignored if {@link #filterLikelyDuplicates} is false.
     */
    private final Duration nonAncestorFilterThreshold;

    /**
     * The maximum number of events to send in a single sync, or 0 if there is no limit.
     */
    private final int maximumEventsPerSync;

    /**
     * The current ancient mode.
     */
    private final AncientMode ancientMode;

    /**
     * Constructs a new ShadowgraphSynchronizer.
     *
     * @param platformContext      the platform context
     * @param shadowGraph          stores events to sync
     * @param numberOfNodes        number of nodes in the network
     * @param syncMetrics          metrics for sync
     * @param receivedEventHandler events that are received are passed here
     * @param fallenBehindManager  tracks if we have fallen behind
     * @param intakeEventCounter   used for tracking events in the intake pipeline per peer
     * @param executor             for executing read/write tasks in parallel
     */
    public ShadowgraphSynchronizer(
            @NonNull final PlatformContext platformContext,
            @NonNull final Shadowgraph shadowGraph,
            final int numberOfNodes,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Consumer<PlatformEvent> receivedEventHandler,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final ParallelExecutor executor) {

        Objects.requireNonNull(platformContext);

        this.time = platformContext.getTime();
        this.shadowGraph = Objects.requireNonNull(shadowGraph);
        this.numberOfNodes = numberOfNodes;
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.executor = Objects.requireNonNull(executor);
        this.eventHandler = Objects.requireNonNull(receivedEventHandler);

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        this.nonAncestorFilterThreshold = syncConfig.nonAncestorFilterThreshold();

        this.filterLikelyDuplicates = syncConfig.filterLikelyDuplicates();
        this.maximumEventsPerSync = syncConfig.maxSyncEventCount();

        this.ancientMode = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();
    }

    /**
     * Executes a sync using the supplied connection.
     *
     * @param platformContext the platform context
     * @param connection      the connection to use
     * @return true if the sync was successful, false if it was aborted
     */
    public boolean synchronize(@NonNull final PlatformContext platformContext, @NonNull final Connection connection)
            throws IOException, ParallelExecutionException, SyncException, InterruptedException {
        logger.info(SYNC_INFO.getMarker(), "{} sync start", connection.getDescription());
        try {
            return reserveSynchronize(platformContext, connection);
        } finally {
            logger.info(SYNC_INFO.getMarker(), "{} sync end", connection.getDescription());
        }
    }

    /**
     * Executes a sync using the supplied connection.
     *
     * @param platformContext the platform context
     * @param connection      the connection to use
     * @return true if the sync was successful, false if it was aborted
     */
    private boolean reserveSynchronize(
            @NonNull final PlatformContext platformContext, @NonNull final Connection connection)
            throws IOException, ParallelExecutionException, SyncException, InterruptedException {

        // accumulates time points for each step in the execution of a single gossip session, used for stats
        // reporting and performance analysis
        final SyncTiming timing = new SyncTiming();
        final List<PlatformEvent> sendList;
        try (final ReservedEventWindow reservation = shadowGraph.reserve()) {
            connection.initForSync();

            timing.start();

            // Step 1: each peer tells the other about its tips and event windows

            final EventWindow myWindow = reservation.getEventWindow();

            final List<ShadowEvent> myTips = getTips();
            // READ and WRITE event windows numbers & tip hashes
            final TheirTipsAndEventWindow theirTipsAndEventWindow = readWriteParallel(
                    readTheirTipsAndEventWindow(connection, numberOfNodes, ancientMode),
                    writeMyTipsAndEventWindow(connection, myWindow, myTips),
                    connection);
            timing.setTimePoint(1);

            syncMetrics.eventWindow(myWindow, theirTipsAndEventWindow.eventWindow());

            if (fallenBehind(myWindow, theirTipsAndEventWindow.eventWindow(), connection)) {
                // aborting the sync since someone has fallen behind
                return false;
            }

            // events that I know they already have
            final Set<ShadowEvent> eventsTheyHave = new HashSet<>();

            // process the hashes received
            final List<ShadowEvent> theirTips = shadowGraph.shadows(theirTipsAndEventWindow.tips());

            // For each tip they send us, determine if we have that event.
            // For each tip, send true if we have the event and false if we don't.
            final List<Boolean> theirTipsIHave = getTheirTipsIHave(theirTips);

            // Add their tips to the set of events they are known to have
            theirTips.stream().filter(Objects::nonNull).forEach(eventsTheyHave::add);

            // Step 2: each peer tells the other which of the other's tips it already has.

            timing.setTimePoint(2);
            final List<Boolean> theirBooleans = readWriteParallel(
                    readMyTipsTheyHave(connection, myTips.size()),
                    writeTheirTipsIHave(connection, theirTipsIHave),
                    connection);
            timing.setTimePoint(3);

            // Add each tip they know to the known set
            final List<ShadowEvent> knownTips = getMyTipsTheyKnow(connection, myTips, theirBooleans);
            eventsTheyHave.addAll(knownTips);

            // create a send list based on the known set
            sendList = createSendList(
                    connection.getSelfId(), eventsTheyHave, myWindow, theirTipsAndEventWindow.eventWindow());
        }

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);

        return sendAndReceiveEvents(
                connection, timing, sendList, syncConfig.syncKeepalivePeriod(), syncConfig.maxSyncTime());
    }

    @NonNull
    private List<ShadowEvent> getTips() {
        final List<ShadowEvent> myTips = shadowGraph.getTips();
        syncMetrics.updateTipsPerSync(myTips.size());
        syncMetrics.updateMultiTipsPerSync(SyncUtils.computeMultiTipCount(myTips));
        return myTips;
    }

    /**
     * Decide if we have fallen behind with respect to this peer.
     *
     * @param self       our event window
     * @param other      their event window
     * @param connection the connection to use
     * @return true if we have fallen behind, false otherwise
     */
    private boolean fallenBehind(
            @NonNull final EventWindow self, @NonNull final EventWindow other, @NonNull final Connection connection) {
        Objects.requireNonNull(self);
        Objects.requireNonNull(other);
        Objects.requireNonNull(connection);

        final SyncFallenBehindStatus status = SyncFallenBehindStatus.getStatus(self, other);
        if (status == SyncFallenBehindStatus.SELF_FALLEN_BEHIND) {
            fallenBehindManager.reportFallenBehind(connection.getOtherId());
        }

        if (status != SyncFallenBehindStatus.NONE_FALLEN_BEHIND) {
            logger.info(SYNC_INFO.getMarker(), "{} aborting sync due to {}", connection.getDescription(), status);
            return true; // abort the sync
        }
        return false;
    }

    /**
     * Create a list of events to send to the peer.
     *
     * @param selfId           the id of this node
     * @param knownSet         the set of events that the peer already has (this is incomplete at this stage and is
     *                         added to during this method)
     * @param myEventWindow    the event window of this node
     * @param theirEventWindow the event window of the peer
     * @return a list of events to send to the peer
     */
    @NonNull
    private List<PlatformEvent> createSendList(
            @NonNull final NodeId selfId,
            @NonNull final Set<ShadowEvent> knownSet,
            @NonNull final EventWindow myEventWindow,
            @NonNull final EventWindow theirEventWindow) {

        Objects.requireNonNull(selfId);
        Objects.requireNonNull(knownSet);
        Objects.requireNonNull(myEventWindow);
        Objects.requireNonNull(theirEventWindow);

        // add to knownSet all the ancestors of each known event
        final Set<ShadowEvent> knownAncestors = shadowGraph.findAncestors(
                knownSet, SyncUtils.unknownNonAncient(knownSet, myEventWindow, theirEventWindow, ancientMode));

        // since knownAncestors is a lot bigger than knownSet, it is a lot cheaper to add knownSet to knownAncestors
        // then vice versa
        knownAncestors.addAll(knownSet);

        syncMetrics.knownSetSize(knownAncestors.size());

        // predicate used to search for events to send
        final Predicate<ShadowEvent> knownAncestorsPredicate =
                SyncUtils.unknownNonAncient(knownAncestors, myEventWindow, theirEventWindow, ancientMode);

        // in order to get the peer the latest events, we get a new set of tips to search from
        final List<ShadowEvent> myNewTips = shadowGraph.getTips();

        // find all ancestors of tips that are not known
        final List<ShadowEvent> unknownTips =
                myNewTips.stream().filter(knownAncestorsPredicate).collect(Collectors.toList());
        final Set<ShadowEvent> sendSet = shadowGraph.findAncestors(unknownTips, knownAncestorsPredicate);
        // add the tips themselves
        sendSet.addAll(unknownTips);

        final List<PlatformEvent> eventsTheyMayNeed =
                sendSet.stream().map(ShadowEvent::getEvent).collect(Collectors.toCollection(ArrayList::new));

        List<PlatformEvent> sendList;
        if (filterLikelyDuplicates) {
            final long startFilterTime = time.nanoTime();
            sendList = filterLikelyDuplicates(selfId, nonAncestorFilterThreshold, time.now(), eventsTheyMayNeed);
            final long endFilterTime = time.nanoTime();
            syncMetrics.recordSyncFilterTime(endFilterTime - startFilterTime);
        } else {
            sendList = eventsTheyMayNeed;
        }

        SyncUtils.sort(eventsTheyMayNeed);

        if (maximumEventsPerSync > 0 && sendList.size() > maximumEventsPerSync) {
            sendList = sendList.subList(0, maximumEventsPerSync);
        }

        return sendList;
    }

    /**
     * By this point in time, we have figured out which events we want to send the peer, and the peer has figured out
     * which events it wants to send us. In parallel, send and receive those events.
     *
     * @param connection          the connection to use
     * @param timing              metrics that track sync timing
     * @param sendList            the events to send
     * @param syncKeepAlivePeriod the period at which the reading thread should send keepalive messages
     * @param maxSyncTime         the maximum amount of time to spend syncing with a peer, syncs that take longer than
     *                            this will be aborted
     * @return true if the phase was successful, false if it was aborted
     * @throws ParallelExecutionException if anything goes wrong
     */
    private boolean sendAndReceiveEvents(
            @NonNull final Connection connection,
            @NonNull final SyncTiming timing,
            @NonNull final List<PlatformEvent> sendList,
            @NonNull final Duration syncKeepAlivePeriod,
            @NonNull final Duration maxSyncTime)
            throws ParallelExecutionException {

        Objects.requireNonNull(connection);
        Objects.requireNonNull(sendList);

        timing.setTimePoint(4);
        // the reading thread uses this to indicate to the writing thread that it is done
        final CountDownLatch eventReadingDone = new CountDownLatch(1);
        // the writer will set it to true if writing is aborted
        final AtomicBoolean writeAborted = new AtomicBoolean(false);
        final Integer eventsRead = readWriteParallel(
                readEventsINeed(
                        connection,
                        eventHandler,
                        maximumEventsPerSync,
                        syncMetrics,
                        eventReadingDone,
                        intakeEventCounter,
                        maxSyncTime),
                sendEventsTheyNeed(connection, sendList, eventReadingDone, writeAborted, syncKeepAlivePeriod),
                connection);
        if (eventsRead < 0 || writeAborted.get()) {
            // sync was aborted
            logger.info(SYNC_INFO.getMarker(), "{} sync aborted", connection::getDescription);
            return false;
        }
        logger.info(
                SYNC_INFO.getMarker(),
                "{} writing events done, wrote {} events",
                connection.getDescription(),
                sendList.size());
        logger.info(
                SYNC_INFO.getMarker(),
                "{} reading events done, read {} events",
                connection.getDescription(),
                eventsRead);

        syncMetrics.syncDone(
                new SyncResult(connection.isOutbound(), connection.getOtherId(), eventsRead, sendList.size()));

        timing.setTimePoint(5);
        syncMetrics.recordSyncTiming(timing, connection);
        return true;
    }

    /**
     * A method to do reads and writes in parallel.
     * <p>
     * It is very important that the read task is executed by the caller thread. The reader thread can always time out,
     * if the writer thread gets blocked by a write method because the buffer is full, the only way to unblock it is to
     * close the connection. So the reader will close the connection and unblock the writer if it times out or if
     * anything goes wrong.
     *
     * @param readTask   read task
     * @param writeTask  write task
     * @param connection the connection to close if anything goes wrong
     * @param <T>        the return type of the read task and this method
     * @return whatever the read task returns
     * @throws ParallelExecutionException thrown if anything goes wrong during these read write operations. the
     *                                    connection will be closed before this exception is thrown
     */
    @Nullable
    private <T> T readWriteParallel(
            @NonNull final Callable<T> readTask,
            @NonNull final Callable<Void> writeTask,
            @NonNull final Connection connection)
            throws ParallelExecutionException {

        Objects.requireNonNull(readTask);
        Objects.requireNonNull(writeTask);
        Objects.requireNonNull(connection);

        return executor.doParallel(readTask, writeTask, connection::disconnect);
    }

    /**
     * Clear the internal state of the gossip engine.
     */
    public void clear() {
        this.shadowGraph.clear();
    }

    /**
     * Events sent here should be gossiped to the network
     * @param platformEvent event to be sent outside
     */
    public void addEvent(@NonNull final PlatformEvent platformEvent) {
        this.shadowGraph.addEvent(platformEvent);
    }

    /**
     * Updates the current event window (mostly ancient thresholds)
     * @param eventWindow new event window to apply
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        this.shadowGraph.updateEventWindow(eventWindow);
    }

    /**
     * Starts helper threads needed for synchronizing shadowgraph
     */
    public void start() {
        executor.start();
    }

    /**
     * Stops helper threads needed for synchronizing shadowgraph
     */
    public void stop() {
        // this part is pretty horrible - there is no real production reason for executor to be passed and managed
        // from outside of this class; unfortunately, a lot of testing code around SyncNode misuses the executor
        // to inject network behaviour in various places; refactoring that is a huge task, so for now, we need to live
        // with test-specific limitations in production code
        if (executor instanceof Stoppable stoppable) {
            stoppable.stop(StopBehavior.INTERRUPTABLE);
        }
    }
}
