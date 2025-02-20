// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.history.HistoryProofVote;
import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.ReadableHistoryStore.HistorySignaturePublication;
import com.hedera.node.app.history.ReadableHistoryStore.ProofKeyPublication;
import com.hedera.node.app.history.WritableHistoryStore;
import com.hedera.node.app.roster.RosterTransitionWeights;
import com.hedera.node.app.tss.TssKeyPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Default implementation of {@link ProofController}.
 */
public class ProofControllerImpl implements ProofController {
    private static final Comparator<ProofKey> PROOF_KEY_COMPARATOR = Comparator.comparingLong(ProofKey::nodeId);

    private final long selfId;

    @Nullable
    private final Bytes ledgerId;

    private final Executor executor;
    private final TssKeyPair schnorrKeyPair;
    private final HistoryLibrary library;
    private final HistoryLibraryCodec codec;
    private final HistorySubmissions submissions;
    private final RosterTransitionWeights weights;
    private final Consumer<HistoryProof> proofConsumer;
    private final Map<Long, HistoryProofVote> votes = new HashMap<>();
    private final Map<Long, Bytes> targetProofKeys = new HashMap<>();
    private final Set<Long> signingNodeIds = new HashSet<>();
    private final NavigableMap<Instant, CompletableFuture<Verification>> verificationFutures = new TreeMap<>();

    /**
     * Once set, the metadata to be proven as associated to the target address book hash.
     */
    @Nullable
    private Bytes targetMetadata;

    /**
     * The ongoing construction, updated in network state each time the controller makes progress.
     */
    private HistoryProofConstruction construction;

    /**
     * A party's verified signature on a new piece of {@code (address book hash, metadata)} history.
     *
     * @param nodeId the node's id
     * @param historySignature its history signature
     * @param isValid whether the signature is valid
     */
    private record Verification(long nodeId, @NonNull HistorySignature historySignature, boolean isValid) {}

    public ProofControllerImpl(
            final long selfId,
            @NonNull final TssKeyPair schnorrKeyPair,
            @Nullable final Bytes ledgerId,
            @NonNull final HistoryProofConstruction construction,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Executor executor,
            @NonNull final HistoryLibrary library,
            @NonNull final HistoryLibraryCodec codec,
            @NonNull final HistorySubmissions submissions,
            @NonNull final List<ProofKeyPublication> keyPublications,
            @NonNull final List<HistorySignaturePublication> signaturePublications,
            @NonNull final Consumer<HistoryProof> proofConsumer) {
        this.selfId = selfId;
        this.ledgerId = ledgerId;
        this.codec = requireNonNull(codec);
        this.executor = requireNonNull(executor);
        this.library = requireNonNull(library);
        this.submissions = requireNonNull(submissions);
        this.weights = requireNonNull(weights);
        this.construction = requireNonNull(construction);
        this.proofConsumer = requireNonNull(proofConsumer);
        this.schnorrKeyPair = requireNonNull(schnorrKeyPair);
        requireNonNull(keyPublications);
        requireNonNull(signaturePublications);
    }

    @Override
    public long constructionId() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean isStillInProgress() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void advanceConstruction(
            @NonNull final Instant now,
            @Nullable final Bytes metadata,
            @NonNull final WritableHistoryStore historyStore) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void addProofKeyPublication(@NonNull final ProofKeyPublication publication) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean addSignaturePublication(@NonNull final HistorySignaturePublication publication) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void addProofVote(
            final long nodeId, @NonNull final HistoryProofVote vote, @NonNull final WritableHistoryStore historyStore) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void cancelPendingWork() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
