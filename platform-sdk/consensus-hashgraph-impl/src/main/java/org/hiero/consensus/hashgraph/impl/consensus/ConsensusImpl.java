// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import static com.swirlds.logging.legacy.LogMarker.CONSENSUS_VOTING;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;
import static org.hiero.consensus.model.PbjConverters.fromPbjTimestamp;
import static org.hiero.consensus.model.PbjConverters.toPbjTimestamp;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.FIRST_CONSENSUS_NUMBER;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.EventConsensusData;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.JudgeId;
import com.hedera.hapi.util.HapiUtils;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.Threshold;
import org.hiero.consensus.concurrent.utility.throttle.RateLimitedLogger;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.hashgraph.impl.metrics.ConsensusMetrics;
import org.hiero.consensus.model.event.NonDeterministicGeneration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.roster.RosterLookup;

/**
 * All the code for calculating the consensus for events in a hashgraph. This calculates the
 * consensus timestamp and consensus order, according to the hashgraph consensus algorithm.
 *
 * <p>Every method in this file is private, except for some getters and the addEvent method.
 * Therefore, if care is taken so that only one thread at a time can be in the call to addEvent,
 * then only one thread at a time will be anywhere in this is class (except for the getters). None
 * of the variables are volatile, so calls to the getters by other threads may not see effects of
 * addEvent immediately.
 *
 * <p>The consensus order is calculated incrementally: each time a new event is added to the
 * hashgraph, it immediately finds the consensus order for all the older events for which that is
 * possible. It uses a fundamental theorem that was not included in the tech report. That theorem
 * is:
 *
 * <p>Theorem: If every known witness in round R in hashgraph A has its fame decided by A, and
 * S_{A,R} is the set of known famous witnesses in round R in hashgraph A, and if at least one event
 * created in round R+2 is known in A, then S_{A,R} is immutable and will never change as the
 * hashgraph grows in the future. Furthermore, any consistent hashgraph B will have an S_{B,R} that
 * is a subset of S_{A,R}, and as B grows during gossip, it will eventually be the case that S_{B,R}
 * = S_{A,R} with probability one.
 *
 * <p>Proof: the R+2 event strongly sees more than 2/3 of members having R+1 witnesses that vote NO
 * on the fame of any unknown R event X that will be discovered in the future. Any future R+2 voter
 * will strongly see a (possibly) different set of more than 2/3 of the R+1 population, and the
 * intersection of the two sets will all be NO votes for the new voter. So the new voter will see
 * less than 1/3 YES votes, and more than 1/3 NO votes, and will therefore vote no. Therefore every
 * R+3 voter will see unanimous NO votes, and will decide NO. Therefore X will not be famous. So the
 * set of famous in R will never grow in the future. (And so the consensus theorems imply that B
 * will eventually agree).
 *
 * <p>In other words, you never know whether new events and witnesses will be added to round R in
 * the future. But if all the known witnesses in that round have their fame decided (and if a round
 * R+2 event is known), then you know for sure that there will never be any more famous witnesses
 * discovered for round R. So you can safely calculate the received round and consensus time stamp
 * for every event that will have a received round of R. This is the key to the incremental
 * algorithm: as soon as all known witnesses in R have their fame decided (and there is at least one
 * R+2 event), then we can decide the consensus for a new batch of events: all those with received
 * round R.
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
 * in R is on average strongly seen by more than 2n/3 of the voters in R+1. There must be at least
 * one that is not below average, so let X be an R witness that is strongly seen by more than 2n/3
 * round R+1 voters. Those voters will vote YES on the fame of X, because they see X. Any round R+2
 * witness will receive votes from more than 2n/3 round R+1 voters, therefore it will receive a
 * majority of its votes for X being YES, therefore it will either vote or decide YES. If any R+2
 * witness decides, then X is known to be famous at that time. If none do, then as soon as an R+3
 * witness exists, it will see unanimous YES votes, and it will decide YES. So X will be known to be
 * famous after the first witness of R+3 is known (or earlier).
 *
 * <p>In normal operation, with everyone online and everyone honest, we might expect that all of the
 * round R witnesses will be known to be famous after the first event of round R+2 is known. But
 * even in the worst case, where some computers are down (even honest ones), and many dishonest
 * members are branching, the theorem still guarantees at least one famous witness is known by R+3.
 *
 * <p>It is another theorem that the d12 and d2 algorithm have more than two thirds of the
 * population creating unique famous witnesses (judges) in each round. It is a theorem that d1 does,
 * too, for the algorithm described in 2016, and is conjectured to be true for the 2019 version,
 * too.
 *
 * <p>Another new theorem used here:
 *
 * <p>Theorem: If a new witness X is added to round R, but at least one already exists in round R+2,
 * then X will not be famous (so there is no need to hold the elections).
 *
 * <p>Proof: If an event X currently exists in round R+2, then when the new event Y is added to
 * round R, it won't be an ancestor of X, nor of the witnesses that X strongly sees. Therefore, X
 * will collect unanimous votes of NO for the fame of Y, so X will decide that Y is not famous.
 * Therefore, once a round R+2 event is added to the hashgraph, the set of possible unique famous
 * witnesses for round R is fixed, and the unique famous witnesses will end up being a subset of it.
 *
 * <p>NOTE: for concision, all of the above talks about things like "2/3 of the members" or "2/3 of
 * the witnesses". In every case, it should be interpreted to actually mean "members whose stake
 * adds up to more than 2/3 of the total stake", and "witnesses created by members whose stake is
 * more than 2/3 of the total".
 */
public class ConsensusImpl implements Consensus {

    private static final Logger logger = LogManager.getLogger(ConsensusImpl.class);

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
     * recently added events. this list is used for recalculating metadata once a new round is
     * decided. as soon as events reach consensus or become stale, they are discarded from this
     * list.
     */
    private final List<EventImpl> recentEvents = new LinkedList<>();
    /** stores all round information */
    private final ConsensusRounds rounds;
    /**
     * Number of events that have reached consensus order. This is used for setting consensus order
     * numbers in events, so it must be part of the signed state.
     */
    private long numConsensus = FIRST_CONSENSUS_NUMBER;

    /**
     * The last consensus timestamp. This is equal to the consensus time of the last transaction in
     * the last event that reached consensus. This is null if no event has reached consensus yet.
     * As each event reaches its consensus, its timestamp is moved forward (if necessary) to be
     * after this time by n {@link ConsensusConstants#MIN_TRANS_TIMESTAMP_INCR_NANOS} nanoseconds,
     * if the event had n transactions (or n=1 if no transactions).
     */
    private Instant lastConsensusTime = null;
    /**
     * if consensus is not starting from genesis, this instance is used to accurately calculate the
     * round for events
     */
    private InitJudges initJudges = null;
    /** The rate limited logger for rounds without a super majority of weight on judges */
    private final RateLimitedLogger noSuperMajorityLogger;
    /** The rate limited logger for rounds with no judge */
    private final RateLimitedLogger noJudgeLogger;
    /** The rate limited logger for coin rounds */
    private final RateLimitedLogger coinRoundLogger;

    /**
     * A flag that signals if we are currently replaying the PCES or not.
     */
    private boolean pcesMode = false;

    /**
     * Constructs an empty object (no events) to keep track of elections and calculate consensus.
     *
     * @param configuration the configuration
     * @param time the time source
     * @param consensusMetrics metrics related to consensus
     * @param roster the global address book, which never changes
     */
    public ConsensusImpl(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final ConsensusMetrics consensusMetrics,
            @NonNull final Roster roster) {
        this.config = requireNonNull(configuration).getConfigData(ConsensusConfig.class);
        this.time = time;
        this.consensusMetrics = consensusMetrics;

        // until we implement roster changes, we will just use the use this roster
        this.rosterLookup = new RosterLookup(roster);

        this.rounds = new ConsensusRounds(config, roster);

        this.noSuperMajorityLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));
        this.noJudgeLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));
        this.coinRoundLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));
    }

    /**
     * Load consensus from a snapshot. This will continue consensus from the round of the snapshot
     * once all the required events are provided.
     */
    @Override
    public void loadSnapshot(@NonNull final ConsensusSnapshot snapshot) {
        reset();
        final Set<Hash> judgeHashes = snapshot.judgeIds().stream()
                .map(judge -> new Hash(judge.judgeHash()))
                .collect(toSet());

        initJudges = new InitJudges(snapshot.round(), judgeHashes);
        rounds.loadFromMinimumJudge(snapshot.minimumJudgeInfoList());
        numConsensus = snapshot.nextConsensusNumber();
        lastConsensusTime = fromPbjTimestamp(snapshot.consensusTimestamp());
    }

    /** Reset this instance to a state of a newly created instance */
    private void reset() {
        recentEvents.clear();
        rounds.reset();
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
     * Add an event to consensus. It must already have been instantiated, checked for being a
     * duplicate of an existing event, had its signature created or checked. It must also be linked
     * to its parents.
     *
     * <p>This method will add it to consensus and propagate all its effects. So if the consensus
     * order can now be calculated for an event (which wasn't possible before), then it will do so
     * and return a list of consensus rounds.
     *
     * <p>It is possible that adding this event will decide the fame of the last candidate witness
     * in a round, and so the round will become decided, and so a batch of events will reach
     * consensus. The list of events that reached consensus (if any) will be returned in a consensus
     * round.
     *
     * @param event the event to be added
     * @return A list of consensus rounds or an empty list if no consensus was reached
     */
    @NonNull
    @Override
    public List<ConsensusRound> addEvent(@NonNull final EventImpl event) {
        try {
            recentEvents.add(event);
            // set its round to undefined so that it gets calculated
            event.setRoundCreated(ConsensusConstants.ROUND_UNDEFINED);
            ConsensusRound consensusRound;

            final boolean lastJudgeFound = checkInitJudges(event);

            if (waitingForInitJudges()) {
                // we should not do any calculations or voting until we have found all the init judges
                return List.of();
            }

            if (lastJudgeFound) {
                // when we find the last init judge, we have to recalculate the metadata for all events
                consensusRound = recalculateAndVote();
            } else {
                // this is the most common case, we are not looking for init judges so we simply calculate the
                // metadata for the event and vote if it's a witness
                consensusRound = calculateAndVote(event);
            }

            final List<ConsensusRound> rounds = new ArrayList<>();
            while (consensusRound != null) {
                rounds.add(consensusRound);

                consensusRound = recalculateAndVote();
            }
            return rounds;
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "Exception occurred while trying to add event", e);
            throw e;
        }
    }

    /**
     * Round fame is calculated for one round at a time. If fame has been decided for a round, we
     * recalculate the metadata for all non-ancient non-consensus events. This may trigger another
     * round having its fame decided.
     *
     * @return a consensus round if fame has been decided, null otherwise
     */
    @Nullable
    private ConsensusRound recalculateAndVote() {
        rounds.recalculating();
        for (final Iterator<EventImpl> iterator = recentEvents.iterator(); iterator.hasNext(); ) {
            final EventImpl insertedEvent = iterator.next();

            if (rounds.isLastDecidedJudge(insertedEvent)
                    && insertedEvent.getAllParents().stream()
                                    .mapToLong(this::round)
                                    .max()
                                    .orElse(ConsensusConstants.ROUND_NEGATIVE_INFINITY)
                            == ConsensusConstants.ROUND_NEGATIVE_INFINITY) {
                // If an event was a judge in the last round decided AND is not a descendant of any other judge in
                // this round, we leave all of its metadata intact. We know that it is not a descendant of any other
                // judge in this round if all of its parents have a round of -infinity.
                //
                // Its round must stay intact so that descendants can determine their round numbers.
                // We don't call calculateAndVote() for this event because:
                // - its metadata will be unchanged
                // - it will not vote
                // - it will never decide a round

                // The only exception to this the DeGen value. This needs to be recalculated on every round, and all
                // descendants of decided judges will base their DeGen on them.
                DeGen.calculateDeGen(insertedEvent);
                continue;
            }

            if (insertedEvent.isConsensus() || ancient(insertedEvent)) {
                insertedEvent.clearMetadata();

                // all events that are consensus or ancient have a round of -infinity
                insertedEvent.setRoundCreated(ConsensusConstants.ROUND_NEGATIVE_INFINITY);
                iterator.remove();
                continue;
            }

            // for all other events, we need to recalculate its round and metadata
            insertedEvent.clearMetadata();
            insertedEvent.setRoundCreated(ConsensusConstants.ROUND_UNDEFINED);

            final ConsensusRound consensusRound = calculateAndVote(insertedEvent);
            if (consensusRound != null) {
                return consensusRound;
            }
        }
        return null;
    }

    @Nullable
    private ConsensusRound calculateAndVote(final EventImpl event) {
        // before we calculate the round of an event, we need to calculate its DeGen value, since it might be needed
        // to determine an event's round
        DeGen.calculateDeGen(event);
        // find the roundCreated, and store it using event.setRoundCreated()
        round(event);
        consensusMetrics.addedEvent(event);

        // force it to memoize for this event now, to avoid deep recursion of these methods later
        calculateMetadata(event);

        if (!witness(event)) {
            return null;
        }

        event.setWitness(true);

        if (rounds.getElectionRoundNumber() <= event.getRoundCreated()) {
            // This witness is either part of the election round or is a witness for a later round.
            // in either case, we let the rounds object know about it.
            rounds.newWitness(event);
            if (rounds.getElectionRoundNumber() < event.getRoundCreated()) {
                // this is a witness for a round later than the round being voted on, so it should
                // vote in all elections in the current round
                voteInAllElections(event);
            }
        } else {
            // this is a witness for a decided round, so it is not a famous witness
            event.setFamous(false);
            event.setFameDecided(true);
        }

        // in most cases, we only need to do this check after voting, since that is the only time a
        // round could be decided. but there is an edge case for which we need to check it for
        // non-witnesses
        // EDGE CASE:
        // if a round gets decided before we have found all the init judges, we cannot proceed with
        // finding consensus events. this is because we need to find all the init judges so that we
        // can find their common ancestors and mark them as having reached consensus. once we have
        // done this, we can find the consensus events for the next round, which in this case would
        // be the election round. if we didn't do that, then an event could reach consensus twice.
        final RoundElections roundElections = rounds.getElectionRound();
        if (roundElections.isDecided() && !waitingForInitJudges()) {
            // all famous witnesses for this round are now known. None will ever be added again. We
            // know this round has at least one witness. We know they all have fame decided. We
            // know the next 2 rounds have events in them, because otherwise we couldn't have
            // decided the fame here. Therefore, any new witness added to this round in the future
            // will be instantly decided as not famous. Therefore, the set of famous witnesses
            // in this round is now completely known and immutable. So we can call the following, to
            // record that fact, and propagate appropriately.
            return roundDecided(roundElections);
        }
        return null;
    }

    @Override
    public boolean waitingForInitJudges() {
        return initJudges != null && initJudges.initJudgesMissing();
    }

    /**
     * Checks if an event is an init judge. If it is, it will set its round created and judge flags.
     * if it's the last missing judge, it will also mark events which have previously reached
     * consensus and return true.
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

        // we now have the last of the missing judges, so find every known event that is an
        // ancestor of all of them, and mark it as having consensus.  We won't handle its
        // transactions or do anything else with it, since it had earlier achieved consensus
        // and affected the signed state that we started from. We won't even set its consensus
        // fields such as roundReceived, because they aren't known, will never be known, and
        // aren't needed.  We'll just mark it as having consensus, so we don't calculate
        // consensus for it again in the future.
        final List<EventImpl> ancestors =
                search.commonAncestorsOf(initJudges.getJudges(), this::nonConsensusNonAncient);
        ancestors.forEach(e -> {
            e.setConsensus(true);
            e.setRecTimes(null);
        });
        // This value is normally updated when a round gets decided, but since we are starting from
        // a snapshot, we need to set it here.
        rounds.setConsensusRelevantNGen(initJudges.getJudges().stream()
                .map(EventImpl::getNGen)
                .min(Long::compareTo)
                .orElse(NonDeterministicGeneration.FIRST_GENERATION));
        initJudges = null;

        return true;
    }

    /**
     * Calculate metadata for an event and memoize it. This is done to avoid deep recursion of these
     * methods later.
     *
     * @param event the event to calculate metadata for
     */
    private void calculateMetadata(@NonNull final EventImpl event) {

        if (notRelevantForConsensus(event) || rounds.isLastDecidedJudge(event)) {
            return;
        }
        lastSee(event, 0);
        timedStronglySeeP(event, 0);
        firstSelfWitnessS(event);
        firstWitnessS(event);
    }

    /**
     * Vote on all candidate witnesses in the current election round. This call could decide a
     * round.
     *
     * @param votingWitness the event that will vote
     */
    private void voteInAllElections(@NonNull final EventImpl votingWitness) {
        final RoundElections roundElections = rounds.getElectionRound();
        votingWitness.initVoting(roundElections.numElections());
        final long diff = round(votingWitness) - roundElections.getRound();
        if (diff <= 0) {
            // this should never happen, but just in case
            return;
        }
        if (diff == 1) {
            for (final Iterator<CandidateWitness> it = roundElections.undecidedWitnesses(); it.hasNext(); ) {
                final CandidateWitness candidateWitness = it.next();
                final boolean firstVote = firstVote(votingWitness, candidateWitness.getWitness());
                votingWitness.setVote(candidateWitness, firstVote);
                logVote(votingWitness, candidateWitness, "first", diff);
            }
            return;
        }

        // if diff > 1, we are counting the votes of the witnesses in the previous round. Vote with
        // the majority of witnesses strongly seen.
        final List<EventImpl> stronglySeen = getStronglySeenInPreviousRound(votingWitness);

        for (final Iterator<CandidateWitness> it = roundElections.undecidedWitnesses(); it.hasNext(); ) {
            final CandidateWitness candidateWitness = it.next();

            final CountingVote countingVote = getCountingVote(candidateWitness, stronglySeen);

            if (isCoinRound(diff)) {
                // a coin round. Don't decide.
                coinVote(votingWitness, candidateWitness, countingVote);
                logVote(
                        votingWitness,
                        candidateWitness,
                        "coin-" + (countingVote.isSupermajority() ? "counting" : "sig"),
                        diff);
                coinRoundLogger.warn(
                        LogMarker.ERROR.getMarker(),
                        "Coin round {}, voting witness: {}",
                        roundElections.getRound(),
                        votingWitness);
                continue;
            }

            // a normal round. Vote with the majority of those you strongly see
            votingWitness.setVote(candidateWitness, countingVote.getVote());
            logVote(votingWitness, candidateWitness, "counting", diff);
            // If you strongly see a supermajority one way, then decide that way.
            if (countingVote.isSupermajority()) {
                // we've decided one famous event. Set it as famous.
                candidateWitness.fameDecided(votingWitness.getVote(candidateWitness));
                if (roundElections.isDecided()) {
                    // this round has been decided
                    consensusMetrics.lastFamousInRound(candidateWitness.getWitness());
                    // no need to vote anymore until we create elections for the next round
                    return;
                }
            }
        }
    }

    /**
     * Calculates a counting vote for the candidate witness by the voting witness.
     *
     * <p>Suppose that the election is being held for round ER, and the witness voting is in round
     * VR. The voting witness looks at all witness it can strongly see from round VR-1. For each of
     * these witnesses, it looks at their vote for the election witness. It adds up all the stake of
     * the witnesses voting yes, and the stake of those voting no. If either of the stake sums is a
     * supermajority, the fame of the election witness is decided.
     *
     * <p>So the outcome of this voting is two booleans:
     *
     * <ol>
     *   <li>is the witness voting yes or no
     *   <li>is the vote a supermajority vote
     * </ol>
     *
     * @param candidateWitness the witness being voted on
     * @param stronglySeen the witnesses VR-1 that the voting witness can strongly see
     * @return the outcome of the vote
     */
    @NonNull
    private CountingVote getCountingVote(final CandidateWitness candidateWitness, final List<EventImpl> stronglySeen) {
        // count votes from witnesses you strongly see
        long yesWeight = 0; // total weight of all members voting yes
        long noWeight = 0; // total weight of all members voting yes
        for (final EventImpl w : stronglySeen) {
            final long weight = rosterLookup.getWeight(w.getCreatorId());
            if (w.getVote(candidateWitness)) {
                yesWeight += weight;
            } else {
                noWeight += weight;
            }
        }
        final boolean superMajority =
                Threshold.SUPER_MAJORITY.isSatisfiedBy(yesWeight, rosterLookup.rosterTotalWeight())
                        || Threshold.SUPER_MAJORITY.isSatisfiedBy(noWeight, rosterLookup.rosterTotalWeight());
        final boolean countingVote = yesWeight >= noWeight;

        return CountingVote.get(countingVote, superMajority);
    }

    /**
     * Should this be a coin voting round
     *
     * @param diff the difference in rounds between the voting witness and the candidate witness
     * @return true if it should be a coin round
     */
    private boolean isCoinRound(final long diff) {
        return diff % config.coinFreq() == 0;
    }

    /**
     * A coin voting round, very similar to a counting vote, the difference is:
     *
     * <ol>
     *   <li>A coin vote never decides fame, unlike a counting vote
     *   <li>If there is no yes or not supermajority, the vote will be random (based on the
     *       signature of the event)
     * </ol>
     *
     * @param votingWitness the witness that is voting
     * @param candidateWitness the witness being voted on
     * @param countingVote the counting vote
     */
    private void coinVote(
            @NonNull final EventImpl votingWitness,
            @NonNull final CandidateWitness candidateWitness,
            @NonNull final CountingVote countingVote) {
        // a coin round. Vote randomly unless you strongly see a supermajority. Don't decide.
        consensusMetrics.coinRound();
        final boolean vote = countingVote.isSupermajority()
                ? countingVote.getVote()
                : ConsensusUtils.coin(votingWitness.getBaseEvent());

        votingWitness.setVote(candidateWitness, vote);
    }

    /** Logs the outcome of voting */
    private void logVote(
            @NonNull final EventImpl votingWitness,
            @NonNull final CandidateWitness candidateWitness,
            @NonNull final String votingType,
            final long diff) {
        if (logger.isDebugEnabled(CONSENSUS_VOTING.getMarker())) {
            logger.debug(
                    CONSENSUS_VOTING.getMarker(),
                    "Witness {} voted on {}. vote:{} type:{} diff:{}",
                    votingWitness.shortString(),
                    candidateWitness.getWitness().shortString(),
                    votingWitness.getVote(candidateWitness),
                    votingType,
                    diff);
        }
    }

    private boolean firstVote(@NonNull final EventImpl voting, @NonNull final EventImpl votedOn) {
        // getRosterIndex() can throw an exception if the creator is not in the roster
        // this should never happen since we don't create elections for events not in the roster,
        // we instantly declare them not famous
        final int votedOnIndex = rosterLookup.getRosterIndex(votedOn.getCreatorId());
        // first round of an election. Vote TRUE for self-ancestors of those you firstSee. Don't
        // decide.
        EventImpl w = firstSee(voting, votedOnIndex);
        while (w != null && w.getRoundCreated() > voting.getRoundCreated() - 1 && selfParent(w) != null) {
            w = firstSelfWitnessS(selfParent(w));
        }
        return votedOn == w;
    }

    /**
     * Find all the witnesses that event can strongly see, in the round before the supplied event's
     * round created.
     *
     * @param event the event to find who it sees
     * @return a list of witnesses
     */
    @NonNull
    private List<EventImpl> getStronglySeenInPreviousRound(final EventImpl event) {
        final ArrayList<EventImpl> stronglySeen = new ArrayList<>(rosterLookup.numMembers());
        for (long m = 0; m < rosterLookup.numMembers(); m++) {
            final EventImpl s = stronglySeeS1(event, m);
            if (s != null) {
                stronglySeen.add(s);
            }
        }
        return stronglySeen;
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
     * @param roundElections the round information of the decided round
     * @return the consensus round
     */
    private @NonNull ConsensusRound roundDecided(final RoundElections roundElections) {
        // the current round just had its fame decided.
        // Note: more witnesses may be added to this round in the future, but they'll all be
        // instantly marked as not famous.
        final List<EventImpl> judges = roundElections.findAllJudges();
        final long decidedRoundNumber = rounds.getElectionRoundNumber();

        // Check for no judges or super majority conditions.
        checkJudges(judges, decidedRoundNumber);

        // update the round and ancient threshold values since fame has been decided for a new round
        rounds.currentElectionDecided();

        // all events that reach consensus during this method call, in consensus order
        final List<PlatformEvent> consensusEvents =
                findConsensusEvents(judges, decidedRoundNumber, ConsensusUtils.generateWhitening(judges)).stream()
                        .map(EventImpl::getBaseEvent)
                        .toList();
        // all rounds before this round are now decided, and appropriate events marked consensus
        consensusMetrics.consensusReachedOnRound();

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

        final long nonAncientThreshold = rounds.getAncientThreshold();
        final long nonExpiredThreshold = rounds.getExpiredThreshold();

        final List<JudgeId> judgeIds = judges.stream()
                .map(event -> new JudgeId(
                        event.getCreatorId().id(), event.getBaseHash().getBytes()))
                .toList();
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
                        rounds.getMinimumJudgeInfoList(),
                        numConsensus,
                        toPbjTimestamp(lastConsensusTime),
                        judgeIds),
                pcesMode,
                time.now());
    }

    /**
     * Check if there are no judges or if there is no super majority of weight on judges.
     *
     * @param judges             the judges for this round
     * @param decidedRoundNumber the round number of the decided round
     */
    private void checkJudges(@NonNull final List<EventImpl> judges, final long decidedRoundNumber) {
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

            lastConsensusTime = EventUtils.getLastTransTime(e.getBaseEvent());
            numConsensus++;
            consensusMetrics.consensusReached(e);
        }
    }

    private boolean nonConsensusNonAncient(@NonNull final EventImpl e) {
        return !e.isConsensus() && !ancient(e);
    }

    private @Nullable EventImpl timedStronglySeeP(@Nullable final EventImpl x, final long m) {
        long t = System.nanoTime(); // Used to update statistic for dot product time
        final EventImpl result = stronglySeeP(x, m);
        t = System.nanoTime() - t; // nanoseconds spent doing the dot product
        consensusMetrics.dotProductTime(t);
        return result;
    }

    /**
     * Check if this event is relevant for consensus calculation. If an event has a round of -infinity we don't care
     * about what it sees. This is a performance optimization, to stop traversing the part of the graph that has no
     * impact on consensus.
     * @param e the event to check
     * @return true if this event is relevant for consensus
     */
    private static boolean notRelevantForConsensus(@NonNull final EventImpl e) {
        return e.getRoundCreated() == ConsensusConstants.ROUND_NEGATIVE_INFINITY;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Functions from SWIRLDS-TR-2020-01, verified by Coq proof
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Checks if this event is a witness. The {@link EventImpl#isWitness()} is set based on this
     * method, so this is considered to be the definition of what a witness is.
     *
     * <p>(selfParent(x) = ∅) ∨ (round(x) > round(selfParent(x))
     *
     * @param x the event to check
     * @return true if this event is a witness
     */
    private boolean witness(@NonNull final EventImpl x) {
        return round(x) > ConsensusConstants.ROUND_NEGATIVE_INFINITY
                && (!x.hasSelfParent() || round(x) != round(selfParent(x)));
    }

    /**
     * @return the self-parent of event x, or ∅ if none or ancient
     */
    private @Nullable EventImpl selfParent(@NonNull final EventImpl x) {
        return ancient(x.getSelfParent()) ? null : x.getSelfParent();
    }

    /**
     * Check if the event is ancient
     *
     * @param x the event to check
     * @return true if the event is ancient
     */
    private boolean ancient(@Nullable final EventImpl x) {
        return x == null || x.getBirthRound() < rounds.getAncientThreshold();
    }

    /**
     * The parent round (max of parents' rounds) of event x (function from SWIRLDS-TR-2020-01). This
     * result is not memoized.
     *
     * @param x the event being queried
     * @return the parent round of x
     */
    private long parentRound(@Nullable final EventImpl x) {
        if (x == null) {
            return ConsensusConstants.ROUND_NEGATIVE_INFINITY;
        }

        long maxRound = ConsensusConstants.ROUND_NEGATIVE_INFINITY;
        for (final EventImpl parent : x.getAllParents()) {
            if (ancient(parent)) {
                continue;
            }
            maxRound = Math.max(maxRound, round(parent));
        }
        return maxRound;
    }

    /**
     * The last event created by m that is an ancestor of x (function from SWIRLDS-TR-2020-01). This
     * has aggressive memoization: the first time it is called with a given x, it immediately
     * calculates and stores results for all m. This result is memoized.
     *
     * @param x the event being queried
     * @param m the member ID of the creator
     * @return the last event created by m that is an ancestor of x, or null if none
     */
    private @Nullable EventImpl lastSee(@Nullable final EventImpl x, final long m) {
        if (x == null) {
            return null;
        }
        if (notRelevantForConsensus(x)) {
            return null;
        }
        if (x.sizeLastSee() != 0) { // return memoized answer, if available
            return x.getLastSee((int) m);
        }
        // memoize answers for all choices of m, then return answer for just this m
        x.initLastSee(rosterLookup.numMembers());

        for (int mm = 0; mm < rosterLookup.numMembers(); mm++) {
            if (rosterLookup.isIdAtIndex(x.getCreatorId(), mm)) {
                // mm created x, so x is considered to see itself
                x.setLastSee(mm, x);
                continue;
            }
            if (x.getAllParents().isEmpty()) {
                // no parents, so cannot see anything
                x.setLastSee(mm, null);
                continue;
            }
            // the latest event the parent can lastSee()
            EventImpl latestEventSeen = null;
            EventImpl parentWhichSeesLatestEvent = null;
            for (final EventImpl parent : x.getAllParents()) {
                if (ancient(parent)) {
                    // we can ignore ancient parents
                    continue;
                }
                final EventImpl candidate = lastSee(parent, mm);
                if (candidate == null) {
                    continue;
                }
                if (latestEventSeen == null) {
                    latestEventSeen = candidate;
                    parentWhichSeesLatestEvent = parent;
                    continue;
                }

                if ((round(candidate) > round(latestEventSeen))
                        || (candidate.getDeGen() > latestEventSeen.getDeGen()
                                && (firstSee(parent, mm) == firstSee(parentWhichSeesLatestEvent, mm)))) {
                    latestEventSeen = candidate;
                    parentWhichSeesLatestEvent = parent;
                }
            }
            x.setLastSee(mm, latestEventSeen);
        }
        return x.getLastSee((int) m);
    }

    /**
     * The witness y created by m that is seen by event x through an event z created by m2 (function
     * from SWIRLDS-TR-2020-01). This result is not memoized.
     *
     * @param x the event being queried
     * @param m the creator of y, the event seen
     * @param m2 the creator of z, the intermediate event through which x sees y
     * @return the event y that is created by m and seen by x through an event by m2
     */
    private @Nullable EventImpl seeThru(@Nullable final EventImpl x, final int m, final int m2) {
        if (x == null) {
            return null;
        }
        if (notRelevantForConsensus(x)) {
            return null;
        }
        if (m == m2 && rosterLookup.isIdAtIndex(x.getCreatorId(), m2)) {
            return firstSelfWitnessS(selfParent(x));
        }
        return firstSee(lastSee(x, m2), m);
    }

    /**
     * The witness created by m in the parent round of x that x strongly sees (function from
     * SWIRLDS-TR-2020-01). This result is memoized.
     *
     * <p>This method is called multiple times by both round() and stronglySeeP1(). A measure of the
     * total time spent in this method gives an indication of how much time is being devoted to what
     * can be thought of as a kind of generalized dot product (not a literal dot product). So it is
     * timed and it updates the statistic for that.
     *
     * @param x the event being queried
     * @param m the member ID of the creator
     * @return witness created by m in the parent round of x that x strongly sees, or null if none
     */
    private @Nullable EventImpl stronglySeeP(@Nullable final EventImpl x, final long m) {
        if (x == null) { // if there is no event, then it can't see anything
            return null;
        }
        if (notRelevantForConsensus(x)) {
            return null;
        }
        if (x.sizeStronglySeeP() != 0) { // return memoized answer, if available
            return x.getStronglySeeP((int) m);
        }
        // calculate the answer, and remember it for next time
        // find and memoize answers for all choices of m, then return answer for just this m
        final long prx = parentRound(x); // parent round of x

        x.initStronglySeeP(rosterLookup.numMembers());
        perMemberLoop:
        for (int mm = 0; mm < rosterLookup.numMembers(); mm++) {
            for (final EventImpl parent : x.getAllParents()) {
                if (ancient(parent)) {
                    continue;
                }
                if (stronglySeeP(parent, mm) != null && parentRound(parent) == prx) {
                    // if x has the same parentRound as one of its parents, then it inherits their strongly see
                    // we don't need to do the full calculation
                    x.setStronglySeeP(mm, stronglySeeP(parent, mm));
                    continue perMemberLoop;
                }
            }

            // the canonical witness by mm that is seen by x thru someone else
            final EventImpl st = seeThru(x, mm, mm);
            if (round(st) != prx) { // ignore if the canonical is in the wrong round, or doesn't exist
                x.setStronglySeeP(mm, null);
            } else {
                long weight = 0;
                for (int m3 = 0; m3 < rosterLookup.numMembers(); m3++) {
                    if (seeThru(x, mm, m3) == st) { // only count intermediates that see the canonical witness
                        weight += rosterLookup.getWeight(m3);
                    }
                }
                if (Threshold.SUPER_MAJORITY.isSatisfiedBy(
                        weight, rosterLookup.rosterTotalWeight())) { // strongly see supermajority of
                    // intermediates
                    x.setStronglySeeP(mm, st);
                } else {
                    x.setStronglySeeP(mm, null);
                }
            }
        }
        return x.getStronglySeeP((int) m);
    }

    /**
     * The round-created for event x (first round is 1), or 0 if x is null (function from
     * SWIRLDS-TR-2020-01). It also stores the round number with x.setRoundCreated(). This result is
     * memoized.
     *
     * <p>If the event has a hash in the hash lists given to the ConsensusImpl constructor, then the
     * roundCreated is set to that round number, rather than calculating it from the parents.
     *
     * <p>If a parent has a round of -1, that is treated as negative infinity. So if all parents are
     * -1, then this one will also be -1.
     *
     * @param x the event being queried
     * @return the round-created for event x, or 0 if x is null
     */
    private long round(@Nullable final EventImpl x) {
        //
        // If an event is missing (null), it must be ancient. ancient events have a round of
        // -infinity
        //
        if (x == null) {
            return ConsensusConstants.ROUND_NEGATIVE_INFINITY;
        }

        //
        // Is the round already memoized? If it is, return the memoized value
        //
        if (x.getRoundCreated() >= ConsensusConstants.ROUND_NEGATIVE_INFINITY) {
            return x.getRoundCreated();
        }

        //
        // events older than all the judges in the latest decided round as well as consensus events
        // have a round of -infinity. this covers ancient events as well because the ancient
        // generation will always be older than the latest decided round generation
        //
        if (rounds.isOlderThanDecidedRoundGeneration(x) || x.isConsensus()) {
            x.setRoundCreated(ConsensusConstants.ROUND_NEGATIVE_INFINITY);
            return ConsensusConstants.ROUND_NEGATIVE_INFINITY;
        }

        //
        // if this event has no parents, then it's the first round
        //
        if (x.getAllParents().isEmpty()) {
            x.setRoundCreated(ConsensusConstants.ROUND_FIRST);
            return x.getRoundCreated();
        }

        long greatestParentRound = ConsensusConstants.ROUND_NEGATIVE_INFINITY;
        long previousParentRound = Long.MIN_VALUE;
        boolean allParentsHaveTheSameRound = true;
        for (final EventImpl parent : x.getAllParents()) {
            if (ancient(parent)) {
                continue;
            }
            final long parentRound = round(parent);
            if (parentRound > greatestParentRound) {
                greatestParentRound = parentRound;
            }

            if (previousParentRound != Long.MIN_VALUE && parentRound != previousParentRound) {
                allParentsHaveTheSameRound = false;
            }
            previousParentRound = parentRound;
        }

        //
        // If parents have unequal rounds, we can assign the higher parent round to this event
        // because if the higher parent round couldn't strongly see a supermajority of witnesses,
        // then this event also cannot.
        //
        // There is an edge case where a single node has a super majority of weight. In this case,
        // we do want to advance if the parent with the higher round has a super majority, so we
        // continue to the super majority check below. This edge case only occurs in testing, so
        // we do not optimize for it here.
        //
        if (!allParentsHaveTheSameRound && !rosterLookup.nodeHasSupermajorityWeight()) {
            x.setRoundCreated(greatestParentRound);
            return x.getRoundCreated();
        }

        //
        // If the greatest parent round is -infinity, then all are -infinity, and this is -infinity
        //
        if (greatestParentRound == ConsensusConstants.ROUND_NEGATIVE_INFINITY) {
            x.setRoundCreated(ConsensusConstants.ROUND_NEGATIVE_INFINITY);
            return x.getRoundCreated();
        }

        // number of members that are voting
        final int numMembers = rosterLookup.numMembers();

        // parents have equal rounds (not -1) OR they are different and one has a super-majority,
        // so check if x can strongly see witnesses with a supermajority of weight.
        // sum of weight involved
        long weight = 0;
        int numStronglySeen = 0;
        for (int m = 0; m < numMembers; m++) {
            if (timedStronglySeeP(x, m) != null) {
                weight += rosterLookup.getWeight(m);
                numStronglySeen++;
            }
        }
        consensusMetrics.witnessesStronglySeen(numStronglySeen);
        if (Threshold.SUPER_MAJORITY.isSatisfiedBy(weight, rosterLookup.rosterTotalWeight())) {
            // it's a supermajority, so advance to the next round
            x.setRoundCreated(1 + parentRound(x));
            consensusMetrics.roundIncrementedByStronglySeen();
            return x.getRoundCreated();
        }
        // it's not a supermajority, so don't advance to the next round
        x.setRoundCreated(parentRound(x));
        return x.getRoundCreated();
    }

    /**
     * The self-ancestor of x in the same round that is a witness (function from
     * SWIRLDS-TR-2020-01). This result is memoized.
     *
     * @param x the event being queried
     * @return The ancestor of x in the same round that is a witness, or null if x is null
     */
    private @Nullable EventImpl firstSelfWitnessS(@Nullable final EventImpl x) {
        if (x == null) {
            return null;
        }
        if (notRelevantForConsensus(x)) {
            return null;
        }
        if (x.getFirstSelfWitnessS() != null) { // if already found and memoized, return it
            return x.getFirstSelfWitnessS();
        }
        // calculate, memoize, and return the result
        if (round(x) > round(selfParent(x))) {
            x.setFirstSelfWitnessS(x);
        } else {
            x.setFirstSelfWitnessS(firstSelfWitnessS(selfParent(x)));
        }
        return x.getFirstSelfWitnessS();
    }

    /**
     * The earliest witness that is an ancestor of x in the same round as x (function from
     * SWIRLDS-TR-2020-01). This result is memoized.
     *
     * @param x the event being queried
     * @return the earliest witness that is an ancestor of x in the same round as x, or null if x is
     *     null
     */
    private @Nullable EventImpl firstWitnessS(@Nullable final EventImpl x) {
        if (x == null) {
            return null;
        }
        if (notRelevantForConsensus(x)) {
            return null;
        }
        if (x.getFirstWitnessS() != null) { // if already found and memoized, return it
            return x.getFirstWitnessS();
        }
        if (round(x) > parentRound(x)) {
            x.setFirstWitnessS(x);
            return x;
        }

        // Find the first parent that is in the same round as x. The first witness of that parent
        // is the first witness of x. If there are no parents with the same round as x, then the
        // first witness of x is x.
        for (final EventImpl parent : x.getAllParents()) {
            if (round(parent) == round(x)) {
                x.setFirstWitnessS(firstWitnessS(parent));
                return x.getFirstWitnessS();
            }
        }

        x.setFirstWitnessS(x);
        return x;
    }

    /**
     * The event by m that x strongly sees in the round before the created round of x (function from
     * SWIRLDS-TR-2020-01). This result is not memoized.
     *
     * @param x the event being queried
     * @param m the member ID of the creator
     * @return event by m that x strongly sees in the round before the created round of x, or null
     *     if none
     */
    private @Nullable EventImpl stronglySeeS1(@Nullable final EventImpl x, final long m) {
        return timedStronglySeeP(firstWitnessS(x), m);
    }

    /**
     * The first witness in round r that is a self-ancestor of x, where r is the round of the last
     * event by m that is seen by x (function from SWIRLDS-TR-2020-01). This result is not memoized.
     *
     * @param x the event being queried
     * @param m the member ID of the creator
     * @return firstSelfWitnessS(lastSee ( x, m)), which is the first witness in round r that is a
     *     self-ancestor of x, where r is the round of the last event by m that is seen by x, or
     *     null if none
     */
    private @Nullable EventImpl firstSee(@Nullable final EventImpl x, final long m) {
        return firstSelfWitnessS(lastSee(x, m));
    }

    @Override
    public long getMaxRound() {
        return rounds.getMaxRound();
    }

    @Override
    public long getFameDecidedBelow() {
        return rounds.getFameDecidedBelow();
    }
}
