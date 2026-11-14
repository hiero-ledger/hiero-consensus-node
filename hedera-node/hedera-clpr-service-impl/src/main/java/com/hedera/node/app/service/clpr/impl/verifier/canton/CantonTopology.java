// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import com.hedera.node.app.service.clpr.impl.verifier.Ed25519Verify;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.Field;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.Range;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProto.Varint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Canton's signed topology transactions, as a trust anchor follows them: what each transaction says, and which
 * keys signed it. Mirrors the reference {@code ClprCantonTopology} byte for byte.
 *
 * <p>A transaction travels as Canton lists it:
 * {@code UntypedVersionedMessage{1: SignedTopologyTransaction, 2: 30}}, where
 * {@code SignedTopologyTransaction{1: transaction, 2: Signature…, 3: proposal, 4: MultiTransactionSignatures…}}
 * and {@code transaction} is {@code UntypedVersionedMessage{1: TopologyTransaction{1: operation, 2: serial,
 * 3: TopologyMapping}, 2: 30}}. Its hash is {@code SHA256(BE32(11) ‖ transaction)}, as received.
 */
public final class CantonTopology {

    static final long VERSION_V30 = 30;
    public static final long OP_ADD_REPLACE = 1;
    public static final long OP_REMOVE = 2;

    /** {@code TopologyMapping} oneof fields. */
    public static final long MAPPING_NAMESPACE_DELEGATION = 1;

    public static final long MAPPING_DECENTRALIZED_NAMESPACE = 3;
    public static final long MAPPING_OWNER_TO_KEY = 4;
    public static final long MAPPING_MEDIATOR_STATE = 12;
    public static final long MAPPING_LSU_ANNOUNCEMENT = 17;

    /** Mapping codes, which delegation restrictions name. */
    public static final int CODE_NAMESPACE_DELEGATION = 1;

    public static final int CODE_DECENTRALIZED_NAMESPACE = 3;
    public static final int CODE_OWNER_TO_KEY = 4;
    public static final int CODE_MEDIATOR_STATE = 12;
    public static final int CODE_LSU_ANNOUNCEMENT = 19;

    /** {@code SigningKeyUsage.PROTOCOL}, the usage of the keys that sign verdicts. */
    static final long USAGE_PROTOCOL = 4;
    /** {@code CryptoKeyFormat.DER_X509_SUBJECT_PUBLIC_KEY_INFO}. */
    static final long FORMAT_DER_SPKI = 4;

    static final int PURPOSE_TOPOLOGY_TRANSACTION = 11;
    static final int PURPOSE_MULTI_TOPOLOGY_TRANSACTION = 55;
    /** The bound on the hashes of one multi-transaction signature that the verifier sorts. */
    static final int MAX_MULTI_HASHES = 64;

    private static final byte[] MULTIHASH_PREFIX = {0x12, 0x20};
    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private CantonTopology() {}

    /** A signature over a topology transaction: the signer fingerprint, the signed hash, and the bytes. */
    public record Signature(
            @NonNull byte[] fingerprint,
            @NonNull byte[] hash,
            @NonNull byte[] signature) {}

    /** A signed topology transaction: its operation, serial, mapping kind, mapping body, hash, and signatures. */
    public record Transaction(
            long operation,
            long serial,
            long mappingKind,
            @NonNull byte[] body,
            @NonNull byte[] hash,
            @NonNull List<Signature> signatures) {}

    /** A parsed {@code LsuAnnouncement}: the successor physical synchronizer id and the upgrade time (micros). */
    public record LsuAnnouncement(@NonNull byte[] successor, long upgradeTime) {}

    /** A parsed {@code DecentralizedNamespaceDefinition}: the namespace, threshold, and owner namespaces. */
    public record DecentralizedNamespace(
            @NonNull byte[] namespace,
            long threshold,
            @NonNull List<byte[]> owners) {}

    /** A parsed {@code OwnerToKeyMapping}: the mediator uid and its {@code PROTOCOL} signing keys. */
    public record OwnerToKey(@NonNull byte[] uid, @NonNull List<CantonSets.Key> keys) {}

    /** A parsed {@code MediatorSynchronizerState}: synchronizer id range, group, threshold, and active members. */
    public record MediatorState(
            @NonNull Range synchronizerId,
            long group,
            long threshold,
            @NonNull List<byte[]> active) {}

    /** A parsed {@code NamespaceDelegation}: namespace, target key (with {@code ok}), restriction, mappings. */
    public record NamespaceDelegation(
            @NonNull byte[] namespace, boolean ok, @NonNull CantonSets.Key target, int restriction, long mappings) {}

    /** A parsed {@code SigningPublicKey}: whether it decoded, the key, and whether it has usage {@code PROTOCOL}. */
    public record SigningKey(boolean ok, @NonNull CantonSets.Key key, boolean protocol) {}

    // ── Transactions and signatures ────────────────────────────────────────

    /**
     * Reads the versioned {@code SignedTopologyTransaction} {@code wrapped}, with every signature that names its
     * key without a session-key delegation. Mirrors the reference {@code readTransaction}.
     */
    @NonNull
    public static Transaction readTransaction(@NonNull final byte[] wrapped) {
        Objects.requireNonNull(wrapped, "wrapped");
        final byte[] signed = unwrapV30(wrapped);
        final Range r = CantonProto.whole(signed);
        final byte[] txBytes = CantonProto.copy(signed, CantonProto.get(signed, r, 1));
        final byte[] hash = sha256(concat(be32(PURPOSE_TOPOLOGY_TRANSACTION), txBytes));

        final byte[] tx = unwrapV30(txBytes);
        final Range tr = CantonProto.whole(tx);
        final long operation = CantonProto.getVarint(tx, tr, 1);
        final long serial = CantonProto.getVarint(tx, tr, 2);
        final Field bodyField = CantonProto.onlyField(tx, CantonProto.get(tx, tr, 3));
        if (bodyField.wireType() != CantonProto.WIRE_LEN) {
            throw CantonProofException.cantonProtoMalformed("topology mapping is not a message");
        }
        final long mappingKind = bodyField.number();
        final byte[] body = CantonProto.copy(tx, bodyField.value());

        final List<Range> single = CantonProto.getAll(signed, r, 2);
        final List<Range> multi = CantonProto.getAll(signed, r, 4);
        final List<Signature> signatures = new ArrayList<>(signatures(signed, single, hash));
        for (final Range m : multi) {
            final byte[] combined = combinedHash(signed, CantonProto.getAll(signed, m, 1), hash);
            signatures.addAll(signatures(signed, CantonProto.getAll(signed, m, 2), combined));
        }
        return new Transaction(operation, serial, mappingKind, body, hash, signatures);
    }

    /**
     * Whether {@code key} signed {@code t}. A signature that names {@code key} and does not verify throws
     * {@code InvalidTopologySignature}. Mirrors the reference {@code signedBy}.
     *
     * <p>Unlike the Solidity reference, no {@code IEd25519Verifier} argument is needed: this port verifies
     * Ed25519 through the static {@link Ed25519Verify}, as {@link CantonMediators#verify} does.
     */
    public static boolean signedBy(@NonNull final Transaction t, @NonNull final CantonSets.Key key) {
        Objects.requireNonNull(t, "t");
        Objects.requireNonNull(key, "key");
        for (final Signature s : t.signatures()) {
            if (!Arrays.equals(s.fingerprint(), key.fingerprint())) {
                continue;
            }
            final CantonMediators.SignerKey k = new CantonMediators.SignerKey(true, 0, key.keySpec(), key.x(), key.y());
            final byte[] message = concat(MULTIHASH_PREFIX, s.hash());
            if (!CantonMediators.verify(k, message, s.signature())) {
                throw CantonProofException.invalidTopologySignature();
            }
            return true;
        }
        return false;
    }

    private static List<Signature> signatures(final byte[] signed, final List<Range> items, final byte[] hash) {
        final List<Signature> out = new ArrayList<>();
        for (final Range item : items) {
            if (CantonProto.find(signed, item, 5) != null) {
                continue; // a session-key delegation
            }
            final Fingerprint fp = fingerprint(signed, CantonProto.get(signed, item, 3));
            if (!fp.ok()) {
                continue;
            }
            out.add(new Signature(fp.value(), hash, CantonProto.copy(signed, CantonProto.get(signed, item, 2))));
        }
        return out;
    }

    /**
     * {@code SHA256(BE32(55) ‖ BE32(n) ‖ (BE32(34) ‖ 0x1220 ‖ h) for each distinct hash h, ascending)}. Throws
     * unless {@code hashes} hold {@code txHash}. Mirrors the reference {@code _combinedHash}.
     */
    private static byte[] combinedHash(final byte[] signed, final List<Range> hashes, final byte[] txHash) {
        if (hashes.isEmpty() || hashes.size() > MAX_MULTI_HASHES) {
            throw CantonProofException.cantonProtoMalformed("multi-transaction hash count out of range");
        }
        final List<byte[]> sorted = new ArrayList<>(hashes.size());
        boolean covers = false;
        for (final Range hr : hashes) {
            final byte[] h = CantonProto.readMultihash(signed, hr);
            covers = covers || Arrays.equals(h, txHash);
            boolean seen = false;
            int j = sorted.size();
            for (final byte[] existing : sorted) {
                if (Arrays.equals(existing, h)) {
                    seen = true;
                    break;
                }
            }
            if (seen) {
                continue;
            }
            while (j > 0 && Arrays.compareUnsigned(sorted.get(j - 1), h) > 0) {
                --j;
            }
            sorted.add(j, h);
        }
        if (!covers) {
            throw CantonProofException.cantonProtoMalformed("multi-transaction hashes do not cover this transaction");
        }
        final CantonBuf buf = new CantonBuf();
        buf.be32(PURPOSE_MULTI_TOPOLOGY_TRANSACTION);
        buf.be32(sorted.size());
        for (final byte[] h : sorted) {
            buf.be32(34);
            buf.bytes(MULTIHASH_PREFIX);
            buf.bytes(h);
        }
        return sha256(buf.toBytes());
    }

    // ── Mappings ───────────────────────────────────────────────────────────

    /**
     * {@code LsuAnnouncement{1: successor_physical_synchronizer_id, 2: Timestamp{1: seconds, 2: nanos}}}; the
     * upgrade time in microseconds. Mirrors the reference {@code readLsuAnnouncement}.
     */
    @NonNull
    public static LsuAnnouncement readLsuAnnouncement(@NonNull final byte[] b) {
        Objects.requireNonNull(b, "b");
        final Range r = CantonProto.whole(b);
        final byte[] successor = CantonProto.copy(b, CantonProto.get(b, r, 1));
        final Range time = CantonProto.get(b, r, 2);
        final BigInteger seconds = unsigned(CantonProto.getVarint(b, time, 1));
        final BigInteger nanos = unsigned(CantonProto.getVarint(b, time, 2));
        final BigInteger micros =
                seconds.multiply(BigInteger.valueOf(1_000_000)).add(nanos.divide(BigInteger.valueOf(1_000)));
        if (micros.compareTo(UINT64_MAX) > 0) {
            throw CantonProofException.cantonProtoMalformed("LSU upgrade time overflows uint64");
        }
        return new LsuAnnouncement(successor, micros.longValue());
    }

    /**
     * {@code DecentralizedNamespaceDefinition{1: decentralized_namespace, 2: threshold, 3: owners}}. Mirrors the
     * reference {@code readDecentralizedNamespace}.
     */
    @NonNull
    public static DecentralizedNamespace readDecentralizedNamespace(@NonNull final byte[] b) {
        Objects.requireNonNull(b, "b");
        final Range r = CantonProto.whole(b);
        final byte[] namespace = namespaceOf(b, CantonProto.get(b, r, 1));
        final long threshold = CantonProto.getVarint(b, r, 2);
        final List<Range> o = CantonProto.getAll(b, r, 3);
        final List<byte[]> owners = new ArrayList<>(o.size());
        for (final Range or : o) {
            owners.add(namespaceOf(b, or));
        }
        return new DecentralizedNamespace(namespace, threshold, owners);
    }

    /**
     * {@code OwnerToKeyMapping{1: member, 2: PublicKey{1: SigningPublicKey}…}} of a mediator, {@code MED::<uid>}:
     * its uid, and its signing keys with usage {@code PROTOCOL} in a key spec that the verifier checks. Mirrors
     * the reference {@code readOwnerToKey}.
     */
    @NonNull
    public static OwnerToKey readOwnerToKey(@NonNull final byte[] b) {
        Objects.requireNonNull(b, "b");
        final Range r = CantonProto.whole(b);
        final Range member = CantonProto.get(b, r, 1);
        if (member.length() < 5
                || b[member.start()] != 'M'
                || b[member.start() + 1] != 'E'
                || b[member.start() + 2] != 'D'
                || b[member.start() + 3] != ':'
                || b[member.start() + 4] != ':') {
            throw CantonProofException.cantonProtoMalformed("OwnerToKeyMapping member is not a MED:: uid");
        }
        final byte[] uid = Arrays.copyOfRange(b, member.start() + 5, member.end());
        final List<Range> publicKeys = CantonProto.getAll(b, r, 2);
        final List<CantonSets.Key> keys = new ArrayList<>();
        for (final Range pk : publicKeys) {
            final Range key = CantonProto.find(b, pk, 1);
            if (key == null) {
                continue;
            }
            final SigningKey sk = signingKey(b, key);
            if (sk.ok() && sk.protocol()) {
                keys.add(sk.key());
            }
        }
        return new OwnerToKey(uid, keys);
    }

    /**
     * {@code MediatorSynchronizerState{1: synchronizer_id, 2: group, 3: threshold, 4: active, 5: observers}}.
     * Mirrors the reference {@code readMediatorState}.
     */
    @NonNull
    public static MediatorState readMediatorState(@NonNull final byte[] b) {
        Objects.requireNonNull(b, "b");
        final Range r = CantonProto.whole(b);
        final Range synchronizerId = CantonProto.get(b, r, 1);
        final long group = CantonProto.getVarint(b, r, 2);
        final long threshold = CantonProto.getVarint(b, r, 3);
        final List<Range> a = CantonProto.getAll(b, r, 4);
        final List<byte[]> active = new ArrayList<>(a.size());
        for (final Range ar : a) {
            active.add(CantonProto.copy(b, ar));
        }
        return new MediatorState(synchronizerId, group, threshold, active);
    }

    /**
     * {@code NamespaceDelegation{1: namespace, 2: target_key, 3: is_root_delegation, 4 | 5 | 6: restriction}}.
     * Mirrors the reference {@code readNamespaceDelegation}.
     */
    @NonNull
    public static NamespaceDelegation readNamespaceDelegation(@NonNull final byte[] b) {
        Objects.requireNonNull(b, "b");
        final Range r = CantonProto.whole(b);
        final byte[] namespace = namespaceOf(b, CantonProto.get(b, r, 1));
        final SigningKey target = signingKey(b, CantonProto.get(b, r, 2));
        final boolean all = CantonProto.find(b, r, 4) != null;
        final boolean allButDelegations = CantonProto.find(b, r, 5) != null;
        final Range codes = CantonProto.find(b, r, 6);
        final boolean specific = codes != null;
        if (count(all, allButDelegations, specific) > 1) {
            throw CantonProofException.cantonProtoMalformed("more than one namespace-delegation restriction");
        }
        if (all) {
            return new NamespaceDelegation(namespace, target.ok(), target.key(), 0, 0);
        }
        if (allButDelegations) {
            return new NamespaceDelegation(namespace, target.ok(), target.key(), 1, 0);
        }
        if (specific) {
            return new NamespaceDelegation(namespace, target.ok(), target.key(), 2, codes(b, codes));
        }
        final int restriction = CantonProto.getVarint(b, r, 3) != 0 ? 0 : 1;
        return new NamespaceDelegation(namespace, target.ok(), target.key(), restriction, 0);
    }

    /**
     * A {@code SigningPublicKey{2: format, 3: public_key, 5: usage, 6: key_spec}} as a key of the sets, and
     * whether it has usage {@code PROTOCOL}. {@code ok} is false for a format or key spec the verifier does not
     * check. Mirrors the reference {@code signingKey}.
     */
    @NonNull
    public static SigningKey signingKey(@NonNull final byte[] b, @NonNull final Range r) {
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(r, "r");
        final long spec = CantonProto.getVarint(b, r, 6);
        final int keySpec = spec == 1
                ? CantonSets.SPEC_ED25519
                : spec == 2 ? CantonSets.SPEC_P256 : spec == 4 ? CantonSets.SPEC_SECP256K1 : 0;
        if (keySpec == 0 || CantonProto.getVarint(b, r, 2) != FORMAT_DER_SPKI) {
            return new SigningKey(false, zeroKey(), false);
        }
        final CantonSets.KeyFromDer parsed =
                CantonSets.keyFromDer(keySpec, CantonProto.copy(b, CantonProto.get(b, r, 3)));
        final boolean protocol = hasUsage(b, r, USAGE_PROTOCOL);
        return new SigningKey(parsed.ok(), parsed.key(), protocol);
    }

    /**
     * The 32 bytes of a namespace or a fingerprint written as {@code 1220<64 hex>}. Mirrors {@code namespaceOf}.
     */
    @NonNull
    public static byte[] namespaceOf(@NonNull final byte[] b, @NonNull final Range r) {
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(r, "r");
        final Fingerprint f = fingerprint(b, r);
        if (!f.ok()) {
            throw CantonProofException.cantonProtoMalformed("not a 1220 fingerprint");
        }
        return f.value();
    }

    /** The namespace of a uid {@code <name>::1220<64 hex>}. Mirrors {@code namespaceOfUid}. */
    @NonNull
    public static byte[] namespaceOfUid(@NonNull final byte[] uid) {
        Objects.requireNonNull(uid, "uid");
        if (uid.length < 71 || uid[uid.length - 70] != ':' || uid[uid.length - 69] != ':') {
            throw CantonProofException.cantonProtoMalformed("uid has no namespace suffix");
        }
        return namespaceOf(uid, new Range(uid.length - 68, uid.length));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record Fingerprint(boolean ok, @NonNull byte[] value) {}

    private static Fingerprint fingerprint(final byte[] b, final Range r) {
        if (r.length() != 68 || b[r.start()] != '1' || b[r.start() + 1] != '2' || b[r.start() + 2] != '2') {
            return new Fingerprint(false, new byte[32]);
        }
        if (b[r.start() + 3] != '0') {
            return new Fingerprint(false, new byte[32]);
        }
        final byte[] raw = CantonProto.hexValue(b, new Range(r.start() + 4, r.end()));
        return new Fingerprint(true, raw);
    }

    /** Whether the repeated enum field 5 of {@code r} holds {@code usage}, packed or not. Mirrors {@code _hasUsage}. */
    private static boolean hasUsage(final byte[] b, final Range r, final long usage) {
        int i = r.start();
        while (i < r.end()) {
            final Field f = CantonProto.readField(b, i, r.end());
            if (f.number() == 5) {
                if (f.wireType() == CantonProto.WIRE_VARINT && f.scalar() == usage) {
                    return true;
                }
                if (f.wireType() == CantonProto.WIRE_LEN) {
                    int j = f.value().start();
                    while (j < f.value().end()) {
                        final Varint u = CantonProto.readVarint(b, j, f.value().end());
                        if (u.value() == usage) {
                            return true;
                        }
                        j = u.next();
                    }
                }
            }
            i = f.next();
        }
        return false;
    }

    /** The bit mask of the packed {@code repeated TopologyMappingCode mappings = 1} in {@code r}. Mirrors {@code _codes}. */
    private static long codes(final byte[] b, final Range r) {
        long mask = 0;
        int i = r.start();
        while (i < r.end()) {
            final Field f = CantonProto.readField(b, i, r.end());
            if (f.number() == 1) {
                if (f.wireType() == CantonProto.WIRE_VARINT && f.scalar() >= 0 && f.scalar() < 32) {
                    mask |= 1L << f.scalar();
                }
                if (f.wireType() == CantonProto.WIRE_LEN) {
                    int j = f.value().start();
                    while (j < f.value().end()) {
                        final Varint c = CantonProto.readVarint(b, j, f.value().end());
                        if (c.value() >= 0 && c.value() < 32) {
                            mask |= 1L << c.value();
                        }
                        j = c.next();
                    }
                }
            }
            i = f.next();
        }
        return mask;
    }

    private static int count(final boolean a, final boolean b, final boolean c) {
        return (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0);
    }

    private static byte[] unwrapV30(final byte[] wrapped) {
        final CantonProto.Versioned v = CantonProto.unwrapVersioned(wrapped);
        if (v.version() != VERSION_V30) {
            throw CantonProofException.cantonProtoMalformed("unexpected versioned-message version " + v.version());
        }
        return v.data();
    }

    private static CantonSets.Key zeroKey() {
        return new CantonSets.Key(new byte[32], 0, 0, new byte[32], new byte[32]);
    }

    private static BigInteger unsigned(final long v) {
        return new BigInteger(Long.toUnsignedString(v));
    }

    private static byte[] be32(final int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(final byte[] message) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(message);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A minimal growable big-endian byte writer. */
    private static final class CantonBuf {
        private byte[] buf = new byte[64];
        private int len = 0;

        private void ensure(final int extra) {
            if (len + extra > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
            }
        }

        void be32(final int v) {
            ensure(4);
            buf[len++] = (byte) (v >>> 24);
            buf[len++] = (byte) (v >>> 16);
            buf[len++] = (byte) (v >>> 8);
            buf[len++] = (byte) v;
        }

        void bytes(final byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, buf, len, b.length);
            len += b.length;
        }

        byte[] toBytes() {
            return Arrays.copyOf(buf, len);
        }
    }
}
