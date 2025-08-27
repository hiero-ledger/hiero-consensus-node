// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import static com.swirlds.logging.legacy.LogMarker.SYNC_INFO;
import static org.hiero.base.CompareTo.isGreaterThan;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.SyncException;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.ByteConstants;
import com.swirlds.platform.network.Connection;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.ThrowingRunnable;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Various static utility method used in syncing
 */
public final class SyncUtils {

    private static final Logger logger = LogManager.getLogger();

    /**
     * Private constructor to never instantiate this class
     */
    private SyncUtils() {}

    /**
     * Send the tips and event window to the peer. This is the first data exchanged during a sync (after protocol
     * negotiation). The complementary function to {@link #readTheirTipsAndEventWindow(Connection, int)}.
     *
     * @param connection  the connection to write to
     * @param eventWindow the event window to write
     * @param tips        the tips to write
     * @return a {@link Callable} that writes the tips and event window
     */
    public static ThrowingRunnable writeMyTipsAndEventWindow(
            @NonNull final Connection connection,
            @NonNull final EventWindow eventWindow,
            @NonNull final List<ShadowEvent> tips) {
        return () -> {
            final List<Hash> tipHashes =
                    tips.stream().map(ShadowEvent::getEventBaseHash).collect(Collectors.toList());

            serializeEventWindow(connection.getDos(), eventWindow);

            connection.getDos().writeTipHashes(tipHashes);
            connection.getDos().flush();
            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                logger.debug(
                        SYNC_INFO.getMarker(),
                        "{} sent event window: {}",
                        connection::getDescription,
                        eventWindow::toString);
                logger.debug(
                        SYNC_INFO.getMarker(),
                        "{} sent tips: {}",
                        connection::getDescription,
                        () -> SyncLogging.toShortShadows(tips));
            }
        };
    }

    /**
     * Read the tips and event window from the peer. This is the first data exchanged during a sync (after protocol
     * negotiation). The complementary function to {@link #writeMyTipsAndEventWindow(Connection, EventWindow, List)}.
     *
     * @param connection    the connection to read from
     * @param numberOfNodes the number of nodes in the network
     * @return a {@link Callable} that reads the tips and event window
     */
    public static Callable<TheirTipsAndEventWindow> readTheirTipsAndEventWindow(
            final Connection connection, final int numberOfNodes) {
        return () -> {
            final EventWindow eventWindow = deserializeEventWindow(connection.getDis());

            final List<Hash> tips = connection.getDis().readTipHashes(numberOfNodes);

            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                logger.debug(
                        SYNC_INFO.getMarker(),
                        "{} received event window: {}",
                        connection::getDescription,
                        eventWindow::toString);
                logger.debug(
                        SYNC_INFO.getMarker(),
                        "{} received tips: {}",
                        connection::getDescription,
                        () -> SyncLogging.toShortHashes(tips));
            }

            return new TheirTipsAndEventWindow(eventWindow, tips);
        };
    }

    /**
     * Tell the sync peer which of their tips I have. The complementary function to
     * {@link #readMyTipsTheyHave(Connection, int)}.
     *
     * @param connection     the connection to write to
     * @param theirTipsIHave for each tip they sent me, write true if I have it, false otherwise. Order corresponds to
     *                       the order in which they sent me their tips.
     * @return a {@link Callable} that writes the booleans
     */
    public static ThrowingRunnable writeTheirTipsIHave(
            final Connection connection, final List<Boolean> theirTipsIHave) {
        return () -> {
            connection.getDos().writeBooleanList(theirTipsIHave);
            connection.getDos().flush();
            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                logger.debug(
                        SYNC_INFO.getMarker(),
                        "{} sent booleans: {}",
                        connection::getDescription,
                        () -> SyncLogging.toShortBooleans(theirTipsIHave));
            }
        };
    }

    /**
     * Read from the peer which of my tips they have. The complementary function to
     * {@link #writeTheirTipsIHave(Connection, List)}.
     *
     * @param connection   the connection to read from
     * @param numberOfTips the number of tips I sent them
     * @return a {@link Callable} that reads the booleans
     */
    public static Callable<List<Boolean>> readMyTipsTheyHave(final Connection connection, final int numberOfTips) {
        return () -> {
            final List<Boolean> booleans = connection.getDis().readBooleanList(numberOfTips);
            if (booleans == null) {
                throw new SyncException(connection, "peer sent null booleans");
            }
            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                logger.debug(
                        SYNC_INFO.getMarker(),
                        "{} received booleans: {}",
                        connection::getDescription,
                        () -> SyncLogging.toShortBooleans(booleans));
            }
            return booleans;
        };
    }

    /**
     * Send the events the peer needs. The complementary function to
     * {@link #readEventsINeed(Connection, Consumer, int, SyncMetrics, CountDownLatch, IntakeEventCounter, Duration)}.
     *
     * @param connection          the connection to write to
     * @param events              the events to write
     * @param eventReadingDone    used to know when the writing thread is done
     * @param writeAborted        set to true if writing is aborted
     * @param syncKeepalivePeriod send a keepalive message every this many milliseconds when writing events during a
     *                            sync
     * @return A {@link Callable} that executes this part of the sync
     */
    public static ThrowingRunnable sendEventsTheyNeed(
            final Connection connection,
            final List<PlatformEvent> events,
            final CountDownLatch eventReadingDone,
            final AtomicBoolean writeAborted,
            final Duration syncKeepalivePeriod) {
        return () -> {
            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                logger.debug(
                        SYNC_INFO.getMarker(),
                        "{} writing events start. send list size: {}",
                        connection.getDescription(),
                        events.size());
            }
            for (final PlatformEvent event : events) {
                connection.getDos().writeByte(ByteConstants.COMM_EVENT_NEXT);
                connection.getDos().writePbjRecord(event.getGossipEvent(), GossipEvent.PROTOBUF);
            }
            if (writeAborted.get()) {
                logger.info(SYNC_INFO.getMarker(), "{} writing events aborted", connection.getDescription());
            } else {
                if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                    logger.debug(
                            SYNC_INFO.getMarker(),
                            "{} writing events done, wrote {} events",
                            connection.getDescription(),
                            events.size());
                }
                connection.getDos().writeByte(ByteConstants.COMM_EVENT_DONE);
            }
            connection.getDos().flush();

            // if we are still reading events, send keepalive messages
            while (!eventReadingDone.await(syncKeepalivePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
                connection.getDos().writeByte(ByteConstants.COMM_SYNC_ONGOING);
                connection.getDos().flush();
            }

            // we have now finished reading and writing all the events of a sync. the remote node may not have
            // finished reading and processing all the events this node has sent. so we write a byte to tell the remote
            // node we have finished, and the reader will wait for it to send us the same byte.
            connection.getDos().writeByte(ByteConstants.COMM_SYNC_DONE);
            connection.getDos().flush();

            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                logger.debug(SYNC_INFO.getMarker(), "{} sent COMM_SYNC_DONE", connection.getDescription());
            }
        };
    }

    /**
     * Read events from the peer that I need. The complementary function to
     * {@link #sendEventsTheyNeed(Connection, List, CountDownLatch, AtomicBoolean, Duration)}.
     *
     * @param connection         the connection to read from
     * @param eventHandler       the consumer of received events
     * @param maxEventCount      the maximum number of events to read, or 0 for no limit
     * @param syncMetrics        tracks event reading metrics
     * @param eventReadingDone   used to notify the writing thread that reading is done
     * @param intakeEventCounter keeps track of the number of events in the intake pipeline from each peer
     * @param maxSyncTime        the maximum amount of time to spend syncing with a peer, syncs that take longer than
     *                           this will be aborted
     * @return A {@link Callable} that executes this part of the sync
     */
    public static Callable<Integer> readEventsINeed(
            final Connection connection,
            final Consumer<PlatformEvent> eventHandler,
            final int maxEventCount,
            final SyncMetrics syncMetrics,
            final CountDownLatch eventReadingDone,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Duration maxSyncTime) {

        return () -> {
            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                logger.debug(SYNC_INFO.getMarker(), "{} reading events start", connection.getDescription());
            }
            int eventsRead = 0;
            try {
                final long startTime = System.nanoTime();
                int count = 0;
                while (true) {
                    // readByte() will throw a timeout exception if the socket timeout is exceeded
                    final byte next = connection.getDis().readByte();
                    // if the peer continuously sends COMM_SYNC_ONGOING, or sends the data really slowly,
                    // this timeout will be triggered
                    checkEventExchangeTime(maxSyncTime, startTime);
                    switch (next) {
                        case ByteConstants.COMM_EVENT_NEXT -> {
                            if (maxEventCount > 0) {
                                count++;
                                if (count > maxEventCount) {
                                    throw new IOException("max event count " + maxEventCount + " exceeded");
                                }
                            }
                            final GossipEvent gossipEvent = connection.getDis().readPbjRecord(GossipEvent.PROTOBUF);
                            final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);

                            platformEvent.setSenderId(connection.getOtherId());
                            intakeEventCounter.eventEnteredIntakePipeline(connection.getOtherId());

                            eventHandler.accept(platformEvent);
                            eventsRead++;
                        }
                        case ByteConstants.COMM_EVENT_ABORT -> {
                            logger.info(
                                    SYNC_INFO.getMarker(), "{} reading events aborted", connection.getDescription());
                            // event reading was aborted, tell the writer thread to send a COMM_SYNC_DONE
                            eventReadingDone.countDown();
                            eventsRead = Integer.MIN_VALUE;
                        }
                        case ByteConstants.COMM_EVENT_DONE -> {
                            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                                logger.debug(
                                        SYNC_INFO.getMarker(),
                                        "{} reading events done, read {} events",
                                        connection.getDescription(),
                                        eventsRead);
                            }
                            syncMetrics.eventsReceived(startTime, eventsRead);
                            // we are done reading event, tell the writer thread to send a COMM_SYNC_DONE
                            eventReadingDone.countDown();
                        }
                        // while we are waiting for the peer to tell us they are done, they might send
                        // COMM_SYNC_ONGOING
                        // if they are still busy reading events
                        case ByteConstants.COMM_SYNC_ONGOING -> {
                            // peer is still reading events, waiting for them to finish
                            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                                logger.debug(
                                        SYNC_INFO.getMarker(),
                                        "{} received COMM_SYNC_ONGOING",
                                        connection.getDescription());
                            }
                        }
                        case ByteConstants.COMM_SYNC_DONE -> {
                            if (logger.isDebugEnabled(SYNC_INFO.getMarker())) {
                                logger.debug(
                                        SYNC_INFO.getMarker(),
                                        "{} received COMM_SYNC_DONE",
                                        connection.getDescription());
                            }
                            return eventsRead;
                        }
                        default ->
                            throw new SyncException(
                                    connection,
                                    String.format("while reading events, received unexpected byte %02x", next));
                    }
                }
            } finally {
                // in case an exception gets thrown, unblock the writer thread
                eventReadingDone.countDown();
            }
        };
    }

    /**
     * Checks if the time spent sending and receiving events exceeds maximum allowed. If it has, it throws an
     * exception.
     *
     * @param maxSyncTime the maximum amount of time to spend syncing with a peer, syncs that take longer than this will
     *                    be aborted
     * @param startTime   the time at which sending and receiving of events started
     * @throws SyncTimeoutException thrown if the time is exceeded
     */
    private static void checkEventExchangeTime(@NonNull final Duration maxSyncTime, final long startTime)
            throws SyncTimeoutException {
        final long syncTime = System.nanoTime() - startTime;
        if (syncTime > maxSyncTime.toNanos()) {
            throw new SyncTimeoutException(Duration.ofNanos(syncTime), maxSyncTime);
        }
    }

    /**
     * Given a list of events we think the other node may not have, reduce that list to events that we think they do not
     * have and that are unlikely to end up being duplicate events.
     *
     * <p>
     * General principles:
     * <ul>
     * <li>Always send self events right away.</li>
     * <li>Don't send non-ancestors of self events unless we've known about that event for a long time.</li>
     * </ul>
     *
     * @param selfId               the id of this node
     * @param nonAncestorThreshold for each event that is not a self event and is not an ancestor of a self event, the
     *                             amount of time the event must be known about before it is eligible to be sent
     * @param now                  the current time
     * @param eventsTheyNeed       the list of events we think they need, expected to be in topological order
     * @return the events that should be actually sent, will be a subset of the eventsTheyNeed list
     */
    @NonNull
    public static List<PlatformEvent> filterLikelyDuplicates(
            @NonNull final NodeId selfId,
            @NonNull final Duration nonAncestorThreshold,
            @NonNull final Instant now,
            @NonNull final List<PlatformEvent> eventsTheyNeed) {

        final LinkedList<PlatformEvent> filteredList = new LinkedList<>();

        final Set<Hash> parentHashesOfEventsToSend = new HashSet<>();

        // Iterate backwards over the events the peer needs, which are in topological order. This allows us to
        // find all ancestors of events we plan on sending, and to send those as well. Events are added to the
        // filtered list in reverse order, resulting in a list that is in topological order.

        for (int index = eventsTheyNeed.size() - 1; index >= 0; index--) {
            final PlatformEvent event = eventsTheyNeed.get(index);

            final boolean sendEvent =
                    // Always send self events
                    event.getCreatorId().equals(selfId)
                            ||
                            // Always send parents of other events we plan to send
                            parentHashesOfEventsToSend.contains(event.getHash())
                            ||
                            // Send all other events if we've known about it for long enough
                            haveWeKnownAboutEventForALongTime(event, nonAncestorThreshold, now);

            if (sendEvent) {
                // If we've decided to send an event, we also want to send its parents if those parents are needed
                // by the peer.
                filteredList.addFirst(event);
                for (final EventDescriptorWrapper otherParent : event.getAllParents()) {
                    parentHashesOfEventsToSend.add(otherParent.hash());
                }
            }
        }

        return filteredList;
    }

    /**
     * Decide if we've known about an event for long enough to make it eligible to be sent.
     *
     * @param event                the event to check
     * @param nonAncestorThreshold the amount of time the event must be known about before it is eligible to be sent
     * @param now                  the current time
     * @return true if we've known about the event for long enough, false otherwise
     */
    private static boolean haveWeKnownAboutEventForALongTime(
            @NonNull final PlatformEvent event,
            @NonNull final Duration nonAncestorThreshold,
            @NonNull final Instant now) {
        final Instant eventReceivedTime = event.getTimeReceived();
        final Duration timeKnown = Duration.between(eventReceivedTime, now);
        return isGreaterThan(timeKnown, nonAncestorThreshold);
    }

    /**
     * Returns a predicate that determines if a {@link ShadowEvent}'s ancient indicator is non-ancient for the peer and
     * greater than this node's minimum non-expired threshold, and is not already known.
     *
     * @param knownShadows     the {@link ShadowEvent}s that are already known and should therefore be rejected by the
     *                         predicate
     * @param myEventWindow    the event window of this node
     * @param theirEventWindow the event window of the peer node
     * @return the predicate
     */
    @NonNull
    public static Predicate<ShadowEvent> unknownNonAncient(
            @NonNull final Collection<ShadowEvent> knownShadows,
            @NonNull final EventWindow myEventWindow,
            @NonNull final EventWindow theirEventWindow) {

        // When searching for events, we don't want to send any events that are known to be ancient to the peer.
        // We should never be syncing with a peer if their ancient threshold is less than our expired threshold
        // (if this is the case, then the peer is "behind"), so in practice the minimumSearchThreshold will always
        // be the same as the peer's ancient threshold. However, in an abundance of caution, we use the maximum of
        // the two thresholds to ensure that we don't ever attempt to traverse over events that are expired to us,
        // since those events may be unlinked and could cause race conditions if accessed.

        final long minimumSearchThreshold =
                Math.max(myEventWindow.expiredThreshold(), theirEventWindow.ancientThreshold());
        return s -> s.getEvent().getBirthRound() >= minimumSearchThreshold && !knownShadows.contains(s);
    }

    /**
     * Computes the number of creators that have more than one tip. If a single creator has more than two tips, this
     * method will only report once for each such creator. The execution time cost for this method is O(T + N) where T
     * is the number of tips including all forks and N is the number of network nodes. There is some memory overhead,
     * but it is fairly nominal in favor of the time complexity savings.
     *
     * @return the number of event creators that have more than one tip.
     */
    public static int computeMultiTipCount(Iterable<ShadowEvent> tips) {
        // The number of tips per creator encountered when iterating over the sending tips
        final Map<NodeId, Integer> tipCountByCreator = new HashMap<>();

        // Make a single O(N) where N is the number of tips including all forks. Typically, N will be equal to the
        // number of network nodes.
        for (final ShadowEvent tip : tips) {
            tipCountByCreator.compute(tip.getEvent().getCreatorId(), (k, v) -> (v != null) ? (v + 1) : 1);
        }

        // Walk the entrySet() which is O(N) where N is the number network nodes. This is still more efficient than a
        // O(N^2) loop.
        int creatorsWithForks = 0;
        for (final Map.Entry<NodeId, Integer> entry : tipCountByCreator.entrySet()) {
            // If the number of tips for a given creator is greater than 1 then we have a fork.
            // This map is broken down by creator ID already as the key so this is guaranteed to be a single increment
            // for each creator with a fork. Therefore, this holds to the method contract.
            if (entry.getValue() > 1) {
                creatorsWithForks++;
            }
        }

        return creatorsWithForks; // total number of unique creators with more than one tip
    }

    /**
     * Performs a topological sort on the given list of events (i.e. where parents always come before their children).
     *
     * @param sendList The list of events to sort.
     */
    static void sort(@NonNull final List<PlatformEvent> sendList) {
        // Note: regardless of ancient mode, sorting uses generations and not birth rounds.
        //       Sorting by generations yields a list in topological order, sorting by birth rounds does not.
        sendList.sort(Comparator.comparingLong(PlatformEvent::getNGen));
    }

    /**
     * For each tip they send us, determine if we have that event. For each tip, send true if we have the event and
     * false if we don't.
     *
     * @param theirTipShadows the tips they sent us
     * @return a list of booleans corresponding to their tips in the order they were sent. True if we have the event,
     * false if we don't
     */
    @NonNull
    static List<Boolean> getTheirTipsIHave(@NonNull final List<ShadowEvent> theirTipShadows) {
        final List<Boolean> myBooleans = new ArrayList<>(theirTipShadows.size());
        for (final ShadowEvent s : theirTipShadows) {
            myBooleans.add(s != null); // is this event is known to me
        }
        return myBooleans;
    }

    /**
     * For each tip sent to the peer, determine if they have that event. If they have it, add it to the list that is
     * returned.
     *
     * @param connection     the connection to use
     * @param myTips         the tips we sent them
     * @param myTipsTheyHave a list of booleans corresponding to our tips in the order they were sent. True if they have
     *                       the event, false if they don't
     * @return a list of tips that they have
     */
    @NonNull
    static List<ShadowEvent> getMyTipsTheyKnow(
            @NonNull final Connection connection,
            @NonNull final List<ShadowEvent> myTips,
            @NonNull final List<Boolean> myTipsTheyHave)
            throws SyncException {

        Objects.requireNonNull(connection);

        if (myTipsTheyHave.size() != myTips.size()) {
            throw new SyncException(
                    connection,
                    String.format(
                            "peer booleans list is wrong size. Expected: %d Actual: %d,",
                            myTips.size(), myTipsTheyHave.size()));
        }
        return getMyTipsTheyKnow(myTips, myTipsTheyHave);
    }

    /**
     * For each tip sent to the peer, determine if they have that event. If they have it, add it to the list that is
     * returned.
     *
     * @param peerId         the peer node id
     * @param myTips         the tips we sent them
     * @param myTipsTheyHave a list of booleans corresponding to our tips in the order they were sent. True if they have
     *                       the event, false if they don't
     * @return a list of tips that they have
     */
    @NonNull
    static List<ShadowEvent> getMyTipsTheyKnow(
            @NonNull final NodeId peerId,
            @NonNull final List<ShadowEvent> myTips,
            @NonNull final List<Boolean> myTipsTheyHave) {

        Objects.requireNonNull(peerId);

        if (myTipsTheyHave.size() != myTips.size()) {
            throw new RuntimeException(String.format(
                    "during sync with %s, peer booleans list is wrong size. Expected: %d Actual: %d,",
                    peerId, myTips.size(), myTipsTheyHave.size()));
        }
        return getMyTipsTheyKnow(myTips, myTipsTheyHave);
    }

    @NonNull
    private static List<ShadowEvent> getMyTipsTheyKnow(
            @NonNull final List<ShadowEvent> myTips, @NonNull final List<Boolean> myTipsTheyHave) {
        final List<ShadowEvent> knownTips = new ArrayList<>();
        // process their booleans
        for (int i = 0; i < myTipsTheyHave.size(); i++) {
            if (Boolean.TRUE.equals(myTipsTheyHave.get(i))) {
                knownTips.add(myTips.get(i));
            }
        }

        return knownTips;
    }

    /**
     * Serialize an event window to the given output stream.
     *
     * @param out         the output stream
     * @param eventWindow the event window
     */
    public static void serializeEventWindow(
            @NonNull final SerializableDataOutputStream out, @NonNull final EventWindow eventWindow)
            throws IOException {

        out.writeLong(eventWindow.latestConsensusRound());
        out.writeLong(eventWindow.ancientThreshold());
        out.writeLong(eventWindow.expiredThreshold());

        // Intentionally don't bother writing ancient mode, the peer will always be using the same ancient mode as us
    }

    /**
     * Deserialize an event window from the given input stream.
     *
     * @param in the input stream
     * @return the deserialized event window
     */
    @NonNull
    public static EventWindow deserializeEventWindow(@NonNull final SerializableDataInputStream in) throws IOException {

        final long latestConsensusRound = in.readLong();
        final long ancientThreshold = in.readLong();
        final long expiredThreshold = in.readLong();

        return new EventWindow(
                latestConsensusRound,
                // by default, we set the birth round to the pending round
                latestConsensusRound + 1,
                ancientThreshold,
                expiredThreshold);
    }
}
