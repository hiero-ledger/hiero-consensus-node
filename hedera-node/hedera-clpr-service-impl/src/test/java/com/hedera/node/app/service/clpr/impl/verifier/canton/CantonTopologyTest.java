// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CantonTopology}: reading real Canton signed topology transactions, verifying their
 * signatures, and decoding each topology mapping.
 *
 * <p>All vectors are REAL Canton bytes. The signed transactions come from {@code topology-rotations.hex} (eight
 * rotations of a Canton 3.5.19 network: a mediator's key rotation, an added P-256 key, mediator and owner
 * namespace delegations, a mediator joining the group, and a second owner). {@code lsu-announcement.hex} and
 * {@code topology-tx-largest.hex} are real single transactions. Expected decoded fields (fingerprints, serials,
 * mapping kinds, the LSU time) are cross-checked against the Solidity oracle {@code CantonTopologyFixtures.sol}
 * and {@code CantonTopologyVerifier.t.sol} (ref {@code pr-556}).
 */
class CantonTopologyTest {

    private static final HexFormat HEX = HexFormat.of();

    // From CantonTopologyFixtures.sol (ref pr-556).
    private static final byte[] MEDIATOR1_KEY_1 =
            hex("47d7e478d17269b47ac84ed799101a84156ac084b2b186ee31c91cf7a1e67b40");
    private static final byte[] MEDIATOR1_KEY_2 =
            hex("4318031c692a30b683240a94c7fea3bc3a439e64d1cbe4e1f2e9599ba2938dcc");
    private static final byte[] MEDIATOR1_P256 =
            hex("fe282f9c0c644fb7fd24f7029187d266a60edee684c0d2ad87cd34efcf4ba678");
    private static final byte[] MEDIATOR1_DELEGATED =
            hex("5191509ee4422119ae336fde0a17e775ea8540ee50c9a1f3904b04fbe42fa48b");
    private static final byte[] OWNER_DELEGATED_P256 =
            hex("55f33859de079494a9ac84ad47db6a9e5bb911bee79131d29cd5b6faceff3d32");
    private static final byte[] MEDIATOR2_KEY = hex("acc89538ec7fd9a5a0b31428b77ffdf69cea9c238b375531d8f5c18cbf45a16e");
    private static final byte[] SYNC_NAMESPACE =
            hex("fe3624c1c2190638c3a9f88ec65abdb7b8a3908031d62dc2a0f65ed68c1e9922");
    private static final byte[] MEDIATOR1_NAMESPACE =
            hex("c7cc5c9aa3791386b0bc13d1b57fd2c3d17a7dec0c9d11836612d8dc904abb49");

    private static byte[] hex(final String s) {
        return HEX.parseHex(s);
    }

    /** The eight real signed topology transactions, in order, extracted from {@code topology-rotations.hex}. */
    private static List<byte[]> rotationTransactions() {
        final List<CantonRlp.Slice> top = CantonRlp.readList(CantonRlp.of(CantonFixtures.topologyRotations()));
        final List<CantonRlp.Slice> items = CantonRlp.readList(top.get(1));
        final List<byte[]> out = new ArrayList<>();
        for (final CantonRlp.Slice item : items) {
            // Each rotation item is RLP[signedTransaction, signerKeys, mediatorUid].
            out.add(CantonRlp.readBytes(CantonRlp.readList(item).get(0)));
        }
        return out;
    }

    // ── readTransaction ──────────────────────────────────────────────────────

    @Test
    void readTransactionExtractsMappingKindOperationAndSerial() {
        final List<byte[]> txs = rotationTransactions();
        assertThat(txs).hasSize(8);

        final CantonTopology.Transaction t0 = CantonTopology.readTransaction(txs.get(0));
        assertThat(t0.operation()).isEqualTo(CantonTopology.OP_ADD_REPLACE);
        assertThat(t0.serial()).isEqualTo(2L);
        assertThat(t0.mappingKind()).isEqualTo(CantonTopology.MAPPING_OWNER_TO_KEY);
        assertThat(t0.hash()).hasSize(32);
        assertThat(t0.signatures()).hasSize(2);

        // The mapping kinds of all eight rotations, in order (see the fixture's documented order).
        final long[] kinds = txs.stream()
                .mapToLong(b -> CantonTopology.readTransaction(b).mappingKind())
                .toArray();
        assertThat(kinds)
                .containsExactly(
                        CantonTopology.MAPPING_OWNER_TO_KEY,
                        CantonTopology.MAPPING_OWNER_TO_KEY,
                        CantonTopology.MAPPING_OWNER_TO_KEY,
                        CantonTopology.MAPPING_NAMESPACE_DELEGATION,
                        CantonTopology.MAPPING_NAMESPACE_DELEGATION,
                        CantonTopology.MAPPING_MEDIATOR_STATE,
                        CantonTopology.MAPPING_OWNER_TO_KEY,
                        CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE);
    }

    @Test
    void readTransactionReadsTheLargestRealTransaction() {
        final CantonTopology.Transaction t = CantonTopology.readTransaction(CantonFixtures.topologyTxLargest());
        assertThat(t.serial()).isEqualTo(2L);
        assertThat(t.hash()).hasSize(32);
        assertThat(t.signatures()).isNotEmpty();
    }

    // ── signedBy ─────────────────────────────────────────────────────────────

    @Test
    void signedByAcceptsARealSignatureAndRejectsATamperedOne() {
        final CantonTopology.Transaction t =
                CantonTopology.readTransaction(rotationTransactions().get(0));
        // The new Ed25519 key (MEDIATOR1_KEY_2) both appears in the mapping and signed the transaction.
        final CantonSets.Key key = CantonTopology.readOwnerToKey(t.body()).keys().stream()
                .filter(k -> java.util.Arrays.equals(k.fingerprint(), MEDIATOR1_KEY_2))
                .findFirst()
                .orElseThrow();

        assertThat(CantonTopology.signedBy(t, key)).isTrue();

        // A key whose fingerprint matches no signature is simply not a signer.
        final CantonSets.Key stranger =
                new CantonSets.Key(MEDIATOR1_P256.clone(), CantonSets.SPEC_ED25519, 0, key.x(), key.y());
        assertThat(CantonTopology.signedBy(t, stranger)).isFalse();

        // A matching fingerprint whose signature does not verify must throw.
        final CantonTopology.Signature good = t.signatures().stream()
                .filter(s -> java.util.Arrays.equals(s.fingerprint(), MEDIATOR1_KEY_2))
                .findFirst()
                .orElseThrow();
        final byte[] tampered = good.signature().clone();
        tampered[0] ^= 0x01;
        final CantonTopology.Transaction forged = new CantonTopology.Transaction(
                t.operation(),
                t.serial(),
                t.mappingKind(),
                t.body(),
                t.hash(),
                List.of(new CantonTopology.Signature(MEDIATOR1_KEY_2.clone(), good.hash(), tampered)));
        assertThatThrownBy(() -> CantonTopology.signedBy(forged, key))
                .isInstanceOfSatisfying(
                        CantonProofException.class,
                        e -> assertThat(e.errorName()).isEqualTo("InvalidTopologySignature"));
    }

    // ── Mapping readers ──────────────────────────────────────────────────────

    @Test
    void readOwnerToKeyReadsTheMediatorKeyRotation() {
        final CantonTopology.Transaction t0 =
                CantonTopology.readTransaction(rotationTransactions().get(0));
        final CantonTopology.OwnerToKey otk = CantonTopology.readOwnerToKey(t0.body());

        assertThat(new String(otk.uid(), StandardCharsets.UTF_8))
                .isEqualTo("mediator1::1220" + HEX.formatHex(MEDIATOR1_NAMESPACE));
        assertThat(otk.keys()).hasSize(2);
        assertThat(otk.keys().get(0).fingerprint()).isEqualTo(MEDIATOR1_KEY_1);
        assertThat(otk.keys().get(1).fingerprint()).isEqualTo(MEDIATOR1_KEY_2);

        // The added P-256 key (third rotation), fingerprint over its DER.
        final CantonTopology.Transaction t2 =
                CantonTopology.readTransaction(rotationTransactions().get(2));
        final List<CantonSets.Key> keys =
                CantonTopology.readOwnerToKey(t2.body()).keys();
        final CantonSets.Key p256 = keys.get(keys.size() - 1);
        assertThat(p256.keySpec()).isEqualTo(CantonSets.SPEC_P256);
        assertThat(p256.fingerprint()).isEqualTo(MEDIATOR1_P256);
        assertThat(p256.fingerprintMode()).isEqualTo(1);
        assertThat(CantonSets.hasOwnFingerprint(p256)).isTrue();
    }

    @Test
    void readNamespaceDelegationReadsMediatorAndOwnerDelegations() {
        // The mediator delegates a key restricted to OwnerToKeyMapping (code 4, so mask 1<<4 == 16).
        final CantonTopology.Transaction t3 =
                CantonTopology.readTransaction(rotationTransactions().get(3));
        final CantonTopology.NamespaceDelegation med = CantonTopology.readNamespaceDelegation(t3.body());
        assertThat(med.ok()).isTrue();
        assertThat(med.namespace()).isEqualTo(MEDIATOR1_NAMESPACE);
        assertThat(med.target().fingerprint()).isEqualTo(MEDIATOR1_DELEGATED);
        assertThat(med.restriction()).isEqualTo(2);
        assertThat(med.mappings()).isEqualTo(1L << CantonTopology.CODE_OWNER_TO_KEY);

        // The owner delegates a P-256 key restricted to all-but-namespace-delegations (restriction 1).
        final CantonTopology.Transaction t4 =
                CantonTopology.readTransaction(rotationTransactions().get(4));
        final CantonTopology.NamespaceDelegation owner = CantonTopology.readNamespaceDelegation(t4.body());
        assertThat(owner.ok()).isTrue();
        assertThat(owner.target().fingerprint()).isEqualTo(OWNER_DELEGATED_P256);
        assertThat(owner.restriction()).isEqualTo(1);
        assertThat(owner.mappings()).isZero();
    }

    @Test
    void readMediatorStateReadsTheGroupChange() {
        final CantonTopology.Transaction t5 =
                CantonTopology.readTransaction(rotationTransactions().get(5));
        final CantonTopology.MediatorState ms = CantonTopology.readMediatorState(t5.body());
        assertThat(ms.group()).isEqualTo(0L);
        assertThat(ms.threshold()).isEqualTo(1L);
        assertThat(ms.active()).hasSize(2);
        assertThat(ms.synchronizerId().length()).isGreaterThan(0);
    }

    @Test
    void readOwnerToKeyReadsMediatorTwosKeys() {
        final CantonTopology.Transaction t6 =
                CantonTopology.readTransaction(rotationTransactions().get(6));
        final CantonTopology.OwnerToKey otk = CantonTopology.readOwnerToKey(t6.body());
        assertThat(new String(otk.uid(), StandardCharsets.UTF_8)).startsWith("mediator2::1220");
        assertThat(otk.keys()).hasSize(1);
        assertThat(otk.keys().get(0).fingerprint()).isEqualTo(MEDIATOR2_KEY);
    }

    @Test
    void readDecentralizedNamespaceReadsTheSecondOwner() {
        final CantonTopology.Transaction t7 =
                CantonTopology.readTransaction(rotationTransactions().get(7));
        final CantonTopology.DecentralizedNamespace dn = CantonTopology.readDecentralizedNamespace(t7.body());
        assertThat(dn.namespace()).isEqualTo(SYNC_NAMESPACE);
        assertThat(dn.threshold()).isEqualTo(1L);
        assertThat(dn.owners()).hasSize(2);
    }

    @Test
    void readLsuAnnouncementReadsTheSuccessorAndUpgradeTime() {
        final CantonTopology.Transaction tx = CantonTopology.readTransaction(CantonFixtures.lsuAnnouncement());
        assertThat(tx.mappingKind()).isEqualTo(CantonTopology.MAPPING_LSU_ANNOUNCEMENT);

        final CantonTopology.LsuAnnouncement lsu = CantonTopology.readLsuAnnouncement(tx.body());
        assertThat(new String(lsu.successor(), StandardCharsets.UTF_8))
                .isEqualTo("probe::122042b2d7afca2069ab5231b4384df922987122465472121b048f886edb5f8198c0::35-1");
        // 2030-01-01T00:00:00Z in microseconds.
        assertThat(lsu.upgradeTime()).isEqualTo(1_893_456_000_000_000L);
    }

    // ── namespaceOf / namespaceOfUid ─────────────────────────────────────────

    @Test
    void namespaceOfUidReadsTheNamespaceFromAUid() {
        final CantonTopology.Transaction t0 =
                CantonTopology.readTransaction(rotationTransactions().get(0));
        final byte[] uid = CantonTopology.readOwnerToKey(t0.body()).uid();
        assertThat(CantonTopology.namespaceOfUid(uid)).isEqualTo(MEDIATOR1_NAMESPACE);
    }

    @Test
    void namespaceOfUidRejectsAUidWithoutANamespaceSuffix() {
        assertThatThrownBy(() -> CantonTopology.namespaceOfUid("short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(
                        CantonProofException.class,
                        e -> assertThat(e.errorName()).isEqualTo("CantonProtoMalformed"));
    }
}
