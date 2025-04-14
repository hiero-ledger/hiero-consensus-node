// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.SIGNED_STATE;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.Mnemonics;
import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.logging.legacy.payload.IssPayload;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.consensus.ConsensusConfig;
import com.swirlds.platform.metrics.IssMetrics;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.iss.internal.ConsensusHashFinder;
import com.swirlds.platform.state.iss.internal.HashValidityStatus;
import com.swirlds.platform.state.iss.internal.RoundHashValidator;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.util.MarkerFileWriter;
import com.swirlds.state.lifecycle.HapiUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;
import org.hiero.consensus.model.sequence.set.SequenceSet;
import org.hiero.consensus.model.sequence.set.StandardSequenceSet;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * A default implementation of the {@link IssDetector}.
 */
public class DefaultIssDetector implements IssDetector {

    private static final Logger logger = LogManager.getLogger(DefaultIssDetector.class);

    private final SequenceMap<Long /* round */, RoundHashValidator> roundData;
    /** Signatures for rounds in the future */
    private final SequenceSet<ScopedSystemTransaction<StateSignatureTransaction>> savedSignatures;

    private long previousRound = -1;

    /**
     * The roster of this network.
     */
    private final Roster roster;

    /**
     * The current software version
     */
    private final SemanticVersion currentSoftwareVersion;

    /**
     * Prevent log messages about a lack of signatures from spamming the logs.
     */
    private final RateLimiter lackingSignaturesRateLimiter;

    /**
     * Prevent log messages about self ISS events from spamming the logs.
     */
    private final RateLimiter selfIssRateLimiter;

    /**
     * Prevent log messages about catastrophic ISS events from spamming the logs.
     */
    private final RateLimiter catastrophicIssRateLimiter;

    /**
     * If true, ignore signatures from the preconsensus event stream, otherwise validate them like normal.
     */
    private final boolean ignorePreconsensusSignatures;

    /**
     * Set to false once all preconsensus events have been replayed.
     */
    private boolean replayingPreconsensusStream = true;

    /**
     * A round that should not be validated. Set to {@link #DO_NOT_IGNORE_ROUNDS} if all rounds should be validated.
     */
    private final long ignoredRound;

    /**
     * ISS related metrics
     */
    private final IssMetrics issMetrics;

    /**
     * Writes marker files to disk.
     */
    private final MarkerFileWriter markerFileWriter;

    /**
     * Create an object that tracks reported hashes and detects ISS events.
     *
     * @param platformContext              the platform context
     * @param roster                       the roster for the network
     * @param currentSoftwareVersion       the current software version
     * @param ignorePreconsensusSignatures If true, ignore signatures from the preconsensus event stream, otherwise
     *                                     validate them like normal.
     * @param ignoredRound                 a round that should not be validated. Set to {@link #DO_NOT_IGNORE_ROUNDS} if
     *                                     all rounds should be validated.
     */
    public DefaultIssDetector(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final SemanticVersion currentSoftwareVersion,
            final boolean ignorePreconsensusSignatures,
            final long ignoredRound) {
        Objects.requireNonNull(platformContext);
        markerFileWriter = new MarkerFileWriter(platformContext);

        final ConsensusConfig consensusConfig =
                platformContext.getConfiguration().getConfigData(ConsensusConfig.class);
        final StateConfig stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);

        final Duration timeBetweenIssLogs = Duration.ofSeconds(stateConfig.secondsBetweenIssLogs());
        lackingSignaturesRateLimiter = new RateLimiter(platformContext.getTime(), timeBetweenIssLogs);
        selfIssRateLimiter = new RateLimiter(platformContext.getTime(), timeBetweenIssLogs);
        catastrophicIssRateLimiter = new RateLimiter(platformContext.getTime(), timeBetweenIssLogs);

        this.roster = Objects.requireNonNull(roster);
        this.currentSoftwareVersion = Objects.requireNonNull(currentSoftwareVersion);

        this.roundData = new StandardSequenceMap<>(
                -consensusConfig.roundsNonAncient(), consensusConfig.roundsNonAncient(), x -> x);
        this.savedSignatures = new StandardSequenceSet<>(
                0, consensusConfig.roundsNonAncient(), s -> s.transaction().round());

        this.ignorePreconsensusSignatures = ignorePreconsensusSignatures;
        if (ignorePreconsensusSignatures) {
            logger.info(STARTUP.getMarker(), "State signatures from the preconsensus event stream will be ignored.");
        }

        this.ignoredRound = ignoredRound;
        if (ignoredRound != DO_NOT_IGNORE_ROUNDS) {
            logger.warn(STARTUP.getMarker(), "No ISS detection will be performed for round {}", ignoredRound);
        }
        this.issMetrics = new IssMetrics(platformContext.getMetrics(), roster);
    }

    /**
     * This method is called once all preconsensus events have been replayed.
     */
    @Override
    public void signalEndOfPreconsensusReplay() {
        replayingPreconsensusStream = false;
    }

    /**
     * Create an ISS notification if the round shouldn't be ignored
     *
     * @param roundNumber the round number of the ISS
     * @param issType     the type of the ISS
     * @return an ISS notification, or null if the round of the ISS should be ignored
     */
    @Nullable
    private IssNotification maybeCreateIssNotification(final long roundNumber, @NonNull final IssType issType) {
        if (roundNumber == ignoredRound) {
            return null;
        }
        markerFileWriter.writeMarkerFile(issType.toString());
        return new IssNotification(roundNumber, issType);
    }

    /**
     * Shift the round data window when a new round's state is hashed, and add the new round's data.
     * <p>
     * If any round that is removed by shifting the window hasn't already had its hash decided, then this method will
     * force a decision on the hash, and handle any ISS events that result.
     *
     * @param roundNumber the round that was just completed
     * @return a list of ISS notifications, which may be empty, but will not contain null
     */
    @NonNull
    private List<IssNotification> shiftRoundDataWindow(final long roundNumber) {
        if (roundNumber <= previousRound) {
            throw new IllegalArgumentException(
                    "previous round was " + previousRound + ", can't decrease round to " + roundNumber);
        }

        final long oldestRoundToValidate = roundNumber - roundData.getSequenceNumberCapacity() + 1;

        final List<RoundHashValidator> removedRounds = new ArrayList<>();
        if (roundNumber != previousRound + 1) {
            // We are either loading the first state at boot time, or we had a reconnect that caused us to skip some
            // rounds. Rounds that have not yet been validated at this point in time should not be considered
            // evidence of a catastrophic ISS.
            roundData.shiftWindow(oldestRoundToValidate);
        } else {
            roundData.shiftWindow(oldestRoundToValidate, (k, v) -> removedRounds.add(v));
        }

        previousRound = roundNumber;

        roundData.put(
                roundNumber, new RoundHashValidator(roundNumber, RosterUtils.computeTotalWeight(roster), issMetrics));

        return removedRounds.stream()
                .map(this::handleRemovedRound)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<IssNotification> handleStateSignatureTransactions(
            @NonNull final Collection<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions) {
        final List<IssNotification> issNotifications = new ArrayList<>();
        // The state signatures in the queue may be for state hashes of different rounds.
        // Iterate through them and handle each individually.
        for (final ScopedSystemTransaction<StateSignatureTransaction> transaction : systemTransactions) {
            final StateSignatureTransaction signaturePayload = transaction.transaction();
            final long round = signaturePayload.round();

            // If the signature is for a state hash that this component is already tracking, apply it now.
            // Otherwise, save it for later.
            if (round < savedSignatures.getFirstSequenceNumberInWindow()) {
                final IssNotification issNotification = handlePostconsensusSignature(transaction);
                if (issNotification != null) {
                    issNotifications.add(issNotification);
                }
            } else {
                savedSignatures.add(transaction);
            }
        }
        return issNotifications;
    }

    /**
     * Called when a round has been completed.
     * <p>
     * Expects the state to have been reserved by the caller for this method. This method will release the state
     * reservation when it is done with it.
     *
     * @param reservedSignedState the reserved state to be handled
     * @return a list of ISS notifications, or null if no ISS occurred
     */
    @Override
    @Nullable
    public List<IssNotification> handleState(@NonNull final ReservedSignedState reservedSignedState) {
        try (reservedSignedState) {
            final SignedState state = reservedSignedState.get();
            final long roundNumber = state.getRound();

            final List<IssNotification> issNotifications = new ArrayList<>(shiftRoundDataWindow(roundNumber));

            // Apply any signatures we collected previously that are for this round
            issNotifications.addAll(applySignaturesAndShiftWindow(roundNumber));

            final IssNotification selfHashCheckResult =
                    checkSelfStateHash(roundNumber, state.getState().getHash());
            if (selfHashCheckResult != null) {
                issNotifications.add(selfHashCheckResult);
            }

            return issNotifications.isEmpty() ? null : issNotifications;
        }
    }

    /**
     * Applies any saved signatures for the given round and shifts the saved signature window.
     *
     * @param roundNumber the round to apply saved signatures to
     * @return a list of ISS notifications, or an empty list if no ISS occurred
     */
    @NonNull
    private List<IssNotification> applySignaturesAndShiftWindow(final long roundNumber) {
        // Apply any signatures we collected previously that are for the current round
        final List<IssNotification> issNotifications = new ArrayList<>(
                handlePostconsensusSignatures(savedSignatures.getEntriesWithSequenceNumber(roundNumber)));
        savedSignatures.shiftWindow(roundNumber + 1);

        return issNotifications;
    }

    /**
     * Handle a round that has become old enough that we want to stop tracking data on it.
     *
     * @param roundHashValidator the hash validator for the round
     * @return an ISS notification, or null if no ISS occurred
     */
    @Nullable
    private IssNotification handleRemovedRound(@NonNull final RoundHashValidator roundHashValidator) {
        final boolean justDecided = roundHashValidator.outOfTime();

        final StringBuilder sb = new StringBuilder();
        roundHashValidator.getHashFinder().writePartitionData(sb);
        logger.info(STATE_HASH.getMarker(), sb);

        if (justDecided) {
            final HashValidityStatus status = roundHashValidator.getStatus();
            if (status == HashValidityStatus.CATASTROPHIC_ISS
                    || status == HashValidityStatus.CATASTROPHIC_LACK_OF_DATA) {

                final IssNotification notification =
                        maybeCreateIssNotification(roundHashValidator.getRound(), IssType.CATASTROPHIC_ISS);
                if (notification != null) {
                    handleCatastrophic(roundHashValidator);
                }

                return notification;
            } else if (status == HashValidityStatus.LACK_OF_DATA) {
                handleLackOfData(roundHashValidator);
            } else {
                throw new IllegalStateException(
                        "Unexpected hash validation status " + status + ", should have decided prior to now");
            }
        }
        return null;
    }

    /**
     * Handle postconsensus state signatures.
     *
     * @param stateSignatureTransactions the transactions containing state signatures
     * @return a list of ISS notifications, which may be empty, but will not contain null
     */
    @NonNull
    private List<IssNotification> handlePostconsensusSignatures(
            final List<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactions) {
        if (stateSignatureTransactions == null) {
            return List.of();
        }

        return stateSignatureTransactions.stream()
                .map(this::handlePostconsensusSignature)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * <p>
     * Observes post-consensus state signature transactions.
     * </p>
     *
     * <p>
     * Since it is only possible to sign a round after it has reached consensus, it is guaranteed that any valid
     * signature transaction observed here (post consensus) will be for a round in the past.
     * </p>
     *
     * @param transaction the transaction to handle
     * @return an ISS notification, or null if no ISS occurred
     */
    @Nullable
    private IssNotification handlePostconsensusSignature(
            @NonNull final ScopedSystemTransaction<StateSignatureTransaction> transaction) {
        final NodeId signerId = transaction.submitterId();
        final StateSignatureTransaction signaturePayload = transaction.transaction();
        final SemanticVersion eventVersion = transaction.softwareVersion();

        if (eventVersion == null) {
            // Illegal event version, ignore.
            return null;
        }

        if (ignorePreconsensusSignatures && replayingPreconsensusStream) {
            // We are still replaying preconsensus events, and we are configured to ignore signatures during replay
            return null;
        }

        if (HapiUtils.SEMANTIC_VERSION_COMPARATOR.compare(currentSoftwareVersion, eventVersion) != 0) {
            // this is a signature from a different software version, ignore it
            return null;
        }

        final RosterEntry node = RosterUtils.getRosterEntryOrNull(roster, signerId.id());

        if (node == null) {
            // we don't care about nodes not in the address book
            return null;
        }

        if (signaturePayload.round() == ignoredRound) {
            // This round is intentionally ignored.
            return null;
        }

        final RoundHashValidator roundValidator = roundData.get(signaturePayload.round());
        if (roundValidator == null) {
            // We are being asked to validate a signature from the far future or far past, or a round that has already
            // been decided.
            return null;
        }

        final boolean decided =
                roundValidator.reportHashFromNetwork(signerId, node.weight(), new Hash(signaturePayload.hash()));
        if (decided) {
            return checkValidity(roundValidator);
        }
        return null;
    }

    /**
     * Checks the validity of the self state hash for a round.
     *
     * @param round the round of the state
     * @param hash  the hash of the state
     * @return an ISS notification, or null if no ISS occurred
     */
    @Nullable
    private IssNotification checkSelfStateHash(final long round, @NonNull final Hash hash) {
        final RoundHashValidator roundHashValidator = roundData.get(round);
        if (roundHashValidator == null) {
            throw new IllegalStateException(
                    "Hash reported for round " + round + ", but that round is not being tracked");
        }

        final boolean decided = roundHashValidator.reportSelfHash(hash);
        if (decided) {
            return checkValidity(roundHashValidator);
        }
        return null;
    }

    /**
     * Called when an overriding state is obtained, i.e. via reconnect or state loading.
     * <p>
     * Expects the input state to have been reserved by the caller for this method. This method will release the state
     * reservation when it is done with it.
     *
     * @param state the state that was loaded
     * @return a list of ISS notifications, or null if no ISS occurred
     */
    @Override
    @Nullable
    public List<IssNotification> overridingState(@NonNull final ReservedSignedState state) {
        try (state) {
            final long roundNumber = state.get().getRound();
            // this is not practically possible for an ISS to occur for hashes before the state provided
            // in this method. Even if it were to happen, on a reconnect, we are receiving a new state that is fully
            // signed, so any ISSs in the past should be ignored. so we will ignore any ISSs from removed rounds
            shiftRoundDataWindow(roundNumber);

            // Apply any signatures we collected previously that are for this round. It is not practically
            // possible for there to be any signatures stored up for this state, but there is no harm in
            // applying any that exist since we are now tracking this state.
            final List<IssNotification> issNotifications = applySignaturesAndShiftWindow(roundNumber);

            final Hash stateHash = state.get().getState().getHash();
            final IssNotification issNotification = checkSelfStateHash(roundNumber, stateHash);
            if (issNotification != null) {
                issNotifications.add(issNotification);
            }

            if (issNotifications.isEmpty()) {
                return null;
            } else {
                logger.warn(
                        SIGNED_STATE.getMarker(),
                        "An ISS was detected for an overriding state for round {}. This should not be possible.",
                        roundNumber);
                return issNotifications;
            }
        }
    }

    /**
     * Called once the validity has been decided. Take action based on the validity status.
     *
     * @param roundValidator the validator for the round
     * @return an ISS notification, or null if no ISS occurred
     */
    @Nullable
    private IssNotification checkValidity(@NonNull final RoundHashValidator roundValidator) {
        final long round = roundValidator.getRound();

        return switch (roundValidator.getStatus()) {
            case VALID -> {
                if (roundValidator.hasDisagreement()) {
                    yield maybeCreateIssNotification(round, IssType.OTHER_ISS);
                }
                yield null;
            }
            case SELF_ISS -> {
                final IssNotification notification = maybeCreateIssNotification(round, IssType.SELF_ISS);
                if (notification != null) {
                    handleSelfIss(roundValidator);
                }
                yield notification;
            }
            case CATASTROPHIC_ISS -> {
                final IssNotification notification = maybeCreateIssNotification(round, IssType.CATASTROPHIC_ISS);
                if (notification != null) {
                    handleCatastrophic(roundValidator);
                }
                yield notification;
            }
            case UNDECIDED -> throw new IllegalStateException(
                    "status is undecided, but method reported a decision, round = " + round);
            case LACK_OF_DATA -> throw new IllegalStateException(
                    "a decision that we lack data should only be possible once time runs out, round = " + round);
            default -> throw new IllegalStateException(
                    "unhandled case " + roundValidator.getStatus() + ", round = " + round);
        };
    }

    /**
     * This node doesn't agree with the consensus hash.
     *
     * @param roundHashValidator the validator responsible for validating the round with a self ISS
     */
    private void handleSelfIss(@NonNull final RoundHashValidator roundHashValidator) {
        final long round = roundHashValidator.getRound();
        final Hash selfHash = roundHashValidator.getSelfStateHash();
        final Hash consensusHash = roundHashValidator.getConsensusHash();

        final long skipCount = selfIssRateLimiter.getDeniedRequests();
        if (selfIssRateLimiter.requestAndTrigger()) {

            final StringBuilder sb = new StringBuilder();
            sb.append("Invalid State Signature (ISS): this node has the wrong hash for round ")
                    .append(round)
                    .append(".\n");

            roundHashValidator.getHashFinder().writePartitionData(sb);
            writeSkippedLogCount(sb, skipCount);

            logger.fatal(
                    EXCEPTION.getMarker(),
                    new IssPayload(
                            sb.toString(),
                            round,
                            Mnemonics.generateMnemonic(selfHash),
                            Mnemonics.generateMnemonic(consensusHash),
                            false));
        }
    }

    /**
     * There has been a catastrophic ISS or a catastrophic lack of data.
     *
     * @param roundHashValidator information about the round, including the signatures that were gathered
     */
    private void handleCatastrophic(@NonNull final RoundHashValidator roundHashValidator) {

        final long round = roundHashValidator.getRound();
        final ConsensusHashFinder hashFinder = roundHashValidator.getHashFinder();
        final Hash selfHash = roundHashValidator.getSelfStateHash();

        final long skipCount = catastrophicIssRateLimiter.getDeniedRequests();
        if (catastrophicIssRateLimiter.requestAndTrigger()) {

            final StringBuilder sb = new StringBuilder();
            sb.append("Catastrophic Invalid State Signature (ISS)\n");
            sb.append("Due to divergence in state hash between many network members, "
                    + "this network is incapable of continued operation without human intervention.\n");

            hashFinder.writePartitionData(sb);
            writeSkippedLogCount(sb, skipCount);

            final String mnemonic = selfHash == null ? "null" : Mnemonics.generateMnemonic(selfHash);
            logger.fatal(EXCEPTION.getMarker(), new IssPayload(sb.toString(), round, mnemonic, "", true));
        }
    }

    /**
     * We are not getting the signatures we need to be getting. ISS events may be going undetected.
     *
     * @param roundHashValidator information about the round
     */
    private void handleLackOfData(@NonNull final RoundHashValidator roundHashValidator) {
        final long skipCount = lackingSignaturesRateLimiter.getDeniedRequests();
        if (!lackingSignaturesRateLimiter.requestAndTrigger()) {
            return;
        }

        final long round = roundHashValidator.getRound();
        final ConsensusHashFinder hashFinder = roundHashValidator.getHashFinder();
        final Hash selfHash = roundHashValidator.getSelfStateHash();

        final StringBuilder sb = new StringBuilder();
        sb.append("Unable to collect enough data to determine the consensus hash for round ")
                .append(round)
                .append(".\n");
        if (selfHash == null) {
            sb.append("No self hash was computed. This is highly unusual.\n");
        }
        hashFinder.writePartitionData(sb);
        writeSkippedLogCount(sb, skipCount);

        logger.warn(STATE_HASH.getMarker(), sb);
    }

    /**
     * Write the number of times a log has been skipped.
     */
    private static void writeSkippedLogCount(@NonNull final StringBuilder sb, final long skipCount) {
        if (skipCount > 0) {
            sb.append("This condition has been triggered ")
                    .append(skipCount)
                    .append(" time(s) over the last ")
                    .append(Duration.ofMinutes(1).toSeconds())
                    .append("seconds.");
        }
    }
}
