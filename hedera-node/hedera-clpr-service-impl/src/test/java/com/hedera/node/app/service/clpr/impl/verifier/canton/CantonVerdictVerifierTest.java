// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hiero.base.crypto.Ed25519;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CantonVerdictVerifier}.
 *
 * <p>REAL = bytes straight from the Canton fixtures (typed content, mediator set, mediator signature). The real
 * mediator set is threshold 1 of 1 member (Ed25519). CONSTRUCTED = assembled here from real fixture bytes (mutated
 * typed content / signature) or from fresh libsodium Ed25519 keys signing the REAL verdict signing input, used for
 * the multi-member quorum cases that no real fixture covers.
 */
class CantonVerdictVerifierTest {

    private static final byte[] EMPTY_DELEGATION = {(byte) 0x80};

    private static final byte[] TYPED = CantonFixtures.verdictTypedContent();
    /**
     * 1-of-1 Ed25519 set built around the REAL verdict mediator key. The REAL topology-mediator-set fixture comes
     * from a different LocalNet run (its key differs from verdict-mediator-key), so the happy path uses this set:
     * REAL key + REAL signature + REAL typed content, with a CONSTRUCTED (sha256-of-key) fingerprint and set framing.
     */
    private static final byte[] REAL_KEY = CantonFixtures.verdictMediatorKey();

    private static final byte[] REAL_FP = sha256(REAL_KEY);
    private static final byte[] REAL_SET = setOf(1, new Mediator(null, REAL_KEY, REAL_FP));
    private static final CantonProto.Verdict VERDICT = CantonProto.parseVerdict(TYPED);
    private static final byte[] SYNC = VERDICT.synchronizerId();

    // ---------------------------------------------------------------- helpers

    private static byte[] realFingerprint() {
        return REAL_FP;
    }

    private static byte[] entry(final byte[] fp, final byte[] sig, final byte[] delegationRlp) {
        return Rlp.encodeList(List.of(Rlp.encodeBytes(fp), Rlp.encodeBytes(sig), delegationRlp));
    }

    private static byte[] sigs(final byte[]... entries) {
        return Rlp.encodeList(List.of(entries));
    }

    private static byte[] realEntry() {
        return entry(realFingerprint(), CantonFixtures.verdictMediatorSig(), EMPTY_DELEGATION);
    }

    private static CantonProofException expectError(final org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        final var t = org.assertj.core.api.Assertions.catchThrowable(c);
        assertThat(t).isInstanceOf(CantonProofException.class);
        return (CantonProofException) t;
    }

    private static void assertError(final String name, final org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        assertThat(expectError(c).errorName()).isEqualTo(name);
    }

    /** A constructed Ed25519 mediator: key pair plus its set fingerprint. */
    private record Mediator(byte[] secret, byte[] pub, byte[] fp) {
        static Mediator of(final int seed) {
            final byte[] s = new byte[Ed25519.SEED_BYTES];
            Arrays.fill(s, (byte) seed);
            final byte[] pub = new byte[Ed25519.PUBLIC_KEY_BYTES];
            final byte[] secret = new byte[Ed25519.SECRET_KEY_BYTES];
            Ed25519.newKeypair(pub, secret, s);
            return new Mediator(secret, pub, sha256(pub));
        }

        byte[] sign(final byte[] message) {
            final byte[] sig = new byte[Ed25519.SIGNATURE_BYTES];
            Ed25519.sign(sig, message, secret);
            return sig;
        }
    }

    private static byte[] sha256(final byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Packed set layout of CantonMediators: Ed25519 members with one current key each, no delegations. */
    private static byte[] setOf(final int threshold, final Mediator... members) {
        final var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0, 0, 0, 1});
        out.writeBytes(be32(threshold));
        out.writeBytes(be32(members.length));
        for (int i = 0; i < members.length; i++) {
            final byte[] id = new byte[32];
            id[0] = (byte) (i + 1);
            out.writeBytes(id);
            out.writeBytes(be32(1));
            out.write(0); // delegations
            out.write(1); // current keys
            out.writeBytes(members[i].fp());
            out.write(1); // Ed25519
            out.write(0);
            out.writeBytes(members[i].pub());
            out.writeBytes(new byte[32]); // y
            out.write(0); // previous keys
        }
        return out.toByteArray();
    }

    private static byte[] be32(final int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] message() {
        return CantonMerkle.verdictSigningInput(TYPED);
    }

    private static byte[] verify(final byte[] signatures, final byte[] set) {
        return CantonVerdictVerifier.verifyVerdict(TYPED, signatures, set, SYNC, 0)
                .rootHash();
    }

    // ------------------------------------------------------------ happy path

    @Test
    void realVerdictPassesAndReturnsRequestIdAndRootHash() {
        // REAL: typed content, mediator key, mediator signature (set framing CONSTRUCTED around the real key).
        final var result = CantonVerdictVerifier.verifyVerdict(TYPED, sigs(realEntry()), REAL_SET, SYNC, 0);
        assertThat(result.requestId()).isEqualTo(VERDICT.requestId());
        assertThat(result.rootHash()).isEqualTo(VERDICT.rootHash());
    }

    @Test
    void emptyListDelegationEncodingAlsoCountsAsEmpty() {
        // REAL signature, delegation encoded as the empty list 0xc0 (isEmpty accepts both 0x80 and 0xc0).
        final byte[] e = entry(realFingerprint(), CantonFixtures.verdictMediatorSig(), new byte[] {(byte) 0xc0});
        assertThat(verify(sigs(e), REAL_SET)).isEqualTo(VERDICT.rootHash());
    }

    // ------------------------------------------------------- verdict rejects

    @Test
    void wrongViewTypeRejected() {
        // CONSTRUCTED: real typed content with view_type varint 1 -> 2 (length preserving).
        final byte[] typed = mutate(TYPED, new byte[] {0x10, 0x01}, new byte[] {0x10, 0x02});
        assertThat(CantonProto.parseVerdict(typed).viewType()).isEqualTo(2);
        assertError(
                "WrongViewType",
                () -> CantonVerdictVerifier.verifyVerdict(typed, sigs(realEntry()), REAL_SET, SYNC, 0));
    }

    @Test
    void synchronizerMismatchRejected() {
        // REAL typed content, CONSTRUCTED expected id (one byte flipped).
        final byte[] other = SYNC.clone();
        other[other.length - 1] ^= 1;
        assertError(
                "SynchronizerMismatch",
                () -> CantonVerdictVerifier.verifyVerdict(TYPED, sigs(realEntry()), REAL_SET, other, 0));
    }

    @Test
    void notApprovedRejected() {
        // CONSTRUCTED: real typed content with the Verdict oneof field 1 (approve) -> field 2 (reject).
        final byte[] typed = mutate(TYPED, new byte[] {0x2a, 0x02, 0x0a, 0x00}, new byte[] {0x2a, 0x02, 0x12, 0x00});
        assertThat(CantonProto.parseVerdict(typed).approved()).isFalse();
        assertError(
                "NotApproved", () -> CantonVerdictVerifier.verifyVerdict(typed, sigs(realEntry()), REAL_SET, SYNC, 0));
    }

    @Test
    void staleVerdictRejectedWhenRequestIdNotAboveLast() {
        // REAL typed content; lastRequestId == requestId and > requestId are stale, requestId - 1 passes.
        assertError(
                "StaleVerdict",
                () -> CantonVerdictVerifier.verifyVerdict(
                        TYPED, sigs(realEntry()), REAL_SET, SYNC, VERDICT.requestId()));
        assertError(
                "StaleVerdict",
                () -> CantonVerdictVerifier.verifyVerdict(
                        TYPED, sigs(realEntry()), REAL_SET, SYNC, VERDICT.requestId() + 1));
        assertThat(CantonVerdictVerifier.verifyVerdict(
                                TYPED, sigs(realEntry()), REAL_SET, SYNC, VERDICT.requestId() - 1)
                        .requestId())
                .isEqualTo(VERDICT.requestId());
    }

    // ----------------------------------------------------- delegation / shape

    @Test
    void nonEmptyDelegationRejected() {
        // CONSTRUCTED: real fingerprint + real signature, non-empty delegation.
        final byte[] e =
                entry(realFingerprint(), CantonFixtures.verdictMediatorSig(), Rlp.encodeBytes(new byte[] {1, 2, 3}));
        assertError("SessionKeysNotSupported", () -> verify(sigs(e), REAL_SET));
    }

    @Test
    void nonEmptyDelegationRejectedEvenForUnknownFingerprint() {
        // CONSTRUCTED: the delegation check precedes the lookup, so an unknown-fingerprint entry is not skipped.
        final byte[] e = entry(new byte[32], new byte[64], Rlp.encodeBytes(new byte[] {9}));
        assertError("SessionKeysNotSupported", () -> verify(sigs(e, realEntry()), REAL_SET));
    }

    @Test
    void entryWithWrongItemCountRejected() {
        // CONSTRUCTED: 2-item entry.
        final byte[] e = Rlp.encodeList(List.of(Rlp.encodeBytes(realFingerprint()), Rlp.encodeBytes(new byte[64])));
        assertError("InvalidPayloadShape", () -> verify(sigs(e), REAL_SET));
    }

    @Test
    void entryThatIsNotAListRejected() {
        assertError("InvalidPayloadShape", () -> verify(sigs(Rlp.encodeBytes(new byte[40])), REAL_SET));
    }

    @Test
    void trailingBytesAfterSignatureListRejected() {
        final byte[] good = sigs(realEntry());
        final byte[] bad = Arrays.copyOf(good, good.length + 1);
        assertError("InvalidPayloadShape", () -> verify(bad, REAL_SET));
    }

    @Test
    void nonCanonicalSingleByteStringRejected() {
        // CONSTRUCTED: fingerprint item 0x81 0x05 (single byte < 0x80 must not be wrapped; OZ RLP rejects it).
        final byte[] e = Rlp.encodeList(
                List.of(new byte[] {(byte) 0x81, 0x05}, Rlp.encodeBytes(new byte[64]), EMPTY_DELEGATION));
        assertError("InvalidPayloadShape", () -> verify(sigs(e), REAL_SET));
    }

    @Test
    void fingerprintLongerThan32BytesRejected() {
        final byte[] e =
                Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[33]), Rlp.encodeBytes(new byte[64]), EMPTY_DELEGATION));
        assertError("InvalidPayloadShape", () -> verify(sigs(e), REAL_SET));
    }

    @Test
    void shortFingerprintIsLeftPaddedLikeReadBytes32() {
        // CONSTRUCTED: member whose fingerprint is 31 zero bytes + 0x07; the entry carries the 1-byte form 0x07.
        final var m = Mediator.of(11);
        final byte[] fp = new byte[32];
        fp[31] = 7;
        final byte[] set = setOf(1, new Mediator(m.secret(), m.pub(), fp));
        final byte[] e = Rlp.encodeList(List.of(new byte[] {7}, Rlp.encodeBytes(m.sign(message())), EMPTY_DELEGATION));
        assertThat(verify(sigs(e), set)).isEqualTo(VERDICT.rootHash());
    }

    // ---------------------------------------------------------------- quorum

    @Test
    void emptySignatureListFailsQuorum() {
        assertError("MediatorQuorumNotMet", () -> verify(new byte[] {(byte) 0xc0}, REAL_SET));
    }

    @Test
    void unknownFingerprintOnlyFailsQuorum() {
        // CONSTRUCTED: unknown fingerprint is skipped, leaving 0 < threshold 1.
        final byte[] e = entry(new byte[32], CantonFixtures.verdictMediatorSig(), EMPTY_DELEGATION);
        assertError("MediatorQuorumNotMet", () -> verify(sigs(e), REAL_SET));
    }

    @Test
    void belowThresholdFailsQuorum() {
        // CONSTRUCTED: 2-of-3 set over fresh keys, only one distinct valid signer.
        final var m = new Mediator[] {Mediator.of(1), Mediator.of(2), Mediator.of(3)};
        final byte[] set = setOf(2, m);
        final byte[] e0 = entry(m[0].fp(), m[0].sign(message()), EMPTY_DELEGATION);
        assertError("MediatorQuorumNotMet", () -> verify(sigs(e0), set));
    }

    @Test
    void duplicateSignerIsNotCountedTwice() {
        // CONSTRUCTED: member 0 signs twice; 2-of-3 is not met.
        final var m = new Mediator[] {Mediator.of(1), Mediator.of(2), Mediator.of(3)};
        final byte[] set = setOf(2, m);
        final byte[] e0 = entry(m[0].fp(), m[0].sign(message()), EMPTY_DELEGATION);
        assertError("MediatorQuorumNotMet", () -> verify(sigs(e0, e0), set));
    }

    // ------------------------------- skip vs hard-reject

    @Test
    void paddingUnknownAndDuplicateEntriesAreSkippedButQuorumStillPasses() {
        // CONSTRUCTED: 2-of-3 set; entries = [all-zero padding, unknown fp, m0 valid, m0 duplicate WITH GARBAGE
        // signature (skipped before verification, so it must not reject), m1 valid].
        final var m = new Mediator[] {Mediator.of(1), Mediator.of(2), Mediator.of(3)};
        final byte[] set = setOf(2, m);
        final byte[] padding = entry(new byte[32], new byte[0], EMPTY_DELEGATION);
        final byte[] unknown = entry(sha256(new byte[] {42}), new byte[64], EMPTY_DELEGATION);
        final byte[] e0 = entry(m[0].fp(), m[0].sign(message()), EMPTY_DELEGATION);
        final byte[] dup0Garbage = entry(m[0].fp(), new byte[64], EMPTY_DELEGATION);
        final byte[] e1 = entry(m[1].fp(), m[1].sign(message()), EMPTY_DELEGATION);
        assertThat(verify(sigs(padding, unknown, e0, dup0Garbage, e1), set)).isEqualTo(VERDICT.rootHash());
    }

    @Test
    void knownKeyWithBadSignatureIsHardRejectedNotSkipped() {
        // CONSTRUCTED: valid m0 then m1 with m0's signature (known key, bad signature) -> reject, even though
        // a later valid m2 signature would have met the threshold had m1 merely been skipped.
        final var m = new Mediator[] {Mediator.of(1), Mediator.of(2), Mediator.of(3)};
        final byte[] set = setOf(2, m);
        final byte[] e0 = entry(m[0].fp(), m[0].sign(message()), EMPTY_DELEGATION);
        final byte[] badM1 = entry(m[1].fp(), m[0].sign(message()), EMPTY_DELEGATION);
        final byte[] e2 = entry(m[2].fp(), m[2].sign(message()), EMPTY_DELEGATION);
        assertError("InvalidMediatorSignature", () -> verify(sigs(e0, badM1, e2), set));
    }

    @Test
    void tamperedRealSignatureIsHardRejected() {
        // CONSTRUCTED: REAL signature with one bit flipped, under the REAL known key.
        final byte[] sig = CantonFixtures.verdictMediatorSig().clone();
        sig[0] ^= 1;
        final byte[] e = entry(realFingerprint(), sig, EMPTY_DELEGATION);
        assertError("InvalidMediatorSignature", () -> verify(sigs(e), REAL_SET));
    }

    @Test
    void entriesAfterThresholdAreNotExamined() {
        // CONSTRUCTED: threshold 1 reached by the REAL entry; the trailing known-key bad signature and the
        // trailing entry with a non-empty delegation are never looked at (loop stops at threshold).
        final byte[] badKnown = entry(realFingerprint(), new byte[64], EMPTY_DELEGATION);
        final byte[] deleg = entry(realFingerprint(), new byte[64], Rlp.encodeBytes(new byte[] {1}));
        assertThat(verify(sigs(realEntry(), badKnown, deleg), REAL_SET)).isEqualTo(VERDICT.rootHash());
    }

    @Test
    void invalidMediatorSetRejected() {
        // Set truncated by one byte.
        final byte[] set = Arrays.copyOf(REAL_SET, REAL_SET.length - 1);
        assertError("InvalidMediatorSet", () -> verify(sigs(realEntry()), set));
    }

    private static byte[] mutate(final byte[] src, final byte[] from, final byte[] to) {
        final List<Integer> hits = new ArrayList<>();
        for (int i = 0; i + from.length <= src.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(src, i, i + from.length), from)) {
                hits.add(i);
            }
        }
        assertThat(hits).hasSize(1);
        final byte[] out = src.clone();
        System.arraycopy(to, 0, out, hits.getFirst(), to.length);
        return out;
    }
}
