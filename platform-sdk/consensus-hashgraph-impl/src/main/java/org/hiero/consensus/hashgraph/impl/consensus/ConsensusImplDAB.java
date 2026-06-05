// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;
import static org.hiero.consensus.model.PbjConverters.fromPbjTimestamp;
import static org.hiero.consensus.model.PbjConverters.toPbjTimestamp;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.FIRST_CONSENSUS_NUMBER;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.EventConsensusData;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.hedera.hapi.util.HapiUtils;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.LongStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.structures.SequentialRingBuffer;
import org.hiero.base.utility.Threshold;
import org.hiero.consensus.concurrent.throttle.RateLimitedLogger;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.EventInfo;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.EventInfo.UpdateResults;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.RoundInfo;
import org.hiero.consensus.hashgraph.impl.consensus.calculations.HashgraphInfo.RoundInfoPrev;
import org.hiero.consensus.hashgraph.impl.metrics.ConsensusMetrics;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.roster.RosterLookup;
import org.hiero.consensus.round.RoundCalculationUtils;

/**
 * All the code for calculating the consensus for events in a hashgraph. This calculates the consensus timestamp and
 * consensus order, according to the hashgraph consensus algorithm.
 *
 * <p>Every method in this file is private, except for some getters and the addEvent method.
 * Therefore, if care is taken so that only one thread at a time can be in the call to addEvent, then only one thread at
 * a time will be anywhere in this is class (except for the getters). None of the variables are volatile, so calls to
 * the getters by other threads may not see effects of addEvent immediately.
 *
 * <p>The consensus order is calculated incrementally: each time a new event is added to the
 * hashgraph, it immediately finds the consensus order for all the older events for which that is possible. It uses a
 * fundamental theorem that was not included in the tech report. That theorem is:
 *
 * <p>Theorem: If every known witness in round R in hashgraph A has its fame decided by A, and
 * S_{A,R} is the set of known famous witnesses in round R in hashgraph A, and if at least one event created in round
 * R+2 is known in A, then S_{A,R} is immutable and will never change as the hashgraph grows in the future. Furthermore,
 * any consistent hashgraph B will have an S_{B,R} that is a subset of S_{A,R}, and as B grows during gossip, it will
 * eventually be the case that S_{B,R} = S_{A,R} with probability one.
 *
 * <p>Proof: the R+2 event strongly sees more than 2/3 of members having R+1 witnesses that vote NO
 * on the fame of any unknown R event X that will be discovered in the future. Any future R+2 voter will strongly see a
 * (possibly) different set of more than 2/3 of the R+1 population, and the intersection of the two sets will all be NO
 * votes for the new voter. So the new voter will see less than 1/3 YES votes, and more than 1/3 NO votes, and will
 * therefore vote no. Therefore every R+3 voter will see unanimous NO votes, and will decide NO. Therefore X will not be
 * famous. So the set of famous in R will never grow in the future. (And so the consensus theorems imply that B will
 * eventually agree).
 *
 * <p>In other words, you never know whether new events and witnesses will be added to round R in
 * the future. But if all the known witnesses in that round have their fame decided (and if a round R+2 event is known),
 * then you know for sure that there will never be any more famous witnesses discovered for round R. So you can safely
 * calculate the received round and consensus time stamp for every event that will have a received round of R. This is
 * the key to the incremental algorithm: as soon as all known witnesses in R have their fame decided (and there is at
 * least one R+2 event), then we can decide the consensus for a new batch of events: all those with received round R.
 *
 * <p>There will be at least one famous event in each round. This is a theorem in the tech report,
 * but both the theorem and its proof should be adjusted to say the following:
 *
 * <p>Definition: a "voter" is a witness in round R that is strongly seen by at least one witness in
 * round R+1.
 *
 * <p>Theorem: For any R, there exists a witness X in round R that will be famous, and this will be
 * decided at the latest when one event in round R+3 is known.
 *
 * <p>Proof: Each voter in R+1 strongly sees more than 2n/3 witnesses in R, therefore each witness
 * in R is on average strongly seen by more than 2n/3 of the voters in R+1. There must be at least one that is not below
 * average, so let X be an R witness that is strongly seen by more than 2n/3 round R+1 voters. Those voters will vote
 * YES on the fame of X, because they see X. Any round R+2 witness will receive votes from more than 2n/3 round R+1
 * voters, therefore it will receive a majority of its votes for X being YES, therefore it will either vote or decide
 * YES. If any R+2 witness decides, then X is known to be famous at that time. If none do, then as soon as an R+3
 * witness exists, it will see unanimous YES votes, and it will decide YES. So X will be known to be famous after the
 * first witness of R+3 is known (or earlier).
 *
 * <p>In normal operation, with everyone online and everyone honest, we might expect that all of the
 * round R witnesses will be known to be famous after the first event of round R+2 is known. But even in the worst case,
 * where some computers are down (even honest ones), and many dishonest members are branching, the theorem still
 * guarantees at least one famous witness is known by R+3.
 *
 * <p>It is another theorem that the d12 and d2 algorithm have more than two thirds of the
 * population creating unique famous witnesses (judges) in each round. It is a theorem that d1 does, too, for the
 * algorithm described in 2016, and is conjectured to be true for the 2019 version, too.
 *
 * <p>Another new theorem used here:
 *
 * <p>Theorem: If a new witness X is added to round R, but at least one already exists in round R+2,
 * then X will not be famous (so there is no need to hold the elections).
 *
 * <p>Proof: If an event X currently exists in round R+2, then when the new event Y is added to
 * round R, it won't be an ancestor of X, nor of the witnesses that X strongly sees. Therefore, X will collect unanimous
 * votes of NO for the fame of Y, so X will decide that Y is not famous. Therefore, once a round R+2 event is added to
 * the hashgraph, the set of possible unique famous witnesses for round R is fixed, and the unique famous witnesses will
 * end up being a subset of it.
 *
 * <p>NOTE: for concision, all of the above talks about things like "2/3 of the members" or "2/3 of
 * the witnesses". In every case, it should be interpreted to actually mean "members whose stake adds up to more than
 * 2/3 of the total stake", and "witnesses created by members whose stake is more than 2/3 of the total".
 */
public class ConsensusImplDAB implements Consensus {

    private static final Logger logger = LogManager.getLogger(ConsensusImplDAB.class);

    /** Consensus configuration */
    private final ConsensusConfig config;
    /** wall clock time */
    private final Time time;
    /** used to look up information from the roster */
    private final RosterLookup rosterLookup;
    /** metrics related to consensus */
    private final ConsensusMetrics consensusMetrics;
    /** used for searching the hashgraph */
    private final AncestorSearch search = new AncestorSearch();
    /**
     * recently added events. this list is used for recalculating metadata once a new round is decided. as soon as
     * events reach consensus or become stale, they are discarded from this list.
     */
    private final List<EventImpl> recentEvents = new LinkedList<>();
    /**
     * Number of events that have reached consensus order. This is used for setting consensus order numbers in events,
     * so it must be part of the signed state.
     */
    private long numConsensus = FIRST_CONSENSUS_NUMBER;

    /**
     * The last consensus timestamp. This is equal to the consensus time of the last transaction in the last event that
     * reached consensus. This is null if no event has reached consensus yet. As each event reaches its consensus, its
     * timestamp is moved forward (if necessary) to be after this time by n
     * {@link ConsensusConstants#MIN_TRANS_TIMESTAMP_INCR_NANOS} nanoseconds, if the event had n transactions (or n=1 if
     * no transactions).
     */
    private Instant lastConsensusTime = null;
    /**
     * if consensus is not starting from genesis, this instance is used to accurately calculate the round for events
     */
    private InitJudges initJudges = null;

    /**
     * A flag that signals if we are currently replaying the PCES or not.
     */
    private boolean pcesMode = false;

    /**
     * Nanoseconds to add to the first transaction's timestamp in an event. This allows space for the execution layer to
     * insert items before the first transaction. Provided by the execution layer at startup.
     */
    private final long transactionOffsetNanos;

    private final HashgraphInfo hashgraphInfo = new HashgraphInfo();
    private RoundInfo roundInfo;
    private RoundInfoPrev roundInfoPrev;
    /** When fully dynamic address book is implemented, this will be a configurable value. It is the number of rounds
     * between a roster being known (as a result of handling a round's transactions or the execution layer requesting
     * it be adopted) and it being used to calculate consensus. */
    private static final int NUM_ROUNDS_ROSTER = 5;
    /** A map used to lookup events from the memos object. Once an event reaches consensus and is returned in a round,
     * the entry is no longer needed in this map. It mirrors recentEvents. */
    private final Map<EventInfo, EventImpl> memosEventMap = new IdentityHashMap<>();
    /** stores the minimum judge ancient identifier for all decided and non-expired rounds */
    private final SequentialRingBuffer<MinimumJudgeInfo> minimumJudgeStorage;

    /** The rate limited logger for rounds without a super majority of weight on judges */
    private final RateLimitedLogger noSuperMajorityLogger;
    /** The rate limited logger for rounds with no judge */
    private final RateLimitedLogger noJudgeLogger;

    /**
     * Constructs an empty object (no events) to keep track of elections and calculate consensus.
     *
     * @param configuration          the configuration
     * @param time                   the time source
     * @param consensusMetrics       metrics related to consensus
     * @param roster                 the global address book, which never changes
     * @param transactionOffsetNanos nanoseconds to add to the first transaction's timestamp in an event
     */
    public ConsensusImplDAB(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final ConsensusMetrics consensusMetrics,
            @NonNull final Roster roster,
            final long transactionOffsetNanos) {
        this.config = requireNonNull(configuration).getConfigData(ConsensusConfig.class);
        this.transactionOffsetNanos = transactionOffsetNanos;
        this.time = time;
        this.consensusMetrics = consensusMetrics;

        // until we implement roster changes, we will just use the use this roster
        this.rosterLookup = new RosterLookup(roster);

        this.minimumJudgeStorage =
                new SequentialRingBuffer<>(ConsensusConstants.ROUND_FIRST, config.roundsExpired() * 2);

        this.noSuperMajorityLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));
        this.noJudgeLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));
    }

    /**
     * Load consensus from a snapshot. This will continue consensus from the round of the snapshot once all the required
     * events are provided.
     */
    @Override
    public void loadSnapshot(@NonNull final ConsensusSnapshot snapshot) {
        reset();
        final Set<Hash> judgeHashes = snapshot.judgeIds().stream()
                .map(judge -> new Hash(judge.judgeHash()))
                .collect(toSet());

        initJudges = new InitJudges(snapshot.round(), judgeHashes);

        final List<MinimumJudgeInfo> minimumJudgeInfos = snapshot.minimumJudgeInfoList();
        minimumJudgeStorage.reset(minimumJudgeInfos.getFirst().round());
        for (final MinimumJudgeInfo minimumJudgeInfo : minimumJudgeInfos) {
            minimumJudgeStorage.add(minimumJudgeInfo.round(), minimumJudgeInfo);
        }

        final List<RosterEntry> rosterEntries = rosterLookup.getRoster().rosterEntries();
        final long[] nodeIds =
                rosterEntries.stream().mapToLong(RosterEntry::nodeId).toArray();
        final long[] weights =
                rosterEntries.stream().mapToLong(RosterEntry::weight).toArray();
        roundInfo = new RoundInfo(
                snapshot.round() + 1,
                nodeIds,
                weights,
                config.coinFreq(),
                false,
                false,
                config.roundsNonAncient(),
                NUM_ROUNDS_ROSTER);

        numConsensus = snapshot.nextConsensusNumber();
        lastConsensusTime = fromPbjTimestamp(snapshot.consensusTimestamp());
    }

    /** Reset this instance to a state of a newly created instance */
    private void reset() {
        recentEvents.clear();
        numConsensus = 0;
        lastConsensusTime = null;
        initJudges = null;
    }

    /**
     * Set whether events are currently being sourced from the PCES.
     *
     * @param pcesMode true if we are currently replaying the PCES, false otherwise
     */
    public void setPcesMode(final boolean pcesMode) {
        this.pcesMode = pcesMode;
    }

    @Override
    public List<EventImpl> getPreConsensusEvents() {
        // recentEvents will usually only contain pre-consensus events,
        // but if the most recent judge reaches consensus, it will be in this list too, so it needs to be filtered out
        return recentEvents.stream().filter(e -> !e.isConsensus()).toList();
    }

    /**
     * Add an event to consensus. It must already have been instantiated, checked for being a duplicate of an existing
     * event, had its signature created or checked. It must also be linked to its parents.
     *
     * <p>This method will add it to consensus and propagate all its effects. So if the consensus
     * order can now be calculated for an event (which wasn't possible before), then it will do so and return a list of
     * consensus rounds.
     *
     * <p>It is possible that adding this event will decide the fame of the last candidate witness
     * in a round, and so the round will become decided, and so a batch of events will reach consensus. The list of
     * events that reached consensus (if any) will be returned in a consensus round.
     *
     * @param event the event to be added
     * @return A list of consensus rounds or an empty list if no consensus was reached
     */
    @NonNull
    @Override
    public List<ConsensusRound> addEvent(@NonNull final EventImpl event) {
        try {
            final EventInfo[] parentEventInfos =
                    event.getAllParents().stream().map(EventImpl::getEventInfo).toArray(EventInfo[]::new);
            final EventInfo eventInfo = new EventInfo(
                    hashgraphInfo,
                    event.getCreatorId().id(),
                    event.getTimeCreated(),
                    event.getBirthRound(),
                    parentEventInfos);
            event.setEventInfo(eventInfo);
            recentEvents.add(event);
            memosEventMap.put(eventInfo, event);
            final boolean lastJudgeFound = checkInitJudges(event);

            if (waitingForInitJudges()) {
                // we should not do any calculations or voting until we have found all the init judges
                return List.of();
            }

            UpdateResults results;

            if (lastJudgeFound) {
                // when we find the last init judge, we have to create the round state objects
                final EventInfo[] judgeMemos = initJudges.getJudges().stream()
                        .map(EventImpl::getEventInfo)
                        .toList()
                        .toArray(EventInfo[]::new);
                final long minJudgeBirthRound = initJudges.getJudges().stream()
                        .mapToLong(EventImpl::getBirthRound)
                        .min()
                        .orElseThrow();
                roundInfoPrev = new RoundInfoPrev(
                        roundInfo.pendingRound(), false, judgeMemos, false,
                        config.roundsNonAncient(), numConsensus - 1, minJudgeBirthRound);

                results = updateRecentEvents();
            } else {
                // this is the most common case, we are not looking for init judges so we simply
                results = updateEvent(event);
            }

            final List<ConsensusRound> rounds = new ArrayList<>();
            while (results != null) {
                final ConsensusRound consensusRound = roundDecided(results);
                rounds.add(consensusRound);
                results = updateRecentEvents();
            }
            return rounds;
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "Exception occurred while trying to add event", e);
            throw e;
        }
    }

    @Nullable
    private UpdateResults updateEvent(@NonNull final EventImpl event) {
        consensusMetrics.addedEvent(event);
        return event.getEventInfo().update(roundInfo, roundInfoPrev);
    }

    private void updateMinimumJudgeInfo(final MinimumJudgeInfo minimumJudgeInfo) {
        minimumJudgeStorage.add(roundInfo.pendingRound(), minimumJudgeInfo);
        // Delete the oldest rounds with round number which is expired
        minimumJudgeStorage.removeOlderThan(roundInfo.pendingRound() - config.roundsExpired());
    }

    @Nullable
    private UpdateResults updateRecentEvents() {
        for (final Iterator<EventImpl> iterator = recentEvents.iterator(); iterator.hasNext(); ) {
            final EventImpl insertedEvent = iterator.next();
            // Call update for all events whose birth round is at least the minimum judge birth round of the
            // latest consensus round, including all judges in the latest consensus round even if they
            // are consensus events.
            if (insertedEvent.getBirthRound() >= roundInfoPrev.prevMinJudgeBirthRound()) {
                final UpdateResults results = insertedEvent.getEventInfo().update(roundInfo, roundInfoPrev);
                if (results != null) {
                    return results;
                }
            }

            if (insertedEvent.isConsensus() || ancient(insertedEvent)) {
                iterator.remove();
                memosEventMap.remove(insertedEvent.getEventInfo());
            }
        }
        return null;
    }

    private void updateRoundInfo(@NonNull final UpdateResults updateResults) {
        // When fully dynamic address book is enabled, we will have a map from round to rosterLookup or from round to
        // roster (and we will create the rosterLookup as needed).
        final List<RosterEntry> rosterEntries = rosterLookup.getRoster().rosterEntries();
        final long[] nodeIds =
                rosterEntries.stream().mapToLong(RosterEntry::nodeId).toArray();
        final long[] weights =
                rosterEntries.stream().mapToLong(RosterEntry::weight).toArray();

        roundInfo = new RoundInfo(
                roundInfo.pendingRound() + 1,
                nodeIds,
                weights,
                config.coinFreq(),
                false,
                false,
                config.roundsNonAncient(),
                NUM_ROUNDS_ROSTER);
        roundInfoPrev = updateResults.nextRoundInfoPrev();
    }
    /**
     * This round has been decided, this means that the fame of all known witnesses in that round
     * has been decided, and so any new witnesses discovered in the future will be guaranteed to not
     * be famous.
     *
     * <p>Since fame for this round is now decided, it is now possible to decide consensus and time
     * stamps for events in earlier rounds. If it's an ancestor of all the famous witnesses, then it
     * reaches consensus.
     *
     * @param results the round information of the decided round
     * @return the consensus round
     */
    private @NonNull ConsensusRound roundDecided(@NonNull final UpdateResults results) {
        final List<EventImpl> judges = Arrays.stream(results.nextRoundInfoPrev().prevJudges())
                .map(memosEventMap::get)
                .toList();
        final long decidedRoundNumber = roundInfo.pendingRound();

        // Check for no judges or super majority conditions.
        logJudgeErrors(judges, decidedRoundNumber);

        // all events that reach consensus during this method call, in consensus order
        final List<PlatformEvent> consensusEvents =
                findConsensusEvents(judges, decidedRoundNumber, ConsensusUtils.generateWhitening(judges)).stream()
                        .map(EventImpl::getBaseEvent)
                        .toList();

        // all rounds before this round are now decided, and appropriate events marked consensus
        consensusMetrics.consensusReachedOnRound(decidedRoundNumber);

        // lastConsensusTime is updated above with the last transaction in the last event that reached consensus
        // if no events reach consensus, then we need to calculate the lastConsensusTime differently
        if (consensusEvents.isEmpty()) {
            if (lastConsensusTime == null) {
                // if this is the first round ever, and there are no events (which is usually the case)
                // we take the median of all the judge created times
                final List<Instant> judgeTimes =
                        judges.stream().map(EventImpl::getTimeCreated).sorted().toList();
                lastConsensusTime = judgeTimes.get(judgeTimes.size() / 2);
            } else {
                // if we have reached consensus before, we simply increase the lastConsensusTime by the min amount
                lastConsensusTime = ConsensusUtils.calcMinTimestampForNextEvent(lastConsensusTime);
            }
        }

        final MinimumJudgeInfo info = minimumJudgeStorage.get(minimumJudgeStorage.minIndex());
        final RoundInfoPrev decidedRoundInfo = results.nextRoundInfoPrev();
        final long nonExpiredThreshold =
                info == null ? EventConstants.ANCIENT_THRESHOLD_UNDEFINED : info.minimumJudgeBirthRound();
        final long nonAncientThreshold = decidedRoundInfo.prevMinNonAncientRound();

        final List<JudgeId> judgeIds = judges.stream()
                .map(j -> JudgeId.newBuilder()
                        .judgeHash(j.getBaseHash().getBytes())
                        .creatorId(j.getCreatorId().id())
                        .build())
                .toList();
        final MinimumJudgeInfo minimumJudgeInfo = MinimumJudgeInfo.newBuilder()
                .round(decidedRoundNumber)
                .minimumJudgeBirthRound(decidedRoundInfo.prevMinJudgeBirthRound())
                .build();
        updateMinimumJudgeInfo(minimumJudgeInfo);

        final long oldestNonAncientRound = RoundCalculationUtils.getOldestNonAncientRound(
                roundInfo.targetNumRoundsNonAncient(), decidedRoundNumber);
        final List<MinimumJudgeInfo> minimumJudgeInfos = LongStream.range(oldestNonAncientRound, getFameDecidedBelow())
                .mapToObj(this::getMinimumJudgeIndicator)
                .filter(Objects::nonNull)
                .toList();

        updateRoundInfo(results);

        return new ConsensusRound(
                rosterLookup.getRoster(),
                consensusEvents,
                new EventWindow(
                        decidedRoundNumber,
                        // by default, we set the birth round for new events to the pending round
                        decidedRoundNumber + 1,
                        nonAncientThreshold,
                        nonExpiredThreshold),
                new ConsensusSnapshot(
                        decidedRoundNumber,
                        minimumJudgeInfos,
                        numConsensus,
                        toPbjTimestamp(lastConsensusTime),
                        judgeIds),
                pcesMode,
                time.now());
    }

    private MinimumJudgeInfo getMinimumJudgeIndicator(final long round) {
        final MinimumJudgeInfo minimumJudgeInfo = minimumJudgeStorage.get(round);
        if (minimumJudgeInfo == null) {
            logger.error(
                    LogMarker.EXCEPTION.getMarker(),
                    "Missing round {}. Fame decided below {}, oldest non-ancient round {}",
                    round,
                    getFameDecidedBelow(),
                    RoundCalculationUtils.getOldestNonAncientRound(config.roundsNonAncient(), getLastRoundDecided()));
            return null;
        }
        return minimumJudgeInfo;
    }

    /**
     * Find all events that are ancestors of the judges in round and update them. A non-consensus
     * event that is an ancestor of all of them should be marked as consensus, and have its
     * consensus roundReceived and timestamp set. This should not be called on any round greater
     * than R until after it has been called on round R.
     *
     * @param judges the judges for this round
     * @param decidedRound the info for the round with the unique famous witnesses, which is also
     *     the round received for these events reaching consensus now
     * @param whitening a XOR of all judge hashes in this round
     */
    private @NonNull List<EventImpl> findConsensusEvents(
            @NonNull final List<EventImpl> judges, final long decidedRound, @NonNull final byte[] whitening) {
        // the newly-consensus events where round received is "round"
        final List<EventImpl> consensus = search.commonAncestorsOf(judges, this::nonConsensusNonAncient);
        // event has reached consensus, so set consensus timestamp, and set isConsensus to true
        consensus.forEach(e -> setIsConsensusTrue(e, decidedRound));

        // "consensus" now has all events in history with receivedRound==round
        // there will never be any more events with receivedRound<=round (not even if the address
        // book changes)
        ConsensusSorter.sort(consensus, whitening);

        // Set the consensus number for every event that just became a consensus
        // event. Add more info about it to the hashgraph. Set event.lastInRoundReceived
        // to true for the last event in "consensus".
        setConsensusOrder(consensus);

        // reclaim the memory for the list of received times
        consensus.forEach(e -> e.setRecTimes(null));

        return consensus;
    }

    /**
     * Set event.isConsensus to true, set its consensusTimestamp, and record speed statistics.
     *
     * @param event the event to modify, with event.getRecTimes() containing all the times judges
     *     first saw it
     * @param receivedRound the round in which event was received
     */
    private static void setIsConsensusTrue(@NonNull final EventImpl event, final long receivedRound) {
        event.setRoundReceived(receivedRound);
        event.setConsensus(true);

        // list of when e1 first became ancestor of each ufw
        // these timestamps have been sorted beforehand
        final List<Instant> times = event.getRecTimes();

        // take middle. If there are 2 middle (even length) then use the 2nd (max) of them
        event.setPreliminaryConsensusTimestamp(times.get(times.size() / 2));
    }

    /**
     * Set event.consensusOrder for every event that just reached consensus, and update the count
     * numConsensus accordingly. The last event in events is marked as being the last received in
     * its round. Consensus timestamps are adjusted, if necessary, to ensure that each event in
     * consensus order is later than the previous one, by enough nanoseconds so that each
     * transaction can be given a later timestamp than the last.
     *
     * @param events the events to set (such that a for(EventImpl e:events) loop visits them in
     *     consensus order)
     */
    private void setConsensusOrder(@NonNull final Collection<EventImpl> events) {
        for (final EventImpl e : events) {
            // the minimum timestamp for this event
            final Instant minTimestamp =
                    lastConsensusTime == null ? null : ConsensusUtils.calcMinTimestampForNextEvent(lastConsensusTime);
            // advance this event's consensus timestamp to be at least minTimestamp
            if (minTimestamp != null && e.getPreliminaryConsensusTimestamp().isBefore(minTimestamp)) {
                e.setPreliminaryConsensusTimestamp(minTimestamp);
            }

            e.getBaseEvent()
                    .setConsensusData(new EventConsensusData(
                            HapiUtils.asTimestamp(e.getPreliminaryConsensusTimestamp()), numConsensus));

            lastConsensusTime = EventUtils.getLastTransTime(e.getBaseEvent(), transactionOffsetNanos);
            numConsensus++;
            consensusMetrics.consensusReached(e);
        }
    }

    /**
     * Log if there are no judges or if there is no super majority of weight on judges.
     *
     * @param judges             the judges for this round
     * @param decidedRoundNumber the round number of the decided round
     */
    private void logJudgeErrors(@NonNull final List<EventImpl> judges, final long decidedRoundNumber) {
        final long judgeWeights = judges.stream()
                .mapToLong(event -> rosterLookup.getWeight(event.getCreatorId()))
                .sum();
        consensusMetrics.judgeWeights(judgeWeights);
        if (judges.isEmpty()) {
            noJudgeLogger.error(LogMarker.ERROR.getMarker(), "no judges in round = {}", decidedRoundNumber);
        } else {
            if (!Threshold.SUPER_MAJORITY.isSatisfiedBy(judgeWeights, rosterLookup.rosterTotalWeight())) {
                noSuperMajorityLogger.error(
                        LogMarker.ERROR.getMarker(),
                        "less than a super majority of weight on judges.  round = {}, judgesWeight = {}, percentage = {}",
                        decidedRoundNumber,
                        judgeWeights,
                        (double) judgeWeights / rosterLookup.rosterTotalWeight());
            }
        }
    }

    @Override
    public boolean waitingForInitJudges() {
        return initJudges != null && initJudges.initJudgesMissing();
    }

    /**
     * Checks if an event is an init judge. If it is, it will set its round created and judge flags. if it's the last
     * missing judge, it will also mark events which have previously reached consensus and return true.
     *
     * @param event the event to check
     * @return true if the event is the last init judge we are looking for
     */
    private boolean checkInitJudges(@NonNull final EventImpl event) {
        if (!waitingForInitJudges()) {
            return false;
        }
        if (!initJudges.isInitJudge(event.getBaseHash())) {
            return false;
        }
        // we found one of the missing init judges
        initJudges.judgeFound(event);
        logger.info(
                STARTUP.getMarker(),
                "Found init judge %s, num remaining: {}".formatted(event.shortString()),
                initJudges::numMissingJudges);
        if (initJudges.initJudgesMissing()) {
            return false;
        }

        initJudges = null;

        return true;
    }

    private boolean nonConsensusNonAncient(@NonNull final EventImpl e) {
        return !e.isConsensus() && !ancient(e);
    }

    @Override
    public long getFameDecidedBelow() {
        return roundInfo.pendingRound();
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Functions from SWIRLDS-TR-2020-01, verified by Coq proof
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Check if the event is ancient
     *
     * @param x the event to check
     * @return true if the event is ancient
     */
    private boolean ancient(@Nullable final EventImpl x) {
        return x == null || x.getBirthRound() < roundInfoPrev.prevMinNonAncientRound();
    }
}
