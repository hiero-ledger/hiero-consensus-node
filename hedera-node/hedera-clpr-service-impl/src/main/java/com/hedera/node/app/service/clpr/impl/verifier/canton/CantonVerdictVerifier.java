// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Checks a Canton mediator verdict: a fresh approval of a transaction view on the expected synchronizer, signed by
 * a threshold of distinct members of the mediator set. Port of {@code verifyVerdict}/{@code _check} of the
 * reference {@code CantonVerdictVerifier.sol} (without bundle rotations or LSU handling).
 *
 * <p>Signatures are RLP {@code [[keyFingerprint(32), signature, delegation], …]} with every {@code delegation}
 * empty. Entries with an unknown fingerprint or a member already counted are skipped; a known key whose signature
 * fails is a hard reject. Scanning stops once the threshold is reached.
 *
 * <p>RLP is decoded by a private strict reader that mirrors the reference's OpenZeppelin {@code RLP} library
 * (canonical lengths, no non-minimal single-byte strings, exact list consumption), because the general-purpose
 * {@code Rlp} decoder in this module is more lenient and consensus verification must accept exactly what the
 * reference accepts.
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
        final List<Slice> entries = readList(new Slice(signatures, 0, signatures.length));
        int count = 0;
        for (int i = 0; i < entries.size() && count < threshold; ++i) {
            final List<Slice> sig = readList(entries.get(i));
            if (sig.size() != 3) {
                throw CantonProofException.invalidPayloadShape("signature entry has " + sig.size() + " items");
            }
            if (!isEmpty(sig.get(2))) {
                throw CantonProofException.sessionKeysNotSupported();
            }
            final CantonMediators.SignerKey key = CantonMediators.lookup(set, readBytes32(sig.get(0)));
            if (!key.found() || counted[key.member()]) {
                continue;
            }
            if (!CantonMediators.verify(key, message, readBytes(sig.get(1)))) {
                throw CantonProofException.invalidMediatorSignature();
            }
            counted[key.member()] = true;
            ++count;
        }
        if (count < threshold) {
            throw CantonProofException.mediatorQuorumNotMet();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Strict RLP (mirrors OpenZeppelin RLP.sol _decodeLength / readList / readBytes / readBytes32)
    // ---------------------------------------------------------------------------------------------

    private record Slice(byte[] buf, int off, int len) {}

    private record Header(int offset, int length, boolean isList) {}

    private static CantonProofException bad(final String why) {
        return CantonProofException.invalidPayloadShape("invalid RLP: " + why);
    }

    private static Header header(final Slice s) {
        if (s.len() == 0) {
            throw bad("empty item");
        }
        final int prefix = s.buf()[s.off()] & 0xff;
        if (prefix < 0x80) {
            return new Header(0, 1, false);
        }
        if (prefix <= 0xb7) {
            final int n = prefix - 0x80;
            if (s.len() <= n || (n == 1 && (s.buf()[s.off() + 1] & 0xff) < 0x80)) {
                throw bad("short string");
            }
            return new Header(1, n, false);
        }
        if (prefix <= 0xbf) {
            return longForm(s, prefix - 0xb7, false);
        }
        if (prefix <= 0xf7) {
            final int n = prefix - 0xc0;
            if (s.len() <= n) {
                throw bad("short list");
            }
            return new Header(1, n, true);
        }
        return longForm(s, prefix - 0xf7, true);
    }

    private static Header longForm(final Slice s, final int lengthLength, final boolean list) {
        if (s.len() <= lengthLength || s.buf()[s.off() + 1] == 0) {
            throw bad("long length");
        }
        long len = 0;
        for (int i = 0; i < lengthLength; ++i) {
            len = (len << 8) | (s.buf()[s.off() + 1 + i] & 0xff);
        }
        // len > 55 and the item must fit exactly-or-more (lengthLength + len < itemLength is rejected below by
        // the callers' exact-consumption checks); a negative or huge len can never fit.
        if (len <= 55 || len < 0 || len >= s.len() || s.len() <= lengthLength + len) {
            throw bad("long length value");
        }
        return new Header(lengthLength + 1, (int) len, list);
    }

    private static List<Slice> readList(final Slice item) {
        final Header h = header(item);
        if (!h.isList() || item.len() != h.offset() + h.length()) {
            throw bad("not an exact list");
        }
        final List<Slice> out = new ArrayList<>();
        int cur = h.offset();
        while (cur < item.len()) {
            final Slice rest = new Slice(item.buf(), item.off() + cur, item.len() - cur);
            final Header eh = header(rest);
            final int total = eh.offset() + eh.length();
            if (total > rest.len()) {
                throw bad("element overruns list");
            }
            out.add(new Slice(item.buf(), item.off() + cur, total));
            cur += total;
        }
        return out;
    }

    private static boolean isEmpty(final Slice s) {
        if (s.len() != 1) {
            return s.len() == 0;
        }
        final int head = s.buf()[s.off()] & 0xff;
        return head == 0x80 || head == 0xc0;
    }

    private static byte[] readBytes(final Slice item) {
        final Header h = header(item);
        if (h.isList()) {
            throw bad("expected string");
        }
        return Arrays.copyOfRange(item.buf(), item.off() + h.offset(), item.off() + h.offset() + h.length());
    }

    /** {@code RLP.readBytes32}: a string of at most 32 bytes, left-padded with zeros (big-endian uint256). */
    private static byte[] readBytes32(final Slice item) {
        if (item.len() > 33) {
            throw bad("bytes32 item too long");
        }
        final byte[] value = readBytes(item);
        final byte[] out = new byte[32];
        System.arraycopy(value, 0, out, 32 - value.length, value.length);
        return out;
    }
}
