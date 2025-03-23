// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.common.utility.Threshold.MAJORITY;
import static com.swirlds.common.utility.Threshold.SUPER_MAJORITY;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.state.signed.SignedStateHistory.SignedStateAction.CREATION;
import static com.swirlds.platform.state.signed.SignedStateHistory.SignedStateAction.RELEASE;
import static com.swirlds.platform.state.signed.SignedStateHistory.SignedStateAction.RESERVE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.utility.ReferenceCounter;
import com.swirlds.common.utility.RuntimeObjectRecord;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import com.swirlds.common.utility.Threshold;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.crypto.SignatureVerifier;
import com.swirlds.platform.roster.RosterRetriever;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedStateHistory.SignedStateAction;
import com.swirlds.platform.state.snapshot.StateToDiskReason;
import com.swirlds.platform.system.address.Address;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * <p>
 * This is a signed state, in a form that allows those outside the network to verify that it is a legitimate state at a
 * given time.
 * </p>
 *
 * <p>
 * It includes a copy of a SwirldsState at a given moment, and the address book, and the history of the address book,
 * and a set of signatures (with identities of the signers) attesting to it.
 * </p>
 *
 * <p>
 * It can be created at the moment all the famous witnesses become known for a given round. The signatures can be
 * created and collected for every round, or for every Nth round, or for each round whose last famous witness has a
 * consensus time stamp at least T seconds after the last signed state, or by some other criterion.
 * </p>
 *
 * <p>
 * The signed state is also saved to disk, and is given to a new member joining the network, or to an old member
 * rejoining after a long absence.
 * </p>
 */
public class SignedState implements SignedStateInfo {

    private static final Logger logger = LogManager.getLogger(SignedState.class);

    /**
     * the signatures collected so far (including from self)
     */
    private SigSet sigSet;

    /**
     * The total weight that has signed this state.
     */
    private long signingWeight;

    /**
     * Is this the last state saved before the freeze period
     */
    private final boolean freezeState;

    /**
     * True if this state has been deleted. Used to prevent the same state from being deleted more than once.
     */
    private boolean deleted = false;

    /**
     * The root of the merkle state.
     */
    private final MerkleNodeState state;

    /**
     * The timestamp of when this object was created.
     */
    private final Instant creationTimestamp = Instant.now();

    /**
     * If not null, then this state should eventually be written to disk. The enum value indicates the reason that the
     * state should be written to disk
     */
    private StateToDiskReason stateToDiskReason;

    /**
     * Indicates whether this signed state has been saved to disk.
     * <p>
     * Note: this value only applies to signed states that are saved inside the normal workflow: states that are dumped
     * out of band do not affect this value.
     */
    private boolean hasBeenSavedToDisk;

    /**
     * Indicates if this state is a special state used to jumpstart emergency recovery. This will only be true for a
     * state that has a root hash that exactly matches the current epoch hash. A recovery state is considered to be
     * "completely signed" regardless of its actual signatures.
     */
    private boolean recoveryState;

    /**
     * Used to track the lifespan of this signed state.
     */
    private final RuntimeObjectRecord registryRecord;

    /**
     * Information about how this signed state was used.
     */
    private final SignedStateHistory history;

    /**
     * Keeps track of reservations on this object.
     */
    private final ReferenceCounter reservations =
            new ReferenceCounter(this::markEligibleForDeletion, this::onReferenceCountException);

    /**
     * The signature verifier used to verify signatures.
     */
    private final SignatureVerifier signatureVerifier;

    private final AtomicBoolean eligibleForDeletion = new AtomicBoolean(false);

    /**
     * If false, delete signed states on the thread that removes the last reference count. Otherwise, let the background
     * deletion handler delete the state.
     */
    private final boolean deleteOnBackgroundThread;

    /**
     * True if this round reached consensus during the replaying of the preconsensus event stream.
     */
    private final boolean pcesRound;
    /**
     * The facade to access the platform state
     */
    private final PlatformStateFacade platformStateFacade;

    /**
     * Instantiate a signed state.
     *
     * @param configuration            the configuration for this node
     * @param signatureVerifier        the signature verifier
     * @param state                    a fast copy of the state resulting from all transactions in consensus order from
     *                                 all events with received rounds up through the round this SignedState represents
     * @param reason                   a short description of why this SignedState is being created. Each location where
     *                                 a SignedState is created should attempt to use a unique reason, as this makes
     *                                 debugging reservation bugs easier.
     * @param freezeState              specifies whether this state is the last one saved before the freeze
     * @param deleteOnBackgroundThread if true, delete this state on the background thread, otherwise delete on the
     *                                 thread that removes the last reference count. Should only be set to true for
     *                                 states that have been sent to the state garbage collector.
     * @param pcesRound                true if this round reached consensus during the replaying of the preconsensus
     *                                 event stream
     * @param platformStateFacade      the facade to access the platform state
     */
    public SignedState(
            @NonNull final Configuration configuration,
            @NonNull final SignatureVerifier signatureVerifier,
            @NonNull final MerkleNodeState state,
            @NonNull final String reason,
            final boolean freezeState,
            final boolean deleteOnBackgroundThread,
            final boolean pcesRound,
            @NonNull final PlatformStateFacade platformStateFacade) {
        this.platformStateFacade = platformStateFacade;
        this.signatureVerifier = requireNonNull(signatureVerifier);
        this.state = requireNonNull(state);
        state.getRoot().reserve();

        final StateConfig stateConfig = configuration.getConfigData(StateConfig.class);
        if (stateConfig.stateHistoryEnabled()) {
            history = new SignedStateHistory(Time.getCurrent(), getRound(), stateConfig.debugStackTracesEnabled());
            history.recordAction(CREATION, getReservationCount(), reason, null);
        } else {
            history = null;
        }

        registryRecord = RuntimeObjectRegistry.createRecord(getClass(), history);
        sigSet = new SigSet();

        this.freezeState = freezeState;
        this.deleteOnBackgroundThread = deleteOnBackgroundThread;
        this.pcesRound = pcesRound;
    }

    public void init(@NonNull PlatformContext platformContext) {
        state.init(
                platformContext.getTime(),
                platformContext.getMetrics(),
                platformContext.getMerkleCryptography(),
                () -> {
                    final ConsensusSnapshot consensusSnapshot = platformStateFacade.consensusSnapshotOf(state);
                    return consensusSnapshot == null ? PlatformStateAccessor.GENESIS_ROUND : consensusSnapshot.round();
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRound() {
        return platformStateFacade.roundOf(state);
    }

    /**
     * Check if this state is the genesis state.
     *
     * @return true if this is the genesis state
     */
    public boolean isGenesisState() {
        return platformStateFacade.isGenesisStateOf(state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull SigSet getSigSet() {
        return sigSet;
    }

    /**
     * Attach signatures to this state.
     *
     * @param sigSet the signatures to be attached to this signed state
     */
    public void setSigSet(@NonNull final SigSet sigSet) {
        this.sigSet = requireNonNull(sigSet);
        signingWeight = 0;
        if (!isGenesisState()) {
            // Only non-genesis states will have signing weight
            final Map<Long, RosterEntry> entries = RosterUtils.toMap(getRoster());

            for (final NodeId signingNode : sigSet) {
                final RosterEntry entry = entries.get(signingNode.id());
                if (entry != null) {
                    signingWeight += entry.weight();
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Roster getRoster() {
        /*
        Ideally the roster would be captured in the constructor but due to the mutable underlying state, the roster
        can change from underneath us. Therefore, the roster must be regenerated on each access.
         */
        final Roster roster = RosterRetriever.retrieveActiveOrGenesisRoster(state, platformStateFacade);
        return requireNonNull(roster, "Roster stored in signed state is null (this should never happen)");
    }

    /**
     * Get the root of the state. This object should not be held beyond the scope of this SignedState or else there is
     * risk that the state may be deleted unexpectedly.
     *
     * @return the state contained in the signed state
     */
    public @NonNull MerkleNodeState getState() {
        return state;
    }

    /**
     * @return is this the last state saved before the freeze period
     */
    public boolean isFreezeState() {
        return freezeState;
    }

    /**
     * Returns true if ths round reached consensus during the replaying of the preconsensus event stream.
     *
     * @return true if this round reached consensus during the replaying of the preconsensus event stream
     */
    public boolean isPcesRound() {
        return pcesRound;
    }

    /**
     * Mark this state as a recovery state. A recovery state is a state with a root hash that exactly matches the
     * current hash epoch. Recovery states are always considered to be "completely signed" regardless of their actual
     * signatures.
     */
    public void markAsRecoveryState() {
        recoveryState = true;
    }

    /**
     * Reserves the SignedState for use. While reserved, this SignedState will not be deleted.
     *
     * @param reason a short description of why this SignedState is being reserved. Each location where a SignedState is
     *               reserved should attempt to use a unique reason, as this makes debugging reservation bugs easier.
     * @return a wrapper that holds the state and the reservation
     */
    public @NonNull ReservedSignedState reserve(@NonNull final String reason) {
        return ReservedSignedState.createAndReserve(this, reason);
    }

    /**
     * Increment reservation count.
     */
    void incrementReservationCount(@NonNull final String reason, final long reservationId) {
        if (history != null) {
            history.recordAction(RESERVE, getReservationCount(), reason, reservationId);
        }
        reservations.reserve();
    }

    /**
     * Try to increment the reservation count.
     */
    boolean tryIncrementReservationCount(@NonNull final String reason, final long reservationId) {
        if (!reservations.tryReserve()) {
            return false;
        }
        if (history != null) {
            history.recordAction(RESERVE, getReservationCount(), reason, reservationId);
        }
        return true;
    }

    /**
     * Decrement reservation count.
     */
    void decrementReservationCount(@NonNull final String reason, final long reservationId) {
        if (history != null) {
            history.recordAction(RELEASE, getReservationCount(), reason, reservationId);
        }
        reservations.release();
    }

    /**
     * Mark this state as eligible for deletion. If configured to delete on the calling thread, then this method will
     * also delete the state.
     */
    private void markEligibleForDeletion() {
        eligibleForDeletion.set(true);
        if (!deleteOnBackgroundThread) {
            delete();
        }
    }

    /**
     * Check if this state should be deleted on the background thread.
     *
     * @return true if this state should be deleted on the background thread
     */
    boolean shouldDeleteOnBackgroundThread() {
        return deleteOnBackgroundThread;
    }

    /**
     * This method is called when there is a reference count exception.
     */
    private void onReferenceCountException() {
        if (history != null) {
            logger.error(
                    EXCEPTION.getMarker(), "SignedState reference count error detected, dumping history.\n{}", history);
        }
    }

    /**
     * Check if this state is eligible for deletion. Once a state becomes eligible for deletion, this method will return
     * true even after the state has been deleted.
     *
     * @return true if this state is eligible for deletion
     */
    boolean isEligibleForDeletion() {
        return eligibleForDeletion.get();
    }

    /**
     * <p>
     * Perform deletion on this signed state.
     * </p>
     *
     * <p>
     * Under normal operation, this method will only be called on the single-threaded background deletion handler.
     * However, if the queue fills up then a different thread may attempt to simultaneously call this method. Because of
     * that, this method must be synchronized.
     * </p>
     */
    synchronized void delete() {
        if (reservations.isDestroyed()) {
            if (!deleted) {
                try {
                    deleted = true;

                    if (history != null) {
                        history.recordAction(SignedStateAction.DESTROY, getReservationCount(), null, null);
                    }
                    registryRecord.release();
                    state.release();
                } catch (final Throwable ex) {
                    logger.error(EXCEPTION.getMarker(), "exception while attempting to delete signed state", ex);
                }
            }
        }
    }

    /**
     * Get the number of reservations.
     */
    public synchronized int getReservationCount() {
        return reservations.getReservationCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        final SignedState that = (SignedState) other;
        return Objects.equals(sigSet, that.sigSet) && Objects.equals(state, that.state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(sigSet, state);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "SS(round: %d, sigs: %d/%s, hash: %s)"
                .formatted(getRound(), signingWeight, RosterUtils.computeTotalWeight(getRoster()), state.getHash());
    }

    /**
     * Get the consensus timestamp for this signed state
     *
     * @return the consensus timestamp for this signed state.
     */
    public @NonNull Instant getConsensusTimestamp() {
        return platformStateFacade.consensusTimestampOf(state);
    }

    /**
     * The wall clock time when this SignedState object was instantiated.
     */
    public @NonNull Instant getCreationTimestamp() {
        return creationTimestamp;
    }

    /**
     * Check if this is a state that needs to be eventually written to disk.
     *
     * @return true if this state eventually needs to be written to disk
     */
    public boolean isStateToSave() {
        return stateToDiskReason != null;
    }

    /**
     * Mark this state as one that needs to be eventually written to disk.
     *
     * @param reason the reason why this state needs to be written to disk
     */
    public void markAsStateToSave(@NonNull final StateToDiskReason reason) {
        stateToDiskReason = reason;
    }

    /**
     * Get the reason why this state needs to be eventually written to disk, or null if it doesn't need to be
     *
     * @return the reason why this state needs to be written to disk, or null if this state does not need to be written
     */
    @Nullable
    public StateToDiskReason getStateToDiskReason() {
        return stateToDiskReason;
    }

    /**
     * Checks whether this state has been saved to disk.
     * <p>
     * The return value of this method applies only to states saved in the normal course of operation, NOT states that
     * have been dumped to disk out of band.
     * <p>
     * This method isn't threadsafe, and should only be called from the thread that is writing the state to disk.
     *
     * @return true if this state has been saved to disk, false otherwise
     */
    public boolean hasStateBeenSavedToDisk() {
        return hasBeenSavedToDisk;
    }

    /**
     * Indicate that this state has been saved to disk.
     * <p>
     * This method shouldn't be called when dumping state to disk out of band.
     * <p>
     * This method isn't threadsafe, and should only be called from the thread that is writing the state to disk.
     */
    public void stateSavedToDisk() {
        hasBeenSavedToDisk = true;
    }

    /**
     * Get the total signing weight collected so far.
     *
     * @return total weight of members whose signatures have been collected
     */
    public long getSigningWeight() {
        return signingWeight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isComplete() {
        return recoveryState | signedBy(SUPER_MAJORITY);
    }

    /**
     * @return true if the state has enough signatures so that it can be trusted to be valid
     */
    public boolean isVerifiable() {
        return recoveryState | signedBy(MAJORITY);
    }

    /**
     * Checks if this state is signed by a supplied threshold
     *
     * @param threshold the threshold to check
     * @return true if this state is signed by the threshold, false otherwise
     */
    private boolean signedBy(@NonNull final Threshold threshold) {
        return requireNonNull(threshold).isSatisfiedBy(signingWeight, RosterUtils.computeTotalWeight(getRoster()));
    }

    /**
     * Throw an exception if this state has not been signed by the majority. This method does not validate signatures,
     * call {@link #pruneInvalidSignatures()} to guarantee that only valid signatures are considered.
     *
     * @throws SignedStateInvalidException if this has not been signed by the majority
     */
    public void throwIfNotVerifiable() {
        if (!isVerifiable()) {
            throw new SignedStateInvalidException(
                    "Signed state lacks sufficient valid signatures. This state has " + sigSet.size()
                            + " valid signatures representing " + signingWeight + "/"
                            + RosterUtils.computeTotalWeight(getRoster()) + " weight");
        }
    }

    /**
     * Add a signature to the sigset if the signature is valid.
     *
     * @param nodeId    the ID of the signing node
     * @param signature the signature to add
     * @return true if the signed state is now complete as a result of the signature being added, false if the signed
     * state is either not complete or was previously complete prior to this signature
     */
    public boolean addSignature(@NonNull final NodeId nodeId, @NonNull final Signature signature) {
        requireNonNull(nodeId, "nodeId");
        requireNonNull(signature, "signature");

        if (isComplete()) {
            // No need to add more signatures
            return false;
        }

        final RosterEntry rosterEntry = RosterUtils.getRosterEntryOrNull(getRoster(), nodeId.id());

        if (rosterEntry == null) {
            // we ignore signatures from nodes no longer in the roster
            return false;
        }

        if (!isSignatureValid(rosterEntry, signature)) {
            return false;
        }

        if (sigSet.hasSignature(nodeId)) {
            // we already have this signature
            return false;
        }

        sigSet.addSignature(nodeId, signature);
        signingWeight += rosterEntry.weight();

        return isComplete();
    }

    /**
     * Check if a signature is valid.  If a node has no weight, we consider the signature to be invalid.
     *
     * @param address   the address of the signer, or null if there is no signing address
     * @param signature the signature to check
     * @return true if the signature is valid, false otherwise
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isSignatureValid(@Nullable final Address address, @NonNull final Signature signature) {
        if (address == null) {
            // Signing node is not in the address book.
            return false;
        }

        if (address.getWeight() == 0) {
            // Signing node has no weight.
            return false;
        }

        if (address.getSigPublicKey() == null) {
            // If the address does not have a valid public key, the signature is invalid.
            // https://github.com/hashgraph/hedera-services/issues/16648
            return false;
        }

        return signatureVerifier.verifySignature(
                state.getHash().getBytes(), signature.getBytes(), address.getSigPublicKey());
    }

    /**
     * Check if a signature is valid. If a node has no weight or is missing a certificate, we consider the signature to
     * be invalid.
     *
     * @param rosterEntry the roster entry of the signer, or null if there was no signing address
     * @param signature   the signature to check
     * @return true if the signature is valid, else false
     */
    private boolean isSignatureValid(@Nullable final RosterEntry rosterEntry, @NonNull final Signature signature) {
        if (rosterEntry == null) {
            // Signing node is not in the roster.
            return false;
        }

        if (rosterEntry.weight() == 0) {
            // Signing node has no weight.
            return false;
        }

        final X509Certificate cert = RosterUtils.fetchGossipCaCertificate(rosterEntry);

        if (cert == null) {
            // If the address does not have a valid public key, the signature is invalid.
            // https://github.com/hashgraph/hedera-services/issues/16648
            return false;
        }

        return signatureVerifier.verifySignature(state.getHash().getBytes(), signature.getBytes(), cert.getPublicKey());
    }

    /**
     * Remove all invalid signatures from a signed state. Uses the address book in the state when judging the validity
     * of signatures.
     */
    public void pruneInvalidSignatures() {
        pruneInvalidSignatures(getRoster());
    }

    /**
     * Remove all invalid signatures from a signed state.
     *
     * @param trustedRoster use this roster to determine signature validity instead of using the roster from the signed
     *                      state. (Useful if validating signed states from untrusted sources.)
     */
    public void pruneInvalidSignatures(@NonNull final Roster trustedRoster) {
        requireNonNull(trustedRoster);

        final Map<Long, RosterEntry> entriesByNodeId = RosterUtils.toMap(trustedRoster);
        final List<NodeId> signaturesToRemove = new ArrayList<>();

        for (final NodeId nodeId : sigSet) {
            final RosterEntry entry = entriesByNodeId.get(nodeId.id());
            if (!isSignatureValid(entry, sigSet.getSignature(nodeId))) {
                signaturesToRemove.add(nodeId);
            }
        }

        for (final NodeId nodeId : signaturesToRemove) {
            sigSet.removeSignature(nodeId);
        }

        // Recalculate signing weight. We should do this even if we don't remove signatures.
        signingWeight = 0;

        for (final NodeId nodeId : sigSet) {
            final RosterEntry entry = entriesByNodeId.get(nodeId.id());
            if (entry != null) {
                signingWeight += entry.weight();
            }
        }
    }

    /**
     * Get the reservation history for this object (if configured to gather history)
     *
     * @return the reservation history
     */
    @Nullable
    public SignedStateHistory getHistory() {
        return history;
    }
}
