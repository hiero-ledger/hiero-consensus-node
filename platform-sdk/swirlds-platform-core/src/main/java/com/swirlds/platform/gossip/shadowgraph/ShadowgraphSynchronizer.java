// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.logging.legacy.LogMarker.SYNC_INFO;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getMyTipsTheyKnow;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.getTheirTipsIHave;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.readEventsINeed;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.readMyTipsTheyHave;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.readTheirTipsAndEventWindow;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.sendEventsTheyNeed;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.writeMyTipsAndEventWindow;
import static com.swirlds.platform.gossip.shadowgraph.SyncUtils.writeTheirTipsIHave;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.framework.Stoppable;
import com.swirlds.common.threading.framework.Stoppable.StopBehavior;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.SyncException;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * The goal of the ShadowgraphSynchronizer is to compare graphs with a remote node, and update them so both sides have
 * the same events in the graph. This process is called a sync.
 * <p>
 * This instance can be called by multiple threads at the same time. To avoid accidental concurrency issues, all the
 * variables in this class are final. The ones that are used for storing information about an ongoing sync are method
 * local.
 */
public class ShadowgraphSynchronizer extends AbstractShadowgraphSynchronizer {

    private static final Logger logger = LogManager.getLogger();

    /**
     * executes tasks in parallel
     */
    private final ParallelExecutor executor;

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

        super(
                platformContext,
                shadowGraph,
                numberOfNodes,
                syncMetrics,
                receivedEventHandler,
                fallenBehindManager,
                intakeEventCounter);
        this.executor = Objects.requireNonNull(executor);

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
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
            throws IOException, ParallelExecutionException, SyncException {

        // accumulates time points for each step in the execution of a single gossip session, used for stats
        // reporting and performance analysis
        final SyncTiming timing = new SyncTiming();
        final List<PlatformEvent> sendList;
        try (final ReservedEventWindow reservation = reserveEventWindow()) {
            connection.initForSync();

            timing.start();

            // Step 1: each peer tells the other about its tips and event windows

            final EventWindow myWindow = reservation.getEventWindow();

            final List<ShadowEvent> myTips = getTips();
            // READ and WRITE event windows numbers & tip hashes
            final TheirTipsAndEventWindow theirTipsAndEventWindow = readWriteParallel(
                    readTheirTipsAndEventWindow(connection, numberOfNodes),
                    writeMyTipsAndEventWindow(connection, myWindow, myTips),
                    connection);
            timing.setTimePoint(1);

            syncMetrics.eventWindow(myWindow, theirTipsAndEventWindow.eventWindow());

            if (hasFallenBehind(myWindow, theirTipsAndEventWindow.eventWindow(), connection.getOtherId())
                    != SyncFallenBehindStatus.NONE_FALLEN_BEHIND) {
                // aborting the sync since someone has fallen behind
                return false;
            }

            // events that I know they already have
            final Set<ShadowEvent> eventsTheyHave = new HashSet<>();

            // process the hashes received
            final List<ShadowEvent> theirTips = shadows(theirTipsAndEventWindow.tips());

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
                new SyncResult(connection.getOtherId(), eventsRead, sendList.size()), connection.isOutbound());

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
     * {@inheritDoc}
     */
    @Override
    public void start() {
        executor.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        // this part is pretty horrible - there is no real production reason for executor to be passed and managed
        // from outside of this class; unfortunately, a lot of testing code around SyncNode misuses the executor
        // to inject network behaviour in various places; refactoring that is a huge task, so for now, we need to live
        // with test-specific limitations in production code
        if (executor instanceof final Stoppable stoppable) {
            stoppable.stop(StopBehavior.INTERRUPTABLE);
        }
    }
}
