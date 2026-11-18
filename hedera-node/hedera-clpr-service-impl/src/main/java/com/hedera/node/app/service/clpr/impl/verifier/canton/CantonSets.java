// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.be32;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.concat;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.sha256;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.u32;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.word;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The owner set and the mediator set of a Canton trust anchor, decoded so that topology rotations can change
 * them and encoded again for the anchor's hashes. Mirrors the reference {@code ClprCantonSets} byte for byte.
 *
 * <p>Packed layout (all multi-byte integers big-endian):
 * <pre>
 *   ownerSet    = dndSerial(4) ‖ threshold(4) ‖ n(4) ‖ Owner × n                  sorted by namespace
 *   Owner       = namespace(32) ‖ rootKey ‖ d(1) ‖ Delegation × d
 *   mediatorSet = mssSerial(4) ‖ threshold(4) ‖ n(4) ‖ Mediator × n               sorted by memberId
 *   Mediator    = memberId(32) ‖ otkSerial(4) ‖ d(1) ‖ Delegation × d ‖ k(1) ‖ Key × k ‖ p(1) ‖ Key × p
 *   Delegation  = Key ‖ restriction(1) ‖ mappings(4) ‖ serial(4) ‖ removed(1)
 *   Key         = fingerprint(32) ‖ keySpec(1) ‖ fingerprintMode(1) ‖ point(64)
 * </pre>
 * {@code keySpec}: 1 Ed25519 (the 32-byte key, zero-padded), 2 ECDSA P-256 ({@code x ‖ y}), 3 ECDSA secp256k1
 * ({@code x ‖ y}). {@code fingerprintMode}: 0 over the raw key (Ed25519), 1 over the DER
 * {@code SubjectPublicKeyInfo}.
 */
final class CantonSets {

    /** Ed25519 key spec. */
    static final int SPEC_ED25519 = 1;
    /** ECDSA P-256 key spec. */
    static final int SPEC_P256 = 2;
    /** ECDSA secp256k1 key spec. */
    static final int SPEC_SECP256K1 = 3;

    private static final int HEADER_LENGTH = 12;
    private static final int KEY_LENGTH = 98;
    private static final int DELEGATION_LENGTH = 108;

    /** {@code SHA256(BE32(12) ‖ keyBytes)}, Canton's {@code PublicKeyFingerprint} hash purpose. */
    static final int PURPOSE_PUBLIC_KEY_FINGERPRINT = 12;

    /**
     * The {@code SubjectPublicKeyInfo} header of each key spec, up to the key: for ECDSA keys including the
     * {@code 0x04} of the uncompressed point.
     */
    static final byte[] DER_ED25519 = hex("302a300506032b6570032100");

    static final byte[] DER_P256 = hex("3059301306072a8648ce3d020106082a8648ce3d03010703420004");
    static final byte[] DER_SECP256K1 = hex("3056301006072a8648ce3d020106052b8104000a03420004");

    private CantonSets() {}

    /** A Canton public key: its fingerprint, key spec, fingerprint mode, and the two 32-byte point words. */
    record Key(
            @NonNull byte[] fingerprint,
            int keySpec,
            int fingerprintMode,
            @NonNull byte[] x,
            @NonNull byte[] y) {

        /** Encodes this key as {@code fingerprint ‖ keySpec ‖ fingerprintMode ‖ x ‖ y}. */
        @NonNull
        byte[] encode() {
            final CantonBytes.Writer out = new CantonBytes.Writer();
            out.bytes(fingerprint);
            out.u8(keySpec);
            out.u8(fingerprintMode);
            out.bytes(x);
            out.bytes(y);
            return out.toBytes();
        }

        /** Whether this key's fingerprint is the one Canton computes for its key. Mirrors {@code hasOwnFingerprint}. */
        boolean hasOwnFingerprint() {
            final byte[] header = header(keySpec);
            if (header.length == 0) {
                return false;
            }
            final byte[] der = keySpec == SPEC_ED25519 ? concat(header, x) : concat(header, x, y);
            final KeyFromDer computed = keyFromDer(keySpec, der);
            return computed.ok() && Arrays.equals(computed.key().fingerprint(), fingerprint);
        }
    }

    /**
     * A namespace delegation. {@code restriction}: 0 all mappings, 1 all but {@code NamespaceDelegation},
     * 2 the codes set in {@code mappings}.
     */
    record Delegation(@NonNull Key key, int restriction, long mappings, long serial, boolean removed) {}

    /** An owner (a namespace), its root key, and its delegations. */
    record Owner(
            @NonNull byte[] namespace,
            @NonNull Key root,
            @NonNull List<Delegation> delegations) {}

    /** The decentralized-namespace owner set: serial, threshold, and the owners. */
    record Owners(long serial, long threshold, @NonNull List<Owner> owners) {

        /** Encodes this owner set. Mirrors the reference {@code encodeOwners}. */
        @NonNull
        byte[] encode() {
            final CantonBytes.Writer out = new CantonBytes.Writer();
            out.u32(serial);
            out.u32(threshold);
            out.u32(owners.size());
            for (final Owner o : owners) {
                out.bytes(o.namespace());
                out.bytes(o.root().encode());
                encodeDelegations(out, o.delegations());
            }
            return out.toBytes();
        }
    }

    /** A mediator member, its key-mapping serial, delegations, current keys, and previous keys. */
    record Member(
            @NonNull byte[] memberId,
            long otkSerial,
            @NonNull List<Delegation> delegations,
            @NonNull List<Key> current,
            @NonNull List<Key> previous) {}

    /** The mediator synchronizer state set: serial, threshold, and the members. */
    record Mediators(long serial, long threshold, @NonNull List<Member> members) {

        /** Encodes this mediator set. Mirrors the reference {@code encodeMediators}. */
        @NonNull
        byte[] encode() {
            final CantonBytes.Writer out = new CantonBytes.Writer();
            out.u32(serial);
            out.u32(threshold);
            out.u32(members.size());
            for (final Member m : members) {
                out.bytes(m.memberId());
                out.u32(m.otkSerial());
                encodeDelegations(out, m.delegations());
                encodeKeys(out, m.current());
                encodeKeys(out, m.previous());
            }
            return out.toBytes();
        }
    }

    /** The result of {@link #keyFromDer}: whether it decoded, and the key. */
    record KeyFromDer(boolean ok, @NonNull Key key) {}

    // ── Owner set ──────────────────────────────────────────────────────────

    /**
     * Decodes a packed owner set. Mirrors the reference {@code decodeOwners}.
     *
     * @throws CantonProofException {@code InvalidSet} if the bytes do not match the layout
     */
    @NonNull
    static Owners decodeOwners(@NonNull final byte[] b) {
        Objects.requireNonNull(b, "b");
        if (b.length < HEADER_LENGTH) {
            throw CantonProofException.invalidSet("owner set shorter than header");
        }
        final long serial = u32(b, 0);
        final long threshold = u32(b, 4);
        final long n = u32(b, 8);
        final List<Owner> owners = new ArrayList<>();
        int off = HEADER_LENGTH;
        for (long i = 0; i < n; ++i) {
            if (off + 32 + KEY_LENGTH >= b.length) {
                throw CantonProofException.invalidSet("owner set truncated at owner " + i);
            }
            final byte[] namespace = word(b, off);
            final Key root = key(b, off + 32);
            if (!Arrays.equals(root.fingerprint(), namespace)) {
                throw CantonProofException.invalidSet("owner root fingerprint is not its namespace");
            }
            final Delegations d = delegations(b, off + 32 + KEY_LENGTH);
            owners.add(new Owner(namespace, root, d.list()));
            off = d.next();
        }
        if (off != b.length || threshold == 0 || threshold > owners.size()) {
            throw CantonProofException.invalidSet("owner set length or threshold invalid");
        }
        return new Owners(serial, threshold, owners);
    }

    // ── Mediator set ─────────────────────────────────────────────────────────

    /**
     * Decodes a packed mediator set. Mirrors the reference {@code decodeMediators}.
     *
     * @throws CantonProofException {@code InvalidSet} if the bytes do not match the layout
     */
    @NonNull
    static Mediators decodeMediators(@NonNull final byte[] b) {
        Objects.requireNonNull(b, "b");
        if (b.length < HEADER_LENGTH) {
            throw CantonProofException.invalidSet("mediator set shorter than header");
        }
        final long serial = u32(b, 0);
        final long threshold = u32(b, 4);
        final long n = u32(b, 8);
        final List<Member> members = new ArrayList<>();
        int off = HEADER_LENGTH;
        for (long i = 0; i < n; ++i) {
            if (off + 36 >= b.length) {
                throw CantonProofException.invalidSet("mediator set truncated at member " + i);
            }
            final byte[] memberId = word(b, off);
            final long otkSerial = u32(b, off + 32);
            final Delegations d = delegations(b, off + 36);
            final Keys current = keys(b, d.next());
            final Keys previous = keys(b, current.next());
            members.add(new Member(memberId, otkSerial, d.list(), current.list(), previous.list()));
            off = previous.next();
        }
        if (off != b.length || threshold == 0 || threshold > members.size()) {
            throw CantonProofException.invalidSet("mediator set length or threshold invalid");
        }
        return new Mediators(serial, threshold, members);
    }

    // ── Keys ───────────────────────────────────────────────────────────────

    /** Decodes a single 98-byte key. Mirrors the reference {@code decodeKey}. */
    @NonNull
    static Key decodeKey(@NonNull final byte[] b) {
        Objects.requireNonNull(b, "b");
        if (b.length != KEY_LENGTH) {
            throw CantonProofException.invalidSet("key not " + KEY_LENGTH + " bytes");
        }
        return key(b, 0);
    }

    /**
     * The key of a DER {@code SubjectPublicKeyInfo} of key spec {@code keySpec}, with its fingerprint computed
     * as Canton does: over the raw key for Ed25519, over the DER for ECDSA. {@code ok} is false for another
     * spec or encoding. Mirrors the reference {@code keyFromDer}.
     */
    @NonNull
    static KeyFromDer keyFromDer(final int keySpec, @NonNull final byte[] der) {
        Objects.requireNonNull(der, "der");
        final byte[] header = header(keySpec);
        final int points = keySpec == SPEC_ED25519 ? 32 : 64;
        if (header.length == 0 || der.length != header.length + points) {
            return new KeyFromDer(false, zeroKey());
        }
        for (int i = 0; i < header.length; ++i) {
            if (der[i] != header[i]) {
                return new KeyFromDer(false, zeroKey());
            }
        }
        final byte[] x = word(der, header.length);
        if (keySpec == SPEC_ED25519) {
            final byte[] fingerprint = sha256(concat(be32(PURPOSE_PUBLIC_KEY_FINGERPRINT), x));
            return new KeyFromDer(true, new Key(fingerprint, keySpec, 0, x, new byte[32]));
        }
        final byte[] y = word(der, header.length + 32);
        final byte[] fingerprint = sha256(concat(be32(PURPOSE_PUBLIC_KEY_FINGERPRINT), der));
        return new KeyFromDer(true, new Key(fingerprint, keySpec, 1, x, y));
    }

    @NonNull
    private static byte[] header(final int keySpec) {
        if (keySpec == SPEC_ED25519) {
            return DER_ED25519;
        }
        if (keySpec == SPEC_P256) {
            return DER_P256;
        }
        if (keySpec == SPEC_SECP256K1) {
            return DER_SECP256K1;
        }
        return new byte[0];
    }

    // ── Decoding helpers ───────────────────────────────────────────────────

    private record Delegations(@NonNull List<Delegation> list, int next) {}

    private record Keys(@NonNull List<Key> list, int next) {}

    private static Delegations delegations(final byte[] b, final int off) {
        if (off >= b.length) {
            throw CantonProofException.invalidSet("truncated before delegation count");
        }
        final int count = Byte.toUnsignedInt(b[off]);
        final List<Delegation> d = new ArrayList<>(count);
        int next = off + 1;
        for (int i = 0; i < count; ++i) {
            if (next + DELEGATION_LENGTH > b.length) {
                throw CantonProofException.invalidSet("truncated delegation " + i);
            }
            final Key k = key(b, next);
            final int restriction = Byte.toUnsignedInt(b[next + KEY_LENGTH]);
            final long mappings = u32(b, next + KEY_LENGTH + 1);
            final long serial = u32(b, next + KEY_LENGTH + 5);
            final boolean removed = b[next + KEY_LENGTH + 9] != 0;
            d.add(new Delegation(k, restriction, mappings, serial, removed));
            next += DELEGATION_LENGTH;
        }
        return new Delegations(d, next);
    }

    private static Keys keys(final byte[] b, final int off) {
        if (off >= b.length) {
            throw CantonProofException.invalidSet("truncated before key count");
        }
        final int count = Byte.toUnsignedInt(b[off]);
        final List<Key> k = new ArrayList<>(count);
        int next = off + 1;
        for (int i = 0; i < count; ++i) {
            if (next + KEY_LENGTH > b.length) {
                throw CantonProofException.invalidSet("truncated key " + i);
            }
            k.add(key(b, next));
            next += KEY_LENGTH;
        }
        return new Keys(k, next);
    }

    private static Key key(final byte[] b, final int off) {
        final byte[] fingerprint = word(b, off);
        final int keySpec = Byte.toUnsignedInt(b[off + 32]);
        final int fingerprintMode = Byte.toUnsignedInt(b[off + 33]);
        final byte[] x = word(b, off + 34);
        final byte[] y = word(b, off + 66);
        return new Key(fingerprint, keySpec, fingerprintMode, x, y);
    }

    // ── Encoding helpers ───────────────────────────────────────────────────

    private static void encodeDelegations(final CantonBytes.Writer out, final List<Delegation> d) {
        if (d.size() > 255) {
            throw CantonProofException.invalidSet("too many delegations");
        }
        out.u8(d.size());
        for (final Delegation x : d) {
            out.bytes(x.key().encode());
            out.u8(x.restriction());
            out.u32(x.mappings());
            out.u32(x.serial());
            out.u8(x.removed() ? 1 : 0);
        }
    }

    private static void encodeKeys(final CantonBytes.Writer out, final List<Key> k) {
        if (k.size() > 255) {
            throw CantonProofException.invalidSet("too many keys");
        }
        out.u8(k.size());
        for (final Key x : k) {
            out.bytes(x.encode());
        }
    }

    // ── Primitives ───────────────────────────────────────────────────────────

    private static Key zeroKey() {
        return new Key(new byte[32], 0, 0, new byte[32], new byte[32]);
    }

    private static byte[] hex(final String s) {
        return java.util.HexFormat.of().parseHex(s);
    }
}
