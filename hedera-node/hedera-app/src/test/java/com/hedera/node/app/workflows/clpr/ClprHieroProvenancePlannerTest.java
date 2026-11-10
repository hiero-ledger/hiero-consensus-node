// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_256;
import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_384;
import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.ANCHOR_AHEAD;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.NO_PROGRESS;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.PENDING_WITNESS;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.SIZE_LIMIT;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.UNKNOWN_ANCHOR;
import static com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport.SkipReason.UNWITNESSED_GAP;
import static com.hedera.node.app.workflows.clpr.ClprHieroProvenancePlanner.evaluate;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.hapi.node.state.history.HintsKeyWitnessRecord;
import com.hedera.node.app.workflows.clpr.ClprHieroProofCodec.CompactStateProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClprHieroProvenancePlannerTest {
    @Test
    void sendsStateProofSignedByPinnedKeyWithoutConsultingHistory() {
        final var proof = proof(0);
        final var cap = ClprHieroProofCodec.stateProofEnvelopeSize(proof, 0);
        final var result = evaluate(anchor(0), List.of(), proof, cap, false);
        assertThat(result.plan()).isEqualTo(new ClprHieroProvenancePlanner.Plan(List.of(), null));
        assertThat(evaluate(anchor(0), List.of(), proof, cap - 1, false).reason())
                .isEqualTo(SIZE_LIMIT);
    }

    @Test
    void stateProofCarriesOnlyTheLinksBetweenThePinnedKeyAndItsSigner() {
        final var history = history(9, Set.of());
        final var result = evaluate(anchor(1), history, proof(9), Long.MAX_VALUE, false);
        assertThat(result.plan())
                .isEqualTo(new ClprHieroProvenancePlanner.Plan(
                        history.subList(1, 9).stream()
                                .map(HintsKeyWitnessRecord::provenanceOrThrow)
                                .toList(),
                        null));
    }

    @Test
    void catchesUpAcrossMultipleBatchesWithoutWitnessesOnIntermediateKeys() {
        final var history = history(21, Set.of(5, 8, 15, 16));
        final var proof = proof(21);

        final var first = requireNonNull(
                evaluate(anchor(0), history, proof, Long.MAX_VALUE, false).plan());
        assertThat(first.links()).hasSize(8);
        assertThat(first.witness()).isEqualTo(history.get(7).witness());

        final var second = requireNonNull(
                evaluate(anchor(8), history, proof, Long.MAX_VALUE, false).plan());
        assertThat(second.links()).hasSize(8);
        assertThat(second.witness()).isEqualTo(history.get(15).witness());

        final var message = requireNonNull(
                evaluate(anchor(16), history, proof, Long.MAX_VALUE, false).plan());
        assertThat(message.links()).hasSize(5);
        assertThat(message.witness()).isNull();
    }

    @Test
    void advancesToAnAvailableWitnessBeforeReportingALaterGap() {
        final var history = history(18, Set.of(8));
        final var proof = proof(18);
        final var first = requireNonNull(
                evaluate(anchor(0), history, proof, Long.MAX_VALUE, false).plan());
        assertThat(first.links()).hasSize(8);
        assertThat(first.witness()).isEqualTo(history.get(7).witness());
        final var second = evaluate(anchor(8), history, proof, Long.MAX_VALUE, false);
        assertThat(second.plan()).isNull();
        assertThat(second.reason()).isEqualTo(UNWITNESSED_GAP);
    }

    @Test
    void advancesTowardSignerWithNoMessageProof() {
        final var history = history(14, Set.of(6, 12));
        history.set(13, record(13, 14, 14, SHA2_384));

        final var first = requireNonNull(
                evaluate(anchor(0), history, proof(14), Long.MAX_VALUE, true).plan());
        assertThat(first.witness()).isEqualTo(history.get(5).witness());
        final var second = requireNonNull(
                evaluate(anchor(6), history, proof(14), Long.MAX_VALUE, true).plan());
        assertThat(second.witness()).isEqualTo(history.get(11).witness());
        assertThat(evaluate(anchor(12), history, proof(14), Long.MAX_VALUE, true)
                        .reason())
                .isEqualTo(UNWITNESSED_GAP);
        history.set(13, record(13, 14, 14, null));
        assertThat(evaluate(anchor(12), history, proof(14), Long.MAX_VALUE, true)
                        .reason())
                .isEqualTo(PENDING_WITNESS);
    }

    @Test
    void doesNotAdvancePastTheSelectedSnapshotsSigningKey() {
        final var history = history(5, Set.of(3, 5));
        final var signer = proof(4);
        final var decision = evaluate(anchor(0), history, signer, Long.MAX_VALUE, true);
        assertThat(requireNonNull(decision.plan()).witness())
                .isEqualTo(history.get(2).witness());
        assertThat(evaluate(anchor(3), history, signer, Long.MAX_VALUE, true).reason())
                .isEqualTo(UNWITNESSED_GAP);
        assertThat(evaluate(anchor(4), history, signer, Long.MAX_VALUE, true).reason())
                .isEqualTo(NO_PROGRESS);
        assertThat(evaluate(anchor(5), history, signer, Long.MAX_VALUE, true).reason())
                .isEqualTo(ANCHOR_AHEAD);
    }

    @Test
    void sendsWitnessOnlyIfTheStateProofWithItsLinksWouldExceedTheBudget() {
        final var history = history(1, Set.of(1));
        final var proof = proof(1);
        final var cap = ClprHieroProofCodec.stateProofEnvelopeSize(proof, 1) - 1;
        final var result =
                requireNonNull(evaluate(anchor(0), history, proof, cap, false).plan());
        assertThat(result.links()).containsExactly(history.getFirst().provenanceOrThrow());
        assertThat(result.witness()).isEqualTo(history.getFirst().witness());
        assertThat(evaluate(anchor(1), history, proof, cap, false).plan())
                .isEqualTo(new ClprHieroProvenancePlanner.Plan(List.of(), null));
    }

    @Test
    void respectsTheExactWitnessSizeLimit() {
        final var history = history(8, Set.of(4, 8));
        final var cap =
                ClprHieroProofCodec.witnessEnvelopeSize(4, history.get(3).witnessOrThrow());
        final var result =
                requireNonNull(evaluate(anchor(0), history, proof(8), cap, true).plan());
        assertThat(result.links()).hasSize(4);
        assertThat(result.witness()).isEqualTo(history.get(3).witness());
        assertThat(evaluate(anchor(0), history, proof(8), cap - 1, true).reason())
                .isEqualTo(SIZE_LIMIT);
    }

    @Test
    void refusesUnknownAnchorOlderSignerAndDiscontinuousHistory() {
        final var history = history(3, Set.of());
        assertThat(evaluate(anchor(9), history, proof(3), Long.MAX_VALUE, false).reason())
                .isEqualTo(UNKNOWN_ANCHOR);
        assertThat(evaluate(anchor(2), history, proof(1), Long.MAX_VALUE, false).reason())
                .isEqualTo(ANCHOR_AHEAD);
        history.set(1, record(7, 2, 2, null));
        assertThat(evaluate(anchor(0), history, proof(3), Long.MAX_VALUE, false).reason())
                .isEqualTo(UNWITNESSED_GAP);
    }

    @Test
    void followsTheLatestOutgoingCertificateWithoutRepeatingKeys() {
        final var history =
                List.of(record(0, 1, 1, null), record(1, 2, 2, null), record(2, 1, 3, null), record(1, 3, 4, null));
        final var proof = proof(3);
        final var message = requireNonNull(
                evaluate(anchor(0), history, proof, Long.MAX_VALUE, false).plan());
        assertThat(message.links())
                .containsExactly(
                        history.getFirst().provenanceOrThrow(),
                        history.getLast().provenanceOrThrow());
        assertThat(message.witness()).isNull();
    }

    @Test
    void considersOnlyOutgoingCertificatesThroughTheSnapshotSigner() {
        final var history =
                List.of(record(0, 1, 1, null), record(1, 2, 2, null), record(2, 1, 3, null), record(1, 3, 4, null));
        final var proof = proof(2);
        final var message = requireNonNull(
                evaluate(anchor(0), history, proof, Long.MAX_VALUE, false).plan());
        assertThat(message.links())
                .containsExactly(
                        history.getFirst().provenanceOrThrow(), history.get(1).provenanceOrThrow());
        assertThat(evaluate(anchor(1), history, proof, Long.MAX_VALUE, false).reason())
                .isEqualTo(ANCHOR_AHEAD);
    }

    @Test
    void waitsForTheWitnessOfTheSelectedAdoptionWhenAKeyRecurs() {
        final var history = new ArrayList<>(List.of(
                record(0, 1, 1, SHA2_256), record(1, 2, 2, null), record(2, 3, 3, SHA2_256), record(3, 1, 4, null)));
        final var proof = proof(1);
        final var pending = evaluate(anchor(3), history, proof, Long.MAX_VALUE, true);
        assertThat(pending.plan()).isNull();
        assertThat(pending.reason()).isEqualTo(PENDING_WITNESS);

        history.set(3, record(3, 1, 4, SHA2_384));
        final var incompatible = evaluate(anchor(3), history, proof, Long.MAX_VALUE, true);
        assertThat(incompatible.plan()).isNull();
        assertThat(incompatible.reason()).isEqualTo(UNWITNESSED_GAP);

        history.set(3, record(3, 1, 4, SHA2_256));
        final var result = requireNonNull(
                evaluate(anchor(3), history, proof, Long.MAX_VALUE, true).plan());
        assertThat(result.links()).containsExactly(history.getLast().provenanceOrThrow());
        assertThat(result.witness()).isEqualTo(history.getLast().witness());
    }

    @Test
    void skipsSameKeyAdoptionAndItsWitness() {
        final var history = List.of(record(0, 1, 1, null), record(1, 1, 2, SHA2_256));
        final var witnessOnly = evaluate(anchor(0), history, proof(1), Long.MAX_VALUE, true);
        assertThat(witnessOnly.plan()).isNull();
        assertThat(witnessOnly.reason()).isEqualTo(UNWITNESSED_GAP);

        final var result = requireNonNull(
                evaluate(anchor(0), history, proof(1), Long.MAX_VALUE, false).plan());
        assertThat(result.links()).containsExactly(history.getFirst().provenanceOrThrow());
        assertThat(result.witness()).isNull();
    }

    private static CompactStateProof proof(final int key) {
        return new CompactStateProof(key(key), Bytes.wrap(new byte[2048]));
    }

    private static Bytes anchor(final int key) {
        return keccak256DigestOf(key(key));
    }

    private static Bytes key(final int index) {
        final byte[] key = new byte[VK_LENGTH];
        key[0] = (byte) index;
        return Bytes.wrap(key);
    }

    private static List<HintsKeyWitnessRecord> history(final int count, final Set<Integer> witnessed) {
        final var history = new ArrayList<HintsKeyWitnessRecord>();
        for (int next = 1; next <= count; next++) {
            history.add(record(next - 1, next, next, witnessed.contains(next) ? SHA2_256 : null));
        }
        return history;
    }

    private static HintsKeyWitnessRecord record(
            final int from, final int to, final int construction, @Nullable final BlockHashAlgorithm digest) {
        final var record = HintsKeyWitnessRecord.newBuilder()
                .provenance(HintsKeyProvenance.newBuilder()
                        .sourceHintsConstructionId(construction)
                        .targetHintsConstructionId(construction + 1)
                        .sourceVerificationKey(key(from))
                        .targetVerificationKey(key(to))
                        .signature(Bytes.wrap(new byte[SIGNATURE_LENGTH]))
                        .build());
        if (digest != null) {
            record.witness(HintsKeyWitness.newBuilder()
                    .targetHintsConstructionId(construction + 1)
                    .timestamp(new Timestamp(1_730_000_000 + construction, 1))
                    .siblingHash(Bytes.wrap(new byte[digest == SHA2_256 ? 32 : 48]))
                    .signature(Bytes.wrap(new byte[SIGNATURE_LENGTH]))
                    .hashAlgorithm(digest)
                    .build());
        }
        return record.build();
    }
}
