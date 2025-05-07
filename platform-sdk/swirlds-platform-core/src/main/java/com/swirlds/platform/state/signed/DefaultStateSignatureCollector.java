// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.config.StateConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.SignatureType;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.sequence.set.SequenceSet;
import org.hiero.consensus.model.sequence.set.StandardSequenceSet;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Collects signatures for signed states. This class ensures that all the non-ancient states that are not fully signed
 * (as long as they are not too old) are kept so that signatures can be collected for it. This class returns states once
 * they are either:
 * <ul>
 *     <li>fully signed</li>
 *     <li>too old</li>
 * </ul>
 */
public class DefaultStateSignatureCollector implements StateSignatureCollector {
    private static final Logger logger = LogManager.getLogger(DefaultStateSignatureCollector.class);
    /** The latest signed state round */
    private long lastStateRound = ConsensusConstants.ROUND_UNDEFINED;
    /** Signed states awaiting signatures */
    private final Map<Long, ReservedSignedState> incompleteStates = new HashMap<>();
    /** State config */
    private final StateConfig stateConfig;
    /** Signatures for rounds in the future */
    private final SequenceSet<SavedSignature> savedSignatures;
    /** A collection of signed state metrics */
    private final SignedStateMetrics signedStateMetrics;

    /**
     * Start empty, with no known signed states. A signed state is considered completed when it has signatures from a
     * sufficient threshold of nodes.
     *
     * @param platformContext    the platform context
     * @param signedStateMetrics a collection of signed state metrics
     */
    public DefaultStateSignatureCollector(
            @NonNull final PlatformContext platformContext, @NonNull final SignedStateMetrics signedStateMetrics) {
        this.stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);
        this.signedStateMetrics = Objects.requireNonNull(signedStateMetrics);

        this.savedSignatures =
                new StandardSequenceSet<>(0, stateConfig.maxAgeOfFutureStateSignatures(), SavedSignature::round);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable List<ReservedSignedState> addReservedState(
            @NonNull final ReservedSignedState reservedSignedState) {
        Objects.requireNonNull(reservedSignedState, "reservedSignedState");
        final SignedState signedState = reservedSignedState.get();

        if (signedState.getState().getHash() == null) {
            throw new IllegalArgumentException(
                    "Unhashed state for round " + signedState.getRound() + " added to the signed state manager");
        }

        // Double check that the signatures on this state are valid.
        // They may no longer be valid if we have done a data migration.
        signedState.pruneInvalidSignatures();

        // find any signatures that have been saved
        final List<SavedSignature> signatures = savedSignatures.getEntriesWithSequenceNumber(signedState.getRound());
        savedSignatures.removeSequenceNumber(signedState.getRound());
        signatures.forEach(ss -> addSignature(reservedSignedState, ss.memberId(), ss.signature()));

        lastStateRound = Math.max(lastStateRound, signedState.getRound());
        adjustSavedSignaturesWindow(signedState.getRound());

        // complete states and freeze states will be written immediately to disk
        // incomplete non-freeze states will be kept until they are complete or too old
        if (!signedState.isComplete() && !signedState.isFreezeState()) {
            final ReservedSignedState previousState = incompleteStates.put(signedState.getRound(), reservedSignedState);
            if (previousState != null) {
                previousState.close();
                logger.warn(
                        LogMarker.EXCEPTION.getMarker(),
                        "Two states with the same round ({}) have been added to the signature collector",
                        signedState.getRound());
            }
            return Optional.of(purgeOldStates()).filter(l -> !l.isEmpty()).orElse(null);
        }
        return Stream.concat(Stream.of(reservedSignedState), purgeOldStates().stream())
                .filter(Objects::nonNull)
                .collect(collectingAndThen(toList(), l -> l.isEmpty() ? null : l));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable List<ReservedSignedState> handlePreconsensusSignatures(
            @NonNull final Queue<ScopedSystemTransaction<StateSignatureTransaction>> transactions) {
        Objects.requireNonNull(transactions, "transactions");
        return transactions.stream()
                .map(this::handlePreconsensusSignature)
                .filter(Objects::nonNull)
                .collect(collectingAndThen(toList(), l -> l.isEmpty() ? null : l));
    }

    private @Nullable ReservedSignedState handlePreconsensusSignature(
            @NonNull final ScopedSystemTransaction<StateSignatureTransaction> scopedTransaction) {

        final long round = scopedTransaction.transaction().round();
        final Signature signature = new Signature(
                SignatureType.RSA, scopedTransaction.transaction().signature().toByteArray());

        signedStateMetrics.getStateSignaturesGatheredPerSecondMetric().cycle();

        if (lastStateRound != -1) {
            final long signatureAge = round - lastStateRound;
            signedStateMetrics.getStateSignatureAge().update(signatureAge);
        }

        final ReservedSignedState reservedState = incompleteStates.get(round);
        if (reservedState == null) {
            // This round has already been completed, or it is really old or in the future
            savedSignatures.add(new SavedSignature(round, scopedTransaction.submitterId(), signature));
            return null;
        }
        return addSignature(reservedState, scopedTransaction.submitterId(), signature);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable List<ReservedSignedState> handlePostconsensusSignatures(
            @NonNull final Queue<ScopedSystemTransaction<StateSignatureTransaction>> transactions) {
        Objects.requireNonNull(transactions, "transactions");
        return transactions.stream()
                .map(this::handlePostconsensusSignature)
                .filter(Objects::nonNull)
                .collect(collectingAndThen(toList(), l -> l.isEmpty() ? null : l));
    }

    private @Nullable ReservedSignedState handlePostconsensusSignature(
            @NonNull final ScopedSystemTransaction<StateSignatureTransaction> scopedTransaction) {
        final long round = scopedTransaction.transaction().round();

        final ReservedSignedState reservedState = incompleteStates.get(round);
        // it isn't possible to receive a postconsensus signature transaction for a future round,
        // and if we don't have the state for an old round, we never will.
        // in both cases, the signature can be ignored
        if (reservedState == null) {
            return null;
        }

        return addSignature(
                reservedState,
                scopedTransaction.submitterId(),
                new Signature(
                        SignatureType.RSA,
                        scopedTransaction.transaction().signature().toByteArray()));
    }

    /**
     * Add a new signature to a signed state.
     *
     * @param reservedSignedState the state being signed
     * @param nodeId              the ID of the signer
     * @param signature           the signature on the state
     * @return the signed state if it is now complete, otherwise null
     */
    private @Nullable ReservedSignedState addSignature(
            @NonNull final ReservedSignedState reservedSignedState,
            @NonNull final NodeId nodeId,
            @NonNull final Signature signature) {
        final SignedState signedState = reservedSignedState.get();

        if (signedState.addSignature(nodeId, signature)) {
            // at this point the signed state is complete for the first time
            signedStateMetrics.getStatesSignedPerSecondMetric().cycle();
            signedStateMetrics
                    .getAverageTimeToFullySignStateMetric()
                    .update(Duration.between(signedState.getCreationTimestamp(), Instant.now())
                            .toMillis());

            return incompleteStates.remove(signedState.getRound());
        }
        return null;
    }

    /**
     * Get the earliest round that is permitted to be stored in this data structure.
     *
     * @return the earliest round permitted to be stored
     */
    private long getEarliestPermittedRound() {
        return lastStateRound - stateConfig.roundsToKeepForSigning() + 1;
    }

    /**
     * Get rid of old states.
     *
     * @return a list of states that were purged
     */
    private @NonNull List<ReservedSignedState> purgeOldStates() {
        final List<ReservedSignedState> purgedStates = new ArrayList<>();

        // Any state older than this is unconditionally removed.
        final long earliestPermittedRound = getEarliestPermittedRound();
        for (final Iterator<ReservedSignedState> iterator =
                        incompleteStates.values().iterator();
                iterator.hasNext(); ) {
            final ReservedSignedState reservedSignedState = iterator.next();
            final SignedState signedState = reservedSignedState.get();
            if (signedState.getRound() < earliestPermittedRound) {
                signedStateMetrics.getTotalUnsignedStatesMetric().increment();
                purgedStates.add(reservedSignedState);
                iterator.remove();
            }
        }

        signedStateMetrics.getUnsignedStatesMetric().update(incompleteStates.size());
        return purgedStates;
    }

    /**
     * Adjust the window where we are willing to save future signatures.
     *
     * @param currentRound the round of the most recently signed state
     */
    private void adjustSavedSignaturesWindow(final long currentRound) {
        // Only save signatures for round N+1 and after.
        // Any rounds behind this one will either have already had a SignedState
        // added to this manager, or will never have a SignedState added to this manager.
        if (savedSignatures.getFirstSequenceNumberInWindow() < currentRound + 1) {
            savedSignatures.shiftWindow(currentRound + 1);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear(@NonNull final Object ignored) {
        for (final Iterator<ReservedSignedState> iterator =
                        incompleteStates.values().iterator();
                iterator.hasNext(); ) {
            final ReservedSignedState state = iterator.next();
            state.close();
            iterator.remove();
        }
        savedSignatures.clear();
        lastStateRound = ConsensusConstants.ROUND_UNDEFINED;
    }

    /**
     * A signature for a state hash that was received when this node does not yet have a state with a matching round.
     */
    private record SavedSignature(long round, @NonNull NodeId memberId, @NonNull Signature signature) {}
}
