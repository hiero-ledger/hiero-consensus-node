// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

/**
 * The packed mediator set of a Canton trust anchor, and the signature check of a mediator verdict.
 * A verbatim Java port of {@code ClprCantonMediators.sol} — the packed-set offset walk, the key
 * lookup, and the per-key-spec signature dispatch all follow the reference byte-for-byte.
 *
 * <p>Packed layout (all multi-byte integers big-endian):
 * <pre>
 *   mediatorSet = mssSerial(4) ‖ threshold(4) ‖ n(4) ‖ Mediator × n
 *   Mediator    = memberId(32) ‖ otkSerial(4) ‖ d(1) ‖ Delegation × d ‖ k(1) ‖ Key × k ‖ p(1) ‖ Key × p
 *   Delegation  = Key ‖ restriction(1) ‖ mappings(4) ‖ serial(4) ‖ removed(1)
 *   Key         = fingerprint(32) ‖ keySpec(1) ‖ fingerprintMode(1) ‖ point(64)
 * </pre>
 * {@code point} is {@code x ‖ y} for ECDSA keys and the 32-byte key zero-padded for Ed25519; {@code k}
 * counts current keys and {@code p} previous ones. Delegations authorize key rotations and are skipped.
 */
public final class CantonMediators {

    /** Ed25519 key spec. */
    public static final int KEY_SPEC_ED25519 = 1;
    /** NIST P-256 key spec. */
    public static final int KEY_SPEC_P256 = 2;
    /** secp256k1 key spec. */
    public static final int KEY_SPEC_SECP256K1 = 3;

    private static final int HEADER_LENGTH = 12;
    /** {@code memberId(32) ‖ otkSerial(4)}, before the delegation count. */
    private static final int MEMBER_PREFIX_LENGTH = 36;

    private static final int KEY_LENGTH = 98;
    private static final int DELEGATION_LENGTH = 108;

    /** The secp256k1 curve order {@code n}. */
    private static final BigInteger SECP256K1_N =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    private CantonMediators() {}

    /** The threshold and member count of a validated mediator set. */
    public record SetSummary(int threshold, int members) {}

    /** The key of the signer {@code fingerprint}, and the index of the member it belongs to. */
    public record SignerKey(boolean found, int member, int keySpec, byte[] x, byte[] y) {
        /** A not-found result. */
        public static SignerKey notFound() {
            return new SignerKey(false, 0, 0, new byte[0], new byte[0]);
        }
    }

    /** The outcome of parsing an ECDSA signature into its {@code (r, s)} components. */
    public record EcdsaSignature(boolean ok, byte[] r, byte[] s) {
        static EcdsaSignature invalid() {
            return new EcdsaSignature(false, new byte[0], new byte[0]);
        }
    }

    /**
     * Checks the layout of {@code set} and returns its threshold and member count. Mirrors the
     * reference {@code validate}: the header must fit, the threshold must be in {@code [1, members]},
     * and the offset walk must consume the set exactly.
     *
     * @throws CantonProofException if the set does not match its declared layout
     */
    @NonNull
    public static SetSummary validate(@NonNull final byte[] set) {
        Objects.requireNonNull(set, "set");
        if (set.length < HEADER_LENGTH) {
            throw CantonProofException.invalidMediatorSet("set shorter than header (" + set.length + " bytes)");
        }
        final long threshold = u32(set, 4);
        final long members = u32(set, 8);
        if (threshold == 0 || threshold > members) {
            throw CantonProofException.invalidMediatorSet(
                    "threshold " + threshold + " out of range for " + members + " member(s)");
        }
        long off = HEADER_LENGTH;
        for (long m = 0; m < members; ++m) {
            off += MEMBER_PREFIX_LENGTH;
            if (off >= set.length) {
                throw CantonProofException.invalidMediatorSet("truncated before delegation count of member " + m);
            }
            off += 1 + Byte.toUnsignedInt(set[(int) off]) * (long) DELEGATION_LENGTH;
            if (off >= set.length) {
                throw CantonProofException.invalidMediatorSet("truncated before current-key count of member " + m);
            }
            off += 1 + Byte.toUnsignedInt(set[(int) off]) * (long) KEY_LENGTH;
            if (off >= set.length) {
                throw CantonProofException.invalidMediatorSet("truncated before previous-key count of member " + m);
            }
            off += 1 + Byte.toUnsignedInt(set[(int) off]) * (long) KEY_LENGTH;
        }
        if (off != set.length) {
            throw CantonProofException.invalidMediatorSet(
                    "trailing or missing bytes: walked to " + off + " of " + set.length);
        }
        return new SetSummary((int) threshold, (int) members);
    }

    /**
     * Finds the key whose fingerprint is {@code fingerprint32}, among the current and previous keys of
     * every member. {@code set} must have passed {@link #validate}. Mirrors the reference {@code lookup}.
     */
    @NonNull
    public static SignerKey lookup(@NonNull final byte[] set, @NonNull final byte[] fingerprint32) {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(fingerprint32, "fingerprint32");
        final long members = u32(set, 8);
        long off = HEADER_LENGTH;
        for (long m = 0; m < members; ++m) {
            off += MEMBER_PREFIX_LENGTH;
            off += 1 + Byte.toUnsignedInt(set[(int) off]) * (long) DELEGATION_LENGTH;
            for (int list = 0; list < 2; ++list) {
                final int count = Byte.toUnsignedInt(set[(int) off]);
                ++off;
                for (int k = 0; k < count; ++k) {
                    if (Arrays.equals(word(set, (int) off), fingerprint32)) {
                        final int keySpec = Byte.toUnsignedInt(set[(int) off + 32]);
                        final byte[] x = word(set, (int) off + 34);
                        final byte[] y = word(set, (int) off + 66);
                        return new SignerKey(true, (int) m, keySpec, x, y);
                    }
                    off += KEY_LENGTH;
                }
            }
        }
        return SignerKey.notFound();
    }

    /**
     * Whether {@code signature} is a valid signature of {@code key} over the (raw) {@code message}.
     * Ed25519 signs {@code message} directly; ECDSA ({@code SHA256withECDSA}) signs {@code SHA256(message)}
     * and its signature is DER or the 64-byte {@code r ‖ s}. Mirrors the reference {@code verify}.
     */
    public static boolean verify(
            @NonNull final SignerKey key, @NonNull final byte[] message, @NonNull final byte[] signature) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(signature, "signature");
        if (key.keySpec() == KEY_SPEC_ED25519) {
            return Ed25519Verify.verify(key.x(), message, signature);
        }
        final EcdsaSignature parsed = parseEcdsaSignature(signature);
        if (!parsed.ok()) {
            return false;
        }
        final byte[] digest = sha256(message);
        if (key.keySpec() == KEY_SPEC_P256) {
            return P256Verify.verify(key.x(), key.y(), digest, parsed.r(), parsed.s());
        }
        if (key.keySpec() == KEY_SPEC_SECP256K1) {
            final BigInteger s = new BigInteger(1, parsed.s());
            if (s.signum() == 0 || s.compareTo(SECP256K1_N) >= 0) {
                return false;
            }
            final byte[] expected =
                    Arrays.copyOfRange(MiscCryptoUtils.keccak256DigestOf(concat(key.x(), key.y())), 12, 32);
            final byte[] a = recoverSecp256k1Address(digest, parsed.r(), parsed.s(), 0);
            if (a != null && Arrays.equals(a, expected)) {
                return true;
            }
            final byte[] b = recoverSecp256k1Address(digest, parsed.r(), parsed.s(), 1);
            return b != null && Arrays.equals(b, expected);
        }
        return false;
    }

    /**
     * Reads {@code (r, s)} from a DER {@code SEQUENCE{INTEGER r, INTEGER s}} or a 64-byte {@code r ‖ s}.
     * Mirrors the reference {@code parseEcdsaSignature}: a raw 64-byte signature splits directly; a DER
     * signature must start with {@code 0x30}, declare its own length, and consume its bytes exactly.
     */
    @NonNull
    public static EcdsaSignature parseEcdsaSignature(@NonNull final byte[] sig) {
        Objects.requireNonNull(sig, "sig");
        if (sig.length == 64) {
            return new EcdsaSignature(true, Arrays.copyOfRange(sig, 0, 32), Arrays.copyOfRange(sig, 32, 64));
        }
        if (sig.length < 8 || sig[0] != 0x30 || Byte.toUnsignedInt(sig[1]) != sig.length - 2) {
            return EcdsaSignature.invalid();
        }
        int off = 2;
        final DerInteger r = derInteger(sig, off);
        if (!r.ok) {
            return EcdsaSignature.invalid();
        }
        off = r.next;
        final DerInteger s = derInteger(sig, off);
        if (!s.ok || s.next != sig.length) {
            return EcdsaSignature.invalid();
        }
        return new EcdsaSignature(true, r.value, s.value);
    }

    private record DerInteger(boolean ok, byte[] value, int next) {
        static DerInteger invalid(final int next) {
            return new DerInteger(false, new byte[0], next);
        }
    }

    @NonNull
    private static DerInteger derInteger(@NonNull final byte[] sig, int off) {
        if (off + 2 > sig.length || sig[off] != 0x02) {
            return DerInteger.invalid(off);
        }
        int len = Byte.toUnsignedInt(sig[off + 1]);
        off += 2;
        if (len == 0 || off + len > sig.length) {
            return DerInteger.invalid(off);
        }
        if (len == 33) {
            if (sig[off] != 0) {
                return DerInteger.invalid(off);
            }
            ++off;
            --len;
        }
        if (len > 32) {
            return DerInteger.invalid(off);
        }
        // Right-align the big-endian magnitude into a 32-byte word.
        final byte[] value = new byte[32];
        System.arraycopy(sig, off, value, 32 - len, len);
        return new DerInteger(true, value, off + len);
    }

    /**
     * Recovers the 20-byte address of the secp256k1 key that produced {@code (r, s)} over
     * {@code digest32} with recovery id {@code recId} (0 or 1), or {@code null} on any failure.
     * The address is {@code keccak256(x ‖ y)[12:]} of the recovered public key.
     */
    private static byte[] recoverSecp256k1Address(
            @NonNull final byte[] digest32, @NonNull final byte[] r, @NonNull final byte[] s, final int recId) {
        try {
            final byte[] compact = new byte[64];
            System.arraycopy(r, 0, compact, 0, 32);
            System.arraycopy(s, 0, compact, 32, 32);
            final var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
            if (LibSecp256k1.secp256k1_ecdsa_recoverable_signature_parse_compact(
                            LibSecp256k1.CONTEXT, recoverableSig, compact, recId)
                    != 1) {
                return null;
            }
            final var pubKey = new LibSecp256k1.secp256k1_pubkey();
            if (LibSecp256k1.secp256k1_ecdsa_recover(LibSecp256k1.CONTEXT, pubKey, recoverableSig, digest32) != 1) {
                return null;
            }
            final ByteBuffer serialized = ByteBuffer.allocate(65);
            final var size = new com.sun.jna.ptr.LongByReference(serialized.capacity());
            if (LibSecp256k1.secp256k1_ec_pubkey_serialize(
                            LibSecp256k1.CONTEXT, serialized, size, pubKey, LibSecp256k1.SECP256K1_EC_UNCOMPRESSED)
                    != 1) {
                return null;
            }
            // serialized = 0x04 ‖ x(32) ‖ y(32); address = keccak256(x ‖ y)[12:].
            final byte[] xy = Arrays.copyOfRange(serialized.array(), 1, 65);
            return Arrays.copyOfRange(MiscCryptoUtils.keccak256DigestOf(xy), 12, 32);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** The unsigned 32-bit big-endian value at {@code off} (the reference's {@code _u32}). */
    private static long u32(@NonNull final byte[] b, final int off) {
        return ((long) (Byte.toUnsignedInt(b[off])) << 24)
                | ((long) Byte.toUnsignedInt(b[off + 1]) << 16)
                | ((long) Byte.toUnsignedInt(b[off + 2]) << 8)
                | Byte.toUnsignedInt(b[off + 3]);
    }

    /** The 32 bytes at {@code off} (the reference's {@code _word}). */
    @NonNull
    private static byte[] word(@NonNull final byte[] b, final int off) {
        return Arrays.copyOfRange(b, off, off + 32);
    }

    @NonNull
    private static byte[] concat(@NonNull final byte[] a, @NonNull final byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @NonNull
    private static byte[] sha256(@NonNull final byte[] message) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(message);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
