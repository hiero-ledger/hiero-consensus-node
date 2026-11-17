// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Synthetic-input tests for the rejection and edge branches of {@link CantonTopology} that the real Canton
 * vectors of {@link CantonTopologyTest} do not reach: multi-transaction signatures, restriction variants of a
 * namespace delegation, key filtering in an {@code OwnerToKeyMapping}, and malformed fingerprints and uids.
 */
class CantonTopologyCoverageTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] NAMESPACE = repeat((byte) 0x5a, 32);
    private static final byte[] ED_X = repeat((byte) 0x07, 32);

    // ── readTransaction ──────────────────────────────────────────────────────

    @Test
    void readTransactionRejectsAMappingThatIsNotAMessage() {
        final byte[] tx = versioned(new Pb()
                .varint(1, 1)
                .varint(2, 1)
                .field(3, new Pb().varint(4, 9).build())
                .build());
        final byte[] signed = versioned(new Pb().field(1, tx).build());
        assertMalformed(() -> CantonTopology.readTransaction(signed));
    }

    @Test
    void readTransactionSkipsSessionKeyAndMalformedFingerprintSignatures() {
        final byte[] tx = simpleTx();
        final byte[] good = sigItem(fp(NAMESPACE), new byte[] {1, 2, 3});
        final byte[] session = new Pb()
                .field(2, new byte[] {9})
                .field(3, fp(NAMESPACE))
                .field(5, new byte[] {1})
                .build();
        final byte[] badFp = sigItem("not-a-fingerprint".getBytes(StandardCharsets.UTF_8), new byte[] {4});
        final byte[] signed = versioned(new Pb()
                .field(1, tx)
                .field(2, good)
                .field(2, session)
                .field(2, badFp)
                .build());

        final CantonTopology.Transaction t = CantonTopology.readTransaction(signed);
        assertThat(t.signatures()).hasSize(1);
        assertThat(t.signatures().get(0).fingerprint()).isEqualTo(NAMESPACE);
        assertThat(t.signatures().get(0).signature()).containsExactly(1, 2, 3);
        assertThat(t.signatures().get(0).hash()).isEqualTo(t.hash());
    }

    @Test
    void readTransactionSignsAMultiTransactionOverTheSortedDistinctHashes() {
        final byte[] tx = simpleTx();
        final byte[] txHash = sha256(concat(new byte[] {0, 0, 0, 11}, tx));
        final byte[] low = repeat((byte) 0x00, 32);
        final byte[] high = repeat((byte) (0xff), 32);
        // Unsorted, with a duplicate, so the sort, the insertion shift and the de-duplication all run.
        final Pb multi = new Pb()
                .field(1, multihash(txHash))
                .field(1, multihash(high))
                .field(1, multihash(low))
                .field(1, multihash(high))
                .field(2, sigItem(fp(NAMESPACE), new byte[] {7}));
        final byte[] signed =
                versioned(new Pb().field(1, tx).field(4, multi.build()).build());

        final CantonTopology.Transaction t = CantonTopology.readTransaction(signed);

        final List<byte[]> sorted = new ArrayList<>(List.of(txHash, low, high));
        sorted.sort(Arrays::compareUnsigned);
        final ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(new byte[] {0, 0, 0, 55, 0, 0, 0, 3});
        for (final byte[] h : sorted) {
            expected.writeBytes(new byte[] {0, 0, 0, 34, 0x12, 0x20});
            expected.writeBytes(h);
        }
        assertThat(t.signatures()).hasSize(1);
        assertThat(t.signatures().get(0).hash()).isEqualTo(sha256(expected.toByteArray()));
    }

    @Test
    void readTransactionRejectsAMultiTransactionWithBadHashSets() {
        final byte[] tx = simpleTx();
        final byte[] txHash = sha256(concat(new byte[] {0, 0, 0, 11}, tx));
        final byte[] sig = sigItem(fp(NAMESPACE), new byte[] {7});

        // No hashes at all.
        final byte[] none = versioned(
                new Pb().field(1, tx).field(4, new Pb().field(2, sig).build()).build());
        assertMalformed(() -> CantonTopology.readTransaction(none));

        // More than MAX_MULTI_HASHES.
        final Pb tooMany = new Pb();
        tooMany.field(1, multihash(txHash));
        for (int i = 0; i < CantonTopology.MAX_MULTI_HASHES; ++i) {
            tooMany.field(1, multihash(repeat((byte) (i + 1), 32)));
        }
        final byte[] many = versioned(
                new Pb().field(1, tx).field(4, tooMany.field(2, sig).build()).build());
        assertMalformed(() -> CantonTopology.readTransaction(many));

        // Hashes that do not include this transaction's hash.
        final byte[] uncovered = versioned(new Pb()
                .field(1, tx)
                .field(
                        4,
                        new Pb()
                                .field(1, multihash(repeat((byte) 0x01, 32)))
                                .field(2, sig)
                                .build())
                .build());
        assertMalformed(() -> CantonTopology.readTransaction(uncovered));
    }

    // ── readLsuAnnouncement ──────────────────────────────────────────────────

    @Test
    void readLsuAnnouncementRejectsAnUpgradeTimeThatOverflowsUint64() {
        final byte[] time = new Pb().varint(1, Long.MAX_VALUE).varint(2, 0).build();
        final byte[] lsu = new Pb()
                .field(1, "succ".getBytes(StandardCharsets.UTF_8))
                .field(2, time)
                .build();
        assertMalformed(() -> CantonTopology.readLsuAnnouncement(lsu));
    }

    // ── readOwnerToKey ───────────────────────────────────────────────────────

    @Test
    void readOwnerToKeyRejectsAMemberThatIsNotAMediatorUid() {
        for (final String member : List.of("MED:", "XED::a", "MXD::a", "MEX::a", "MED;:a", "MED:;a")) {
            final byte[] b = new Pb().field(1, utf8(member)).build();
            assertMalformed(() -> CantonTopology.readOwnerToKey(b));
        }
    }

    @Test
    void readOwnerToKeyKeepsOnlyDecodableProtocolKeys() {
        final byte[] protocolKey = signingKey(1, 4, edDer(), new Pb().varint(5, 4));
        final byte[] packedProtocolKey = signingKey(1, 4, edDer(), new Pb().field(5, new byte[] {1, 4}));
        final byte[] noProtocolUsage = signingKey(1, 4, edDer(), new Pb().varint(5, 1));
        final byte[] badSpec = signingKey(99, 4, edDer(), new Pb().varint(5, 4));
        final byte[] b = new Pb()
                .field(1, utf8("MED::mediator"))
                .field(2, new Pb().field(1, protocolKey).build())
                .field(2, new byte[0]) // a PublicKey with no signing key
                .field(2, new Pb().field(1, noProtocolUsage).build())
                .field(2, new Pb().field(1, badSpec).build())
                .field(2, new Pb().field(1, packedProtocolKey).build())
                .build();

        final CantonTopology.OwnerToKey otk = CantonTopology.readOwnerToKey(b);
        assertThat(new String(otk.uid(), StandardCharsets.UTF_8)).isEqualTo("mediator");
        assertThat(otk.keys()).hasSize(2);
        assertThat(otk.keys().get(0).x()).isEqualTo(ED_X);
    }

    // ── readNamespaceDelegation ──────────────────────────────────────────────

    @Test
    void readNamespaceDelegationReadsEachRestriction() {
        final byte[] target = signingKey(1, 4, edDer(), new Pb().varint(5, 4));

        final CantonTopology.NamespaceDelegation all = CantonTopology.readNamespaceDelegation(
                delegation(target).field(4, new byte[0]).build());
        assertThat(all.ok()).isTrue();
        assertThat(all.restriction()).isZero();
        assertThat(all.mappings()).isZero();

        final CantonTopology.NamespaceDelegation butDelegations = CantonTopology.readNamespaceDelegation(
                delegation(target).field(5, new byte[0]).build());
        assertThat(butDelegations.restriction()).isEqualTo(1);

        // Legacy flag: is_root_delegation true is all, false is all-but-delegations.
        final CantonTopology.NamespaceDelegation root = CantonTopology.readNamespaceDelegation(
                delegation(target).varint(3, 1).build());
        assertThat(root.restriction()).isZero();
        final CantonTopology.NamespaceDelegation notRoot =
                CantonTopology.readNamespaceDelegation(delegation(target).build());
        assertThat(notRoot.restriction()).isEqualTo(1);
    }

    @Test
    void readNamespaceDelegationReadsSpecificMappingCodesPackedOrNot() {
        final byte[] target = signingKey(1, 4, edDer(), new Pb().varint(5, 4));
        // Unpacked varints (one in range, one out), a packed run (one in, one out), and a foreign field.
        final byte[] codes = new Pb()
                .varint(1, 4)
                .varint(1, 40)
                .field(1, new byte[] {12, 50})
                .varint(2, 7)
                .build();
        final CantonTopology.NamespaceDelegation d = CantonTopology.readNamespaceDelegation(
                delegation(target).field(6, codes).build());
        assertThat(d.restriction()).isEqualTo(2);
        assertThat(d.mappings()).isEqualTo((1L << 4) | (1L << 12));
    }

    @Test
    void readNamespaceDelegationRejectsMoreThanOneRestriction() {
        final byte[] target = signingKey(1, 4, edDer(), new Pb().varint(5, 4));
        final byte[] b =
                delegation(target).field(4, new byte[0]).field(5, new byte[0]).build();
        assertMalformed(() -> CantonTopology.readNamespaceDelegation(b));
    }

    // ── signingKey ───────────────────────────────────────────────────────────

    @Test
    void signingKeyDecodesEverySpecAndFlagsUnsupportedOnes() {
        final byte[] p256 = concat(CantonSets.DER_P256, repeat((byte) 0x01, 64));
        final byte[] k1 = concat(CantonSets.DER_SECP256K1, repeat((byte) 0x02, 64));

        final byte[] ed = signingKey(1, 4, edDer(), new Pb().varint(5, 4));
        assertThat(sk(ed).ok()).isTrue();
        assertThat(sk(ed).protocol()).isTrue();

        final byte[] p = signingKey(2, 4, p256, new Pb());
        assertThat(sk(p).ok()).isTrue();
        assertThat(sk(p).key().keySpec()).isEqualTo(CantonSets.SPEC_P256);
        assertThat(sk(p).protocol()).isFalse();

        final byte[] s = signingKey(4, 4, k1, new Pb());
        assertThat(sk(s).ok()).isTrue();
        assertThat(sk(s).key().keySpec()).isEqualTo(CantonSets.SPEC_SECP256K1);

        // An unknown key spec and a non-DER format are not checked by the verifier.
        assertThat(sk(signingKey(3, 4, edDer(), new Pb())).ok()).isFalse();
        assertThat(sk(signingKey(1, 3, edDer(), new Pb())).ok()).isFalse();
        // A key whose DER does not match its spec decodes as not ok.
        assertThat(sk(signingKey(1, 4, new byte[] {1, 2}, new Pb())).ok()).isFalse();
    }

    // ── namespaceOf / namespaceOfUid ─────────────────────────────────────────

    @Test
    void namespaceOfRejectsAnythingButA1220Fingerprint() {
        final String hex = "ab".repeat(32);
        assertThat(CantonTopology.namespaceOf(utf8("1220" + hex), whole("1220" + hex)))
                .isEqualTo(HEX.parseHex(hex));
        for (final String bad : List.of("2220" + hex, "1320" + hex, "1230" + hex, "1221" + hex, "1220" + "ab", "")) {
            final byte[] b = utf8(bad);
            assertMalformed(() -> CantonTopology.namespaceOf(b, new CantonProto.Range(0, b.length)));
        }
    }

    @Test
    void namespaceOfUidRequiresTheDoubleColonSeparator() {
        final String hex = "cd".repeat(32);
        assertThat(CantonTopology.namespaceOfUid(utf8("a::1220" + hex))).isEqualTo(HEX.parseHex(hex));
        for (final String bad : List.of("a:;1220" + hex, "a;:1220" + hex, "a::" + "1220" + hex.substring(2) + "xx")) {
            final byte[] uid = utf8(bad);
            assertThatThrownBy(() -> CantonTopology.namespaceOfUid(uid)).isInstanceOf(CantonProofException.class);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static CantonTopology.SigningKey sk(final byte[] b) {
        return CantonTopology.signingKey(b, new CantonProto.Range(0, b.length));
    }

    private static CantonProto.Range whole(final String s) {
        return new CantonProto.Range(0, s.length());
    }

    private static void assertMalformed(final org.junit.jupiter.api.function.Executable body) {
        assertThatThrownBy(body::execute)
                .isInstanceOfSatisfying(
                        CantonProofException.class,
                        e -> assertThat(e.errorName()).isEqualTo("CantonProtoMalformed"));
    }

    private static Pb delegation(final byte[] target) {
        return new Pb().field(1, fp(NAMESPACE)).field(2, target);
    }

    private static byte[] signingKey(final long spec, final long format, final byte[] der, final Pb usage) {
        final Pb pb = new Pb().varint(2, format).field(3, der);
        final byte[] u = usage.build();
        // Splice the usage field(s) in verbatim, then the key spec.
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(pb.build());
        out.writeBytes(u);
        out.writeBytes(new Pb().varint(6, spec).build());
        return out.toByteArray();
    }

    private static byte[] edDer() {
        return concat(CantonSets.DER_ED25519, ED_X);
    }

    /** A versioned (v30) transaction holding a LSU-shaped mapping body. */
    private static byte[] simpleTx() {
        return versioned(new Pb()
                .varint(1, 1)
                .varint(2, 1)
                .field(3, new Pb().field(1, new byte[] {1}).build())
                .build());
    }

    private static byte[] versioned(final byte[] data) {
        return new Pb().field(1, data).varint(2, CantonTopology.VERSION_V30).build();
    }

    private static byte[] sigItem(final byte[] fingerprint, final byte[] signature) {
        return new Pb().field(2, signature).field(3, fingerprint).build();
    }

    private static byte[] fp(final byte[] namespace) {
        return utf8("1220" + HEX.formatHex(namespace));
    }

    private static byte[] multihash(final byte[] digest) {
        return concat(new byte[] {0x12, 0x20}, digest);
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeat(final byte b, final int n) {
        final byte[] out = new byte[n];
        Arrays.fill(out, b);
        return out;
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(final byte[] m) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(m);
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal protobuf wire-format builder. */
    private static final class Pb {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb field(final int number, final byte[] value) {
            writeVarint(((long) number << 3) | CantonProto.WIRE_LEN);
            writeVarint(value.length);
            out.writeBytes(value);
            return this;
        }

        Pb varint(final int number, final long value) {
            writeVarint((long) number << 3);
            writeVarint(value);
            return this;
        }

        byte[] build() {
            return out.toByteArray();
        }

        private void writeVarint(long v) {
            while (true) {
                final int b = (int) (v & 0x7f);
                v >>>= 7;
                if (v != 0) {
                    out.write(b | 0x80);
                } else {
                    out.write(b);
                    return;
                }
            }
        }
    }
}
