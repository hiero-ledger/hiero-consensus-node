// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.ANCHOR_AHEAD;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.NO_PROGRESS;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.PENDING_WITNESS;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.SIZE_LIMIT;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.UNKNOWN_ANCHOR;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.UNWITNESSED_GAP;
import static com.hedera.node.app.workflows.clpr.ClprHieroProofCodec.MAX_LINKS;

import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason;
import com.hedera.node.app.workflows.clpr.ClprHieroProofCodec.CompactStateProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Selects the next compact proof from the receiver's anchor and the source's adopted signing history. */
final class ClprHieroProvenancePlanner {
    private static final int ANCHOR_HASH_LENGTH = 32;

    /**
     * The links to carry in a state proof, or a batch of links and the witness for its last key.
     * A state proof under the receiver's current key has no links.
     */
    record Plan(
            @NonNull List<HintsKeyProvenance> links,
            @Nullable HintsKeyWitness witness) {}

    record Decision(@Nullable Plan plan, @Nullable SkipReason reason) {}

    private record Link(
            HintsKeyProvenance certificate, @Nullable HintsKeyWitness witness) {}

    private ClprHieroProvenancePlanner() {}

    /**
     * Plans one bundle towards the signer of the selected snapshot. A witness-only retry can advance partially
     * toward that signer when no state proof can be delivered.
     */
    static @NonNull Decision evaluate(
            @NonNull final Bytes anchorHash,
            @NonNull final List<HintsKeyWitnessRecord> records,
            @NonNull final CompactStateProof proof,
            final long maxPayloadBytes,
            final boolean witnessOnly) {
        if (anchorHash.length() != ANCHOR_HASH_LENGTH) {
            return skip(UNKNOWN_ANCHOR);
        }
        if (anchorHash.equals(keccak256DigestOf(proof.verificationKey()))) {
            if (witnessOnly) {
                return skip(NO_PROGRESS);
            }
            return ClprHieroProofCodec.stateProofEnvelopeSize(proof, 0) <= maxPayloadBytes
                    ? new Decision(new Plan(List.of(), null), null)
                    : skip(SIZE_LIMIT);
        }

        if (records.isEmpty()) {
            return skip(UNKNOWN_ANCHOR);
        }
        final var signer = proof.verificationKey();
        int target = -1;
        Bytes previous = null;
        for (int i = 0; i < records.size(); i++) {
            final var certificate = records.get(i).provenance();
            if (certificate == null
                    || certificate.sourceVerificationKey().length() != VK_LENGTH
                    || certificate.targetVerificationKey().length() != VK_LENGTH
                    || certificate.signature().length() != SIGNATURE_LENGTH
                    || (previous != null && !previous.equals(certificate.sourceVerificationKey()))) {
                return skip(UNWITNESSED_GAP);
            }
            if (i == 0 && certificate.sourceVerificationKey().equals(signer)) {
                target = 0;
            }
            previous = certificate.targetVerificationKey();
            if (previous.equals(signer)) {
                target = i + 1;
            }
        }
        final int anchor = anchorPosition(anchorHash, records);
        if (anchor < 0) {
            return skip(UNKNOWN_ANCHOR);
        }
        if (target < 0) {
            return skip(UNWITNESSED_GAP);
        }
        if (target <= anchor) {
            return skip(ANCHOR_AHEAD);
        }
        final var path = latestPath(records.subList(0, target), anchor, signer);
        if (path.isEmpty()) {
            return skip(UNWITNESSED_GAP);
        }
        final var stateProof = witnessOnly ? null : proof;
        final var plan = nextBatch(path, stateProof, maxPayloadBytes);
        if (plan != null) {
            return new Decision(plan, null);
        }
        if (maxPayloadBytes != Long.MAX_VALUE && nextBatch(path, stateProof, Long.MAX_VALUE) != null) {
            return skip(SIZE_LIMIT);
        }
        if (witnessOnly
                && path.size() <= MAX_LINKS
                && target == records.size()
                && records.getLast().witness() == null
                && records.getLast().provenanceOrThrow().equals(path.getLast().certificate())) {
            return skip(PENDING_WITNESS);
        }
        return skip(UNWITNESSED_GAP);
    }

    private static Decision skip(final SkipReason reason) {
        return new Decision(null, reason);
    }

    private static int anchorPosition(final Bytes anchorHash, final List<HintsKeyWitnessRecord> records) {
        for (int position = records.size(); position > 0; position--) {
            final var key = records.get(position - 1).provenanceOrThrow().targetVerificationKey();
            if (anchorHash.equals(keccak256DigestOf(key))) {
                return position;
            }
        }
        final var firstKey = records.getFirst().provenanceOrThrow().sourceVerificationKey();
        return anchorHash.equals(keccak256DigestOf(firstKey)) ? 0 : -1;
    }

    private static List<Link> latestPath(
            final List<HintsKeyWitnessRecord> prefix, final int anchor, final Bytes signer) {
        final Map<Bytes, Link> outgoing = new HashMap<>();
        for (int i = anchor; i < prefix.size(); i++) {
            final var record = prefix.get(i);
            final var certificate = record.provenanceOrThrow();
            final var source = certificate.sourceVerificationKey();
            if (!source.equals(certificate.targetVerificationKey())) {
                // The witness attests to this adoption. Another adoption may reuse the key at a different timestamp.
                outgoing.put(source, new Link(certificate, compatibleWitness(record, certificate)));
            }
        }
        final var path = new ArrayList<Link>();
        var key = prefix.get(anchor).provenanceOrThrow().sourceVerificationKey();
        while (!key.equals(signer)) {
            // Latest outgoing links skip any earlier cycle back to this key, so the walk only moves forward.
            final var link = outgoing.get(key);
            if (link == null) {
                return List.of();
            }
            key = link.certificate().targetVerificationKey();
            path.add(link);
        }
        return path;
    }

    private static @Nullable HintsKeyWitness compatibleWitness(
            final HintsKeyWitnessRecord record, final HintsKeyProvenance certificate) {
        final var witness = record.witness();
        return witness != null
                        && witness.targetHintsConstructionId() == certificate.targetHintsConstructionId()
                        && ClprHieroProofCodec.supportsWitness(witness)
                ? witness
                : null;
    }

    private static @Nullable Plan nextBatch(
            final List<Link> path, @Nullable final CompactStateProof proof, final long maxPayloadBytes) {
        final int totalLinks = path.size();
        if (proof != null
                && totalLinks <= MAX_LINKS
                && ClprHieroProofCodec.stateProofEnvelopeSize(proof, totalLinks) <= maxPayloadBytes) {
            return planWith(path, null);
        }
        for (int linkCount = Math.min(totalLinks, MAX_LINKS); linkCount > 0; linkCount--) {
            final var witness = path.get(linkCount - 1).witness();
            if (witness != null && ClprHieroProofCodec.witnessEnvelopeSize(linkCount, witness) <= maxPayloadBytes) {
                return planWith(path.subList(0, linkCount), witness);
            }
        }
        return null;
    }

    private static Plan planWith(final List<Link> path, @Nullable final HintsKeyWitness witness) {
        return new Plan(path.stream().map(Link::certificate).toList(), witness);
    }
}
