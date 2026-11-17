// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.hiero.base.crypto.Ed25519;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CantonTopologyVerifier#applyRotations} on CONSTRUCTED topology: owner and mediator sets keyed by
 * fresh Ed25519 keys, and hand-built topology transactions that those keys really sign, so that every signature
 * verifies (or fails to) as it would on a real network. The real Canton vectors are in
 * {@link CantonTopologyVerifierTest}.
 */
class CantonTopologyVerifierSignedTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] NO_BYTES = new byte[0];

    private static final Signer OWNER_A = Signer.of(1);
    private static final Signer OWNER_B = Signer.of(2);
    private static final Signer OWNER_C = Signer.of(3);
    private static final Signer DELEGATE_1 = Signer.of(4);
    private static final Signer DELEGATE_2 = Signer.of(5);
    private static final Signer MEDIATOR_ROOT = Signer.of(6);
    private static final Signer KEY_OLD = Signer.of(7);
    private static final Signer KEY_NEW = Signer.of(8);

    private static final byte[] SYNC_NAMESPACE = repeat((byte) 0x11, 32);
    private static final String SYNC_LOGICAL = "sync::1220" + HEX.formatHex(SYNC_NAMESPACE);
    private static final String SYNC_ID = SYNC_LOGICAL + "::35-0";
    private static final String APP = "app::1220" + "ff".repeat(32);

    private static final long ADD = CantonTopology.OP_ADD_REPLACE;
    private static final long REMOVE = CantonTopology.OP_REMOVE;
    private static final long OTHER_OP = 3;

    private static final byte[] MEDIATOR_UID = utf8("med::1220" + HEX.formatHex(MEDIATOR_ROOT.ns()));

    // ── Decentralized namespace (owners) ─────────────────────────────────────

    @Test
    void ownersRemovalRejected() {
        final Rot r = rot(
                tx(REMOVE, 2, CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE, dn(SYNC_NAMESPACE, 1, OWNER_A), OWNER_A));
        assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("RotationsNotSupported");
    }

    @Test
    void ownersOfTheWrongShapeRejected() {
        final byte[] other = repeat((byte) 0x22, 32);
        for (final byte[] body : List.of(
                dn(other, 1, OWNER_A), // not the synchronizer's namespace
                dn(SYNC_NAMESPACE, 0, OWNER_A), // threshold zero
                dn(SYNC_NAMESPACE, 3, OWNER_A, OWNER_B))) { // threshold above the owners
            final Rot r = rot(tx(ADD, 2, CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE, body, OWNER_A));
            assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("InvalidTopologyChange");
        }
    }

    @Test
    void newOwnerWithoutItsKeyOrSignatureRejected() {
        final byte[] body = dn(SYNC_NAMESPACE, 1, OWNER_A, OWNER_B);
        // B's key is not offered.
        final Rot noKey = rot(tx(ADD, 2, CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE, body, OWNER_A));
        assertThat(error(owners(OWNER_A), mediators(), noKey)).isEqualTo("TopologyUnauthorized");
        // B's key is offered, but B did not sign.
        final Rot unsigned = rot(
                tx(ADD, 2, CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE, body, OWNER_A), List.of(OWNER_B), NO_BYTES);
        assertThat(error(owners(OWNER_A), mediators(), unsigned)).isEqualTo("TopologyUnauthorized");
    }

    @Test
    void ownerNamedTwiceRejected() {
        final Rot r = rot(tx(
                ADD,
                2,
                CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE,
                dn(SYNC_NAMESPACE, 1, OWNER_A, OWNER_A),
                OWNER_A));
        assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("InvalidTopologyChange");
    }

    @Test
    void ownersGrowAndStaySortedByNamespace() {
        // Descending input, so that every insertion shifts; B and C are new and sign for themselves.
        final List<Signer> desc = new ArrayList<>(List.of(OWNER_A, OWNER_B, OWNER_C));
        desc.sort((x, y) -> Arrays.compareUnsigned(y.ns(), x.ns()));
        final Rot r = rot(
                tx(
                        ADD,
                        2,
                        CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE,
                        dn(SYNC_NAMESPACE, 2, desc.toArray(new Signer[0])),
                        OWNER_A,
                        OWNER_B,
                        OWNER_C),
                List.of(OWNER_B, OWNER_C),
                NO_BYTES);
        final CantonTopologyVerifier.RotationResult res = run(owners(OWNER_A), mediators(), r);

        final CantonSets.Owners out = CantonSets.decodeOwners(res.newOwnerSet());
        assertThat(out.serial()).isEqualTo(2);
        assertThat(out.threshold()).isEqualTo(2);
        final List<Signer> asc = new ArrayList<>(desc);
        asc.sort((x, y) -> Arrays.compareUnsigned(x.ns(), y.ns()));
        assertThat(out.owners().stream().map(CantonSets.Owner::namespace).toList())
                .usingElementComparator(Arrays::compare)
                .containsExactlyElementsOf(asc.stream().map(Signer::ns).toList());
    }

    // ── Owner namespace delegations ──────────────────────────────────────────

    @Test
    void ownerDelegationOfAnUnknownNamespaceOrByANonRootRejected() {
        final Rot unknown =
                rot(tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_B.ns(), DELEGATE_1, 4), OWNER_B));
        assertThat(error(owners(OWNER_A), mediators(), unknown)).isEqualTo("InvalidTopologyChange");

        final Rot notRoot = rot(
                tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 4), DELEGATE_1));
        assertThat(error(owners(OWNER_A), mediators(), notRoot)).isEqualTo("TopologyUnauthorized");
    }

    @Test
    void delegationOfTheNamespaceKeyOrAnUndecodableKeyRejected() {
        final Rot self =
                rot(tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), OWNER_A, 4), OWNER_A));
        assertThat(error(owners(OWNER_A), mediators(), self)).isEqualTo("RotationsNotSupported");

        final byte[] undecodable = new Pb()
                .field(1, fp(OWNER_A.ns()))
                .field(
                        2,
                        new Pb()
                                .varint(2, 4)
                                .field(3, new byte[] {1})
                                .varint(6, 1)
                                .build())
                .field(4, NO_BYTES)
                .build();
        final Rot bad = rot(tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, undecodable, OWNER_A));
        assertThat(error(owners(OWNER_A), mediators(), bad)).isEqualTo("RotationsNotSupported");
    }

    @Test
    void removalOfAnUnknownDelegationRejected() {
        final Rot r = rot(
                tx(REMOVE, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 4), OWNER_A));
        assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("InvalidTopologyChange");
    }

    @Test
    void unsupportedOperationOnADelegationRejected() {
        final Rot add =
                rot(tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 4), OWNER_A));
        final Rot other = rot(tx(
                OTHER_OP, 2, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 4), OWNER_A));
        assertThat(error(owners(OWNER_A), mediators(), add, other)).isEqualTo("RotationsNotSupported");
    }

    @Test
    void ownerDelegationsAreAddedChangedAndRemoved() {
        final long lsuBit = 1L << CantonTopology.CODE_LSU_ANNOUNCEMENT;
        final Rot addD1 =
                rot(tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 5), OWNER_A));
        final Rot addD2 =
                rot(tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_2, 4), OWNER_A));
        // D2 is changed to the LSU announcement only; D1, which comes first in the list, is skipped over.
        final Rot changeD2 =
                rot(tx(ADD, 2, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_2, 6), OWNER_A));
        final Rot removeD1 = rot(
                tx(REMOVE, 2, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 5), OWNER_A));

        final CantonTopologyVerifier.RotationResult res =
                run(owners(OWNER_A), mediators(), addD1, addD2, changeD2, removeD1);

        final List<CantonSets.Delegation> d =
                CantonSets.decodeOwners(res.newOwnerSet()).owners().getFirst().delegations();
        assertThat(d).hasSize(2);
        assertThat(d.get(0).key().fingerprint()).isEqualTo(DELEGATE_1.ns());
        assertThat(d.get(0).removed()).isTrue();
        assertThat(d.get(0).serial()).isEqualTo(2);
        assertThat(d.get(1).key().fingerprint()).isEqualTo(DELEGATE_2.ns());
        assertThat(d.get(1).removed()).isFalse();
        assertThat(d.get(1).restriction()).isEqualTo(2);
        assertThat(d.get(1).mappings()).isEqualTo(lsuBit);
    }

    // ── Logical synchronizer upgrade ─────────────────────────────────────────

    private static Rot lsu(final long op, final long serial, final String successor, final Signer... signers) {
        return rot(tx(op, serial, CantonTopology.MAPPING_LSU_ANNOUNCEMENT, lsuBody(successor), signers));
    }

    private static String lsuError(final String currentId, final String successor) {
        return error(
                anchorOf(currentId, "", 0, 0, owners(OWNER_A), mediators()),
                owners(OWNER_A),
                mediators(),
                lsu(ADD, 1, successor, OWNER_A));
    }

    @Test
    void lsuSuccessorsAreOrderedBySerialThenProtocolVersion() {
        for (final String successor :
                List.of(SYNC_LOGICAL + "::35-1", SYNC_LOGICAL + "::36-0", SYNC_LOGICAL + "::36-1")) {
            final CantonTopologyVerifier.RotationResult res =
                    run(owners(OWNER_A), mediators(), lsu(ADD, 1, successor, OWNER_A));
            final CantonAnchor.Anchor out = CantonAnchor.Anchor.decode(res.newAnchor());
            assertThat(out.successorSynchronizerId()).isEqualTo(successor);
            assertThat(out.upgradeTime()).isEqualTo(1_700_000_000_000_000L);
            assertThat(out.lsuSerial()).isEqualTo(1);
        }
    }

    @Test
    void lsuSuccessorsThatDoNotSucceedRejected() {
        for (final String successor : List.of(
                SYNC_ID, // the same physical id
                SYNC_LOGICAL + "::34-1", // protocol version goes down
                "other::1220" + "11".repeat(32) + "::35-1", // another logical synchronizer
                SYNC_LOGICAL.replace("sync", "synd") + "::35-1", // same length of logical id, other bytes
                "x", // no separator
                "::35-1", // empty logical id
                SYNC_LOGICAL + "::35", // no serial
                SYNC_LOGICAL + "::3a-1", // protocol version not a number
                SYNC_LOGICAL + "::-1", // protocol version empty
                SYNC_LOGICAL + "::35-", // serial empty
                SYNC_LOGICAL + "::35-1x", // serial not a number
                SYNC_LOGICAL + "::35-1234567890")) { // serial of ten digits
            assertThat(lsuError(SYNC_ID, successor)).as(successor).isEqualTo("InvalidLsuAnnouncement");
        }
        // A greater protocol version does not make up for a lower serial.
        assertThat(lsuError(SYNC_LOGICAL + "::35-2", SYNC_LOGICAL + "::36-1")).isEqualTo("InvalidLsuAnnouncement");
        // The current id is malformed.
        assertThat(lsuError("not-a-physical-id", SYNC_LOGICAL + "::35-1")).isEqualTo("InvalidLsuAnnouncement");
    }

    @Test
    void lsuAnnouncementOverAPendingOneMustHaveAGreaterSerial() {
        final byte[] a = anchorOf(SYNC_ID, SYNC_LOGICAL + "::35-1", 1_000L, 5, owners(OWNER_A), mediators());
        assertThat(error(a, owners(OWNER_A), mediators(), lsu(ADD, 5, SYNC_LOGICAL + "::35-2", OWNER_A)))
                .isEqualTo("InvalidLsuAnnouncement");

        final CantonTopologyVerifier.RotationResult res =
                run(a, owners(OWNER_A), mediators(), lsu(ADD, 6, SYNC_LOGICAL + "::35-2", OWNER_A));
        final CantonAnchor.Anchor out = CantonAnchor.Anchor.decode(res.newAnchor());
        assertThat(out.successorSynchronizerId()).isEqualTo(SYNC_LOGICAL + "::35-2");
        assertThat(out.lsuSerial()).isEqualTo(6);
    }

    @Test
    void lsuAnnouncementRemovalCancelsAPendingUpgradeOnly() {
        final byte[] pending = anchorOf(SYNC_ID, SYNC_LOGICAL + "::35-1", 1_000L, 5, owners(OWNER_A), mediators());
        final CantonTopologyVerifier.RotationResult res =
                run(pending, owners(OWNER_A), mediators(), lsu(REMOVE, 6, SYNC_LOGICAL + "::35-1", OWNER_A));
        final CantonAnchor.Anchor out = CantonAnchor.Anchor.decode(res.newAnchor());
        assertThat(out.successorSynchronizerId()).isEmpty();
        assertThat(out.upgradeTime()).isZero();
        assertThat(out.lsuSerial()).isZero();

        assertThat(error(owners(OWNER_A), mediators(), lsu(REMOVE, 1, SYNC_LOGICAL + "::35-1", OWNER_A)))
                .isEqualTo("InvalidLsuAnnouncement");
    }

    @Test
    void lsuAnnouncementWithAnotherOperationOrAHugeSerialRejected() {
        assertThat(error(owners(OWNER_A), mediators(), lsu(OTHER_OP, 1, SYNC_LOGICAL + "::35-1", OWNER_A)))
                .isEqualTo("InvalidLsuAnnouncement");
        assertThat(error(owners(OWNER_A), mediators(), lsu(ADD, 0x1_0000_0000L, SYNC_LOGICAL + "::35-1", OWNER_A)))
                .isEqualTo("InvalidLsuAnnouncement");
    }

    @Test
    void lsuAnnouncementAuthorizedByDelegatesOfTheRightRestriction() {
        final CantonSets.Key d1 = DELEGATE_1.key();
        final CantonSets.Key d2 = DELEGATE_2.key();
        final long lsuBit = 1L << CantonTopology.CODE_LSU_ANNOUNCEMENT;
        final byte[] ownerSet = CantonSets.encodeOwners(new CantonSets.Owners(
                1,
                1,
                List.of(new CantonSets.Owner(
                        OWNER_A.ns(),
                        OWNER_A.key(),
                        List.of(
                                new CantonSets.Delegation(d1, 2, 0, 1, false), // not LSU announcements
                                new CantonSets.Delegation(d2, 2, lsuBit, 1, false))))));
        // D1 holds no right to this mapping.
        assertThat(error(ownerSet, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", DELEGATE_1)))
                .isEqualTo("TopologyUnauthorized");
        // D2 does.
        final CantonTopologyVerifier.RotationResult res =
                run(ownerSet, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", DELEGATE_2));
        assertThat(CantonAnchor.Anchor.decode(res.newAnchor()).successorSynchronizerId())
                .isEqualTo(SYNC_LOGICAL + "::35-1");
    }

    @Test
    void lsuAnnouncementAuthorizedByDelegatesOfEveryOpenRestriction() {
        for (final int restriction : new int[] {0, 1}) {
            final byte[] ownerSet = CantonSets.encodeOwners(new CantonSets.Owners(
                    1,
                    1,
                    List.of(new CantonSets.Owner(
                            OWNER_A.ns(),
                            OWNER_A.key(),
                            List.of(
                                    new CantonSets.Delegation(DELEGATE_1.key(), 0, 0, 1, true), // removed
                                    new CantonSets.Delegation(DELEGATE_2.key(), restriction, 0, 1, false))))));
            assertThat(error(ownerSet, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", DELEGATE_1)))
                    .isEqualTo("TopologyUnauthorized");
            final CantonTopologyVerifier.RotationResult res =
                    run(ownerSet, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", DELEGATE_2));
            assertThat(CantonAnchor.Anchor.decode(res.newAnchor()).lsuSerial()).isEqualTo(1);
        }
    }

    // ── Mediator state ───────────────────────────────────────────────────────

    private static byte[] uidOf(final String name) {
        return utf8(name + "::1220" + HEX.formatHex(MEDIATOR_ROOT.ns()));
    }

    private static Rot mediatorState(
            final long op,
            final long serial,
            final byte[] logical,
            final long group,
            final long threshold,
            final List<byte[]> active) {
        return rot(tx(
                op, serial, CantonTopology.MAPPING_MEDIATOR_STATE, msBody(logical, group, threshold, active), OWNER_A));
    }

    @Test
    void mediatorStateOfAnotherOperationOrShapeRejected() {
        final byte[] logical = utf8(SYNC_LOGICAL);
        final List<byte[]> one = List.of(MEDIATOR_UID);
        assertThat(error(owners(OWNER_A), mediators(), mediatorState(OTHER_OP, 2, logical, 0, 1, one)))
                .isEqualTo("RotationsNotSupported");
        for (final Rot r : List.of(
                mediatorState(ADD, 2, utf8("other::1220" + "11".repeat(32)), 0, 1, one), // another synchronizer
                mediatorState(ADD, 2, logical, 1, 1, one), // another group
                mediatorState(ADD, 2, logical, 0, 0, one), // threshold zero
                mediatorState(ADD, 2, logical, 0, 2, one))) { // threshold above the members
            assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("InvalidTopologyChange");
        }
    }

    @Test
    void mediatorStateOnAMalformedSynchronizerIdRejected() {
        final byte[] a = anchorOf("not-a-physical-id", "", 0, 0, owners(OWNER_A), mediators());
        final Rot r = mediatorState(ADD, 2, utf8(SYNC_LOGICAL), 0, 1, List.of(MEDIATOR_UID));
        assertThat(error(a, owners(OWNER_A), mediators(), r)).isEqualTo("InvalidTopologyChange");
    }

    @Test
    void mediatorNamedTwiceRejected() {
        final Rot r = mediatorState(ADD, 2, utf8(SYNC_LOGICAL), 0, 1, List.of(MEDIATOR_UID, MEDIATOR_UID));
        assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("InvalidTopologyChange");
    }

    @Test
    void mediatorStateReplacesTheMembersSortedById() {
        final List<byte[]> uids = new ArrayList<>(List.of(MEDIATOR_UID, uidOf("m2"), uidOf("m3")));
        uids.sort((x, y) -> Arrays.compareUnsigned(sha256(y), sha256(x))); // descending, so every insertion shifts
        final Rot r = mediatorState(ADD, 2, utf8(SYNC_LOGICAL), 0, 2, uids);
        final CantonTopologyVerifier.RotationResult res = run(owners(OWNER_A), mediators(), r);

        final CantonSets.Mediators out = CantonSets.decodeMediators(res.newMediatorSet());
        assertThat(out.serial()).isEqualTo(2);
        assertThat(out.threshold()).isEqualTo(2);
        final List<byte[]> ascending = new ArrayList<>(
                uids.stream().map(CantonTopologyVerifierSignedTest::sha256).toList());
        ascending.sort(Arrays::compareUnsigned);
        assertThat(out.members().stream().map(CantonSets.Member::memberId).toList())
                .usingElementComparator(Arrays::compare)
                .containsExactlyElementsOf(ascending);
        // The member that was already there keeps its keys.
        final CantonSets.Member kept = out.members().stream()
                .filter(m -> Arrays.equals(m.memberId(), sha256(MEDIATOR_UID)))
                .findFirst()
                .orElseThrow();
        assertThat(kept.current()).hasSize(1);
    }

    // ── Mediator keys ────────────────────────────────────────────────────────

    private static Rot otk(
            final long op,
            final long serial,
            final List<Signer> keys,
            final List<Signer> signers,
            final List<Signer> offered) {
        return rot(
                tx(
                        op,
                        serial,
                        CantonTopology.MAPPING_OWNER_TO_KEY,
                        otkBody(MEDIATOR_UID, keys),
                        signers.toArray(new Signer[0])),
                offered,
                NO_BYTES);
    }

    @Test
    void mediatorKeysRotateWhenTheRootSignsAndNewKeysSignForThemselves() {
        // Add a key: the old one stays, the new one signs for itself.
        final CantonTopologyVerifier.RotationResult added = run(
                owners(OWNER_A),
                mediators(),
                otk(ADD, 2, List.of(KEY_OLD, KEY_NEW), List.of(MEDIATOR_ROOT, KEY_NEW), List.of(MEDIATOR_ROOT)));
        CantonSets.Member m =
                CantonSets.decodeMediators(added.newMediatorSet()).members().getFirst();
        assertThat(m.current()).hasSize(2);
        assertThat(m.previous()).isEmpty();
        assertThat(m.otkSerial()).isEqualTo(2);

        // Replace it: the old key moves to the previous keys.
        final CantonTopologyVerifier.RotationResult replaced = run(
                owners(OWNER_A),
                mediators(),
                otk(ADD, 2, List.of(KEY_NEW), List.of(MEDIATOR_ROOT, KEY_NEW), List.of(MEDIATOR_ROOT)));
        m = CantonSets.decodeMediators(replaced.newMediatorSet()).members().getFirst();
        assertThat(m.current().stream().map(CantonSets.Key::fingerprint).toList())
                .usingElementComparator(Arrays::compare)
                .containsExactly(KEY_NEW.ns());
        assertThat(m.previous().stream().map(CantonSets.Key::fingerprint).toList())
                .usingElementComparator(Arrays::compare)
                .containsExactly(KEY_OLD.ns());
    }

    @Test
    void mediatorKeyThatDidNotSignRejected() {
        final Rot r = otk(ADD, 2, List.of(KEY_OLD, KEY_NEW), List.of(MEDIATOR_ROOT), List.of(MEDIATOR_ROOT));
        assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("TopologyUnauthorized");
    }

    @Test
    void mediatorKeysRemovedAndUnsupportedOperation() {
        final CantonTopologyVerifier.RotationResult res = run(
                owners(OWNER_A),
                mediators(),
                otk(REMOVE, 2, List.of(), List.of(MEDIATOR_ROOT), List.of(MEDIATOR_ROOT)));
        final CantonSets.Member m =
                CantonSets.decodeMediators(res.newMediatorSet()).members().getFirst();
        assertThat(m.current()).isEmpty();
        assertThat(m.previous()).isEmpty();
        assertThat(m.otkSerial()).isEqualTo(2);

        final Rot other = otk(OTHER_OP, 2, List.of(), List.of(MEDIATOR_ROOT), List.of(MEDIATOR_ROOT));
        assertThat(error(owners(OWNER_A), mediators(), other)).isEqualTo("RotationsNotSupported");
    }

    @Test
    void mediatorKeysSignedByADelegateOfTheMember() {
        final long otkBit = 1L << CantonTopology.CODE_OWNER_TO_KEY;
        final CantonSets.Member member = new CantonSets.Member(
                sha256(MEDIATOR_UID),
                1,
                List.of(
                        new CantonSets.Delegation(DELEGATE_1.key(), 2, otkBit, 1, true), // removed
                        new CantonSets.Delegation(DELEGATE_2.key(), 2, otkBit, 1, false)),
                List.of(KEY_OLD.key()),
                List.of());
        final byte[] mediatorSet = CantonSets.encodeMediators(new CantonSets.Mediators(1, 1, List.of(member)));

        // The removed delegate may not sign, and nor may a stranger.
        assertThat(error(owners(OWNER_A), mediatorSet, otk(ADD, 2, List.of(KEY_OLD), List.of(DELEGATE_1), List.of())))
                .isEqualTo("TopologyUnauthorized");
        // The live delegate may, with no root key at all.
        final CantonTopologyVerifier.RotationResult res = run(
                owners(OWNER_A),
                mediatorSet,
                otk(ADD, 2, List.of(KEY_OLD, KEY_NEW), List.of(DELEGATE_2, KEY_NEW), List.of()));
        assertThat(CantonSets.decodeMediators(res.newMediatorSet())
                        .members()
                        .getFirst()
                        .current())
                .hasSize(2);
    }

    // ── Mediator delegations ─────────────────────────────────────────────────

    private static Rot mediatorDelegation(
            final byte[] namespace, final byte[] uid, final List<Signer> signers, final List<Signer> offered) {
        return rot(
                tx(
                        ADD,
                        1,
                        CantonTopology.MAPPING_NAMESPACE_DELEGATION,
                        nsd(namespace, DELEGATE_1, 4),
                        signers.toArray(new Signer[0])),
                offered,
                uid);
    }

    @Test
    void mediatorDelegationRejections() {
        // For another namespace than the mediator's.
        assertThat(error(
                        owners(OWNER_A),
                        mediators(),
                        mediatorDelegation(OWNER_B.ns(), MEDIATOR_UID, List.of(MEDIATOR_ROOT), List.of(MEDIATOR_ROOT))))
                .isEqualTo("InvalidTopologyChange");
        // For a mediator that is not in the set.
        final byte[] stranger = uidOf("stranger");
        assertThat(error(
                        owners(OWNER_A),
                        mediators(),
                        mediatorDelegation(
                                MEDIATOR_ROOT.ns(), stranger, List.of(MEDIATOR_ROOT), List.of(MEDIATOR_ROOT))))
                .isEqualTo("InvalidTopologyChange");
        // Not signed by the root of the mediator's namespace.
        assertThat(error(
                        owners(OWNER_A),
                        mediators(),
                        mediatorDelegation(
                                MEDIATOR_ROOT.ns(), MEDIATOR_UID, List.of(DELEGATE_1), List.of(MEDIATOR_ROOT))))
                .isEqualTo("TopologyUnauthorized");
    }

    @Test
    void mediatorDelegationAdded() {
        final CantonTopologyVerifier.RotationResult res = run(
                owners(OWNER_A),
                mediators(),
                mediatorDelegation(MEDIATOR_ROOT.ns(), MEDIATOR_UID, List.of(MEDIATOR_ROOT), List.of(MEDIATOR_ROOT)));
        final List<CantonSets.Delegation> d = CantonSets.decodeMediators(res.newMediatorSet())
                .members()
                .getFirst()
                .delegations();
        assertThat(d).hasSize(1);
        assertThat(d.getFirst().key().fingerprint()).isEqualTo(DELEGATE_1.ns());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** An Ed25519 key pair and its Canton key. */
    private record Signer(byte[] secret, byte[] pub, CantonSets.Key key) {
        static Signer of(final int seed) {
            final byte[] s = new byte[Ed25519.SEED_BYTES];
            Arrays.fill(s, (byte) seed);
            final byte[] pub = new byte[Ed25519.PUBLIC_KEY_BYTES];
            final byte[] secret = new byte[Ed25519.SECRET_KEY_BYTES];
            Ed25519.newKeypair(pub, secret, s);
            final CantonSets.Key key = CantonSets.keyFromDer(
                            CantonSets.SPEC_ED25519, concat(CantonSets.DER_ED25519, pub))
                    .key();
            return new Signer(secret, pub, key);
        }

        byte[] ns() {
            return key.fingerprint();
        }

        byte[] sign(final byte[] message) {
            final byte[] sig = new byte[Ed25519.SIGNATURE_BYTES];
            Ed25519.sign(sig, message, secret);
            return sig;
        }
    }

    /** A rotation item: the signed transaction, the signer keys it offers, and the mediator uid. */
    private record Rot(byte[] signedTx, List<Signer> offered, byte[] uid) {}

    private static Rot rot(final byte[] signedTx) {
        return rot(signedTx, List.of(), NO_BYTES);
    }

    private static Rot rot(final byte[] signedTx, final List<Signer> offered, final byte[] uid) {
        return new Rot(signedTx, offered, uid);
    }

    private static byte[] encode(final Rot r) {
        final byte[] offered = r.offered().isEmpty()
                ? Rlp.encodeBytes(NO_BYTES)
                : Rlp.encodeList(r.offered().stream()
                        .map(s -> Rlp.encodeBytes(CantonSets.encodeKey(s.key())))
                        .toList());
        return Rlp.encodeList(List.of(Rlp.encodeBytes(r.signedTx()), offered, Rlp.encodeBytes(r.uid())));
    }

    private static byte[] owners(final Signer... roots) {
        final List<Signer> sorted = new ArrayList<>(List.of(roots));
        sorted.sort((x, y) -> Arrays.compareUnsigned(x.ns(), y.ns()));
        return CantonSets.encodeOwners(new CantonSets.Owners(
                1,
                1,
                sorted.stream()
                        .map(s -> new CantonSets.Owner(s.ns(), s.key(), List.of()))
                        .toList()));
    }

    /** One mediator, {@link #MEDIATOR_UID}, holding {@link #KEY_OLD}, at serial 1. */
    private static byte[] mediators() {
        return CantonSets.encodeMediators(new CantonSets.Mediators(
                1,
                1,
                List.of(new CantonSets.Member(sha256(MEDIATOR_UID), 1, List.of(), List.of(KEY_OLD.key()), List.of()))));
    }

    private static byte[] anchorOf(
            final String id,
            final String successor,
            final long upgradeTime,
            final int lsuSerial,
            final byte[] ownerSet,
            final byte[] mediatorSet) {
        return new CantonAnchor.Anchor(
                        id,
                        1L,
                        0,
                        SYNC_NAMESPACE,
                        MiscCryptoUtils.keccak256DigestOf(ownerSet),
                        MiscCryptoUtils.keccak256DigestOf(mediatorSet),
                        successor,
                        upgradeTime,
                        lsuSerial,
                        APP)
                .encode();
    }

    private static CantonTopologyVerifier.RotationResult run(
            final byte[] ownerSet, final byte[] mediatorSet, final Rot... rots) {
        return run(anchorOf(SYNC_ID, "", 0, 0, ownerSet, mediatorSet), ownerSet, mediatorSet, rots);
    }

    private static CantonTopologyVerifier.RotationResult run(
            final byte[] anchor, final byte[] ownerSet, final byte[] mediatorSet, final Rot... rots) {
        final List<byte[]> items = new ArrayList<>();
        for (final Rot r : rots) {
            items.add(encode(r));
        }
        final byte[] rotations = Rlp.encodeList(List.of(Rlp.encodeBytes(ownerSet), Rlp.encodeList(items)));
        return CantonTopologyVerifier.applyRotations(anchor, mediatorSet, rotations);
    }

    private static String error(final byte[] ownerSet, final byte[] mediatorSet, final Rot... rots) {
        return error(anchorOf(SYNC_ID, "", 0, 0, ownerSet, mediatorSet), ownerSet, mediatorSet, rots);
    }

    private static String error(
            final byte[] anchor, final byte[] ownerSet, final byte[] mediatorSet, final Rot... rots) {
        final Throwable t = catchThrowable(() -> run(anchor, ownerSet, mediatorSet, rots));
        assertThat(t).isInstanceOf(CantonProofException.class);
        return ((CantonProofException) t).errorName();
    }

    // ── Topology bytes ───────────────────────────────────────────────────────

    /** A versioned signed topology transaction with a one-field mapping, signed by each of {@code signers}. */
    private static byte[] tx(
            final long op, final long serial, final long mappingKind, final byte[] body, final Signer... signers) {
        final byte[] txBytes = versioned(new Pb()
                .varint(1, op)
                .varint(2, serial)
                .field(3, new Pb().field((int) mappingKind, body).build())
                .build());
        final byte[] hash = sha256(concat(new byte[] {0, 0, 0, CantonTopology.PURPOSE_TOPOLOGY_TRANSACTION}, txBytes));
        final byte[] message = concat(new byte[] {0x12, 0x20}, hash);
        final Pb signed = new Pb().field(1, txBytes);
        for (final Signer s : signers) {
            signed.field(
                    2, new Pb().field(2, s.sign(message)).field(3, fp(s.ns())).build());
        }
        return versioned(signed.build());
    }

    private static byte[] dn(final byte[] namespace, final long threshold, final Signer... owners) {
        final Pb pb = new Pb().field(1, fp(namespace)).varint(2, threshold);
        for (final Signer o : owners) {
            pb.field(3, fp(o.ns()));
        }
        return pb.build();
    }

    /** A namespace delegation to {@code target}; {@code restriction} is the field number 4, 5 or 6 that sets it. */
    private static byte[] nsd(final byte[] namespace, final Signer target, final int restriction) {
        final Pb pb = new Pb().field(1, fp(namespace)).field(2, signingKey(target));
        if (restriction == 6) {
            return pb.field(
                            6,
                            new Pb()
                                    .varint(1, CantonTopology.CODE_LSU_ANNOUNCEMENT)
                                    .build())
                    .build();
        }
        return pb.field(restriction, NO_BYTES).build();
    }

    private static byte[] lsuBody(final String successor) {
        return new Pb()
                .field(1, utf8(successor))
                .field(2, new Pb().varint(1, 1_700_000_000L).varint(2, 0).build())
                .build();
    }

    private static byte[] msBody(
            final byte[] logical, final long group, final long threshold, final List<byte[]> active) {
        final Pb pb = new Pb().field(1, logical).varint(2, group).varint(3, threshold);
        for (final byte[] a : active) {
            pb.field(4, a);
        }
        return pb.build();
    }

    private static byte[] otkBody(final byte[] uid, final List<Signer> keys) {
        final Pb pb = new Pb().field(1, concat(utf8("MED::"), uid));
        for (final Signer k : keys) {
            pb.field(2, new Pb().field(1, signingKey(k)).build());
        }
        return pb.build();
    }

    /** A {@code SigningPublicKey}: DER format, Ed25519, usage {@code PROTOCOL}. */
    private static byte[] signingKey(final Signer s) {
        return new Pb()
                .varint(2, 4)
                .field(3, concat(CantonSets.DER_ED25519, s.pub()))
                .varint(5, 4)
                .varint(6, 1)
                .build();
    }

    private static byte[] versioned(final byte[] data) {
        return new Pb().field(1, data).varint(2, CantonTopology.VERSION_V30).build();
    }

    private static byte[] fp(final byte[] namespace) {
        return utf8("1220" + HEX.formatHex(namespace));
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
