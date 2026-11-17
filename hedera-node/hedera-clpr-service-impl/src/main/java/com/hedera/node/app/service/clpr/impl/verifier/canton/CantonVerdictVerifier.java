// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonRlp.Slice;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Checks a Canton mediator verdict: a fresh approval of a transaction view on the expected synchronizer, signed by
 * a threshold of distinct members of the mediator set. Currently does not implement bundle rotations or LSU handling.
 *
 * <p>Signatures are RLP {@code [[keyFingerprint(32), signature, delegation], …]} with every {@code delegation}
 * empty. Entries with an unknown fingerprint or a member already counted are skipped; a known key whose signature
 * fails is a hard reject. Scanning stops once the threshold is reached.
 *
 * <p>RLP is decoded by {@link CantonRlp}, a strict reader that mirrors the reference's OpenZeppelin {@code RLP}
 * library, because the general-purpose {@code Rlp} decoder in this module is more lenient and consensus
 * verification must accept exactly what the reference accepts.
 */
public final class CantonVerdictVerifier {

    /** The verdict's request id and the root hash of the transaction it approves. */
    public record VerdictResult(long requestId, @NonNull byte[] rootHash) {}

    private CantonVerdictVerifier() {}

    /**
     * Verifies {@code typedContent} approves a transaction view on {@code synchronizerId} with a request id above
     * {@code lastRequestId}, signed by the threshold of distinct members of {@code mediatorSet}.
     *
     * @throws CantonProofException on any verification failure
     */
    @NonNull
    public static VerdictResult verifyVerdict(
            @NonNull final byte[] typedContent,
            @NonNull final byte[] signatures,
            @NonNull final byte[] mediatorSet,
            @NonNull final byte[] synchronizerId,
            final long lastRequestId) {
        Objects.requireNonNull(typedContent, "typedContent");
        Objects.requireNonNull(signatures, "signatures");
        Objects.requireNonNull(mediatorSet, "mediatorSet");
        Objects.requireNonNull(synchronizerId, "synchronizerId");
        final CantonProto.Verdict v = CantonProto.parseVerdict(typedContent);
        check(typedContent, v, synchronizerId, lastRequestId, signatures, mediatorSet);
        return new VerdictResult(v.requestId(), v.rootHash());
    }

    private static void check(
            final byte[] typed,
            final CantonProto.Verdict v,
            final byte[] synchronizerId,
            final long lastRequestId,
            final byte[] signatures,
            final byte[] set) {
        if (v.viewType() != CantonProto.VIEW_TYPE_TRANSACTION) {
            throw CantonProofException.wrongViewType();
        }
        if (!Arrays.equals(v.synchronizerId(), synchronizerId)) {
            throw CantonProofException.synchronizerMismatch();
        }
        if (!v.approved()) {
            throw CantonProofException.notApproved();
        }
        // requestId is uint64 in the reference: compare unsigned.
        if (Long.compareUnsigned(v.requestId(), lastRequestId) <= 0) {
            throw CantonProofException.staleVerdict();
        }

        final CantonMediators.SetSummary summary = CantonMediators.validate(set);
        final int threshold = summary.threshold();
        final boolean[] counted = new boolean[summary.members()];
        final byte[] message = CantonMerkle.verdictSigningInput(typed);
        // Like RLP.decodeList, every top-level element header is validated up front, even past the threshold.
        final List<Slice> entries = CantonRlp.readList(CantonRlp.of(signatures));
        int count = 0;
        for (int i = 0; i < entries.size() && count < threshold; ++i) {
            final List<Slice> sig = CantonRlp.readList(entries.get(i));
            if (sig.size() != 3) {
                throw CantonProofException.invalidPayloadShape("signature entry has " + sig.size() + " items");
            }
            if (!CantonRlp.isEmpty(sig.get(2))) {
                throw CantonProofException.sessionKeysNotSupported();
            }
            final CantonMediators.SignerKey key = CantonMediators.lookup(set, CantonRlp.readBytes32(sig.get(0)));
            if (!key.found() || counted[key.member()]) {
                continue;
            }
            if (!CantonMediators.verify(key, message, CantonRlp.readBytes(sig.get(1)))) {
                throw CantonProofException.invalidMediatorSignature();
            }
            counted[key.member()] = true;
            ++count;
        }
        if (count < threshold) {
            throw CantonProofException.mediatorQuorumNotMet();
        }
    }
}
