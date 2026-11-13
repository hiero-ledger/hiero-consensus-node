// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CantonTopologyVerifier#applyRotations} against the real topology fixtures of a Canton network
 * (the eight signed transactions in {@code topology-rotations.hex}, and a real {@code LsuAnnouncement}).
 *
 * <p>REAL = bytes straight from the Canton fixtures. CONSTRUCTED = a real announcement checked under an anchor whose
 * synchronizer id was changed here to exercise the non-successor rejection, and the order tests that re-encode the
 * real transaction list with one transaction skipped or two swapped. No topology bytes are invented.
 */
class CantonTopologyVerifierTest {

    private static final HexFormat HEX = HexFormat.of();

    // Fixture constants from CantonTopologyFixtures.sol (pr-556).
    private static final String SYNCHRONIZER_ID =
            "probe::1220fe3624c1c2190638c3a9f88ec65abdb7b8a3908031d62dc2a0f65ed68c1e9922::35-0";
    private static final byte[] SYNCHRONIZER_NAMESPACE =
            HEX.parseHex("fe3624c1c2190638c3a9f88ec65abdb7b8a3908031d62dc2a0f65ed68c1e9922");
    private static final byte[] OWNER_SEQUENCER =
            HEX.parseHex("65971ff3bf94bdd46a9bab769b1c6e7e8e6a324031d3719586eb51c058e6d2a8");
    private static final byte[] OWNER_PARTICIPANT =
            HEX.parseHex("1a356465bbf4bc3a2e5f22fbb6cbc74ddb20169b68b60218d7c2edd94c053ca5");
    private static final byte[] MEDIATOR1 =
            HEX.parseHex("6e8899fea6adfe7b3965493fb60343bd44215fe4c695d02e6c8a761bd7bfa9bc");
    private static final byte[] MEDIATOR2 =
            HEX.parseHex("8e1a7f24091ce78a600a76759af67ac913bf3ff954e8eb8a166c5a1cb184892c");
    private static final byte[] MEDIATOR1_KEY_2 =
            HEX.parseHex("4318031c692a30b683240a94c7fea3bc3a439e64d1cbe4e1f2e9599ba2938dcc");
    private static final byte[] MEDIATOR1_P256 =
            HEX.parseHex("fe282f9c0c644fb7fd24f7029187d266a60edee684c0d2ad87cd34efcf4ba678");
    private static final byte[] MEDIATOR1_DELEGATED =
            HEX.parseHex("5191509ee4422119ae336fde0a17e775ea8540ee50c9a1f3904b04fbe42fa48b");
    private static final byte[] MEDIATOR2_KEY =
            HEX.parseHex("acc89538ec7fd9a5a0b31428b77ffdf69cea9c238b375531d8f5c18cbf45a16e");
    private static final byte[] OWNER_DELEGATED_P256 =
            HEX.parseHex("55f33859de079494a9ac84ad47db6a9e5bb911bee79131d29cd5b6faceff3d32");

    private static final String APP = "app::1220" + "ff".repeat(32);

    // ── helpers ──────────────────────────────────────────────────────────────

    private static byte[] anchor(
            final String synchronizerId, final byte[] namespace, final byte[] ownerSet, final byte[] mediatorSet) {
        return new CantonAnchor.Anchor(
                        synchronizerId,
                        1L,
                        0,
                        namespace,
                        MiscCryptoUtils.keccak256DigestOf(ownerSet),
                        MiscCryptoUtils.keccak256DigestOf(mediatorSet),
                        "",
                        0L,
                        0,
                        APP)
                .encode();
    }

    private static byte[] bytesOf(final CantonRlp.Slice s) {
        return Arrays.copyOfRange(s.buf(), s.off(), s.off() + s.len());
    }

    private static CantonSets.Member member(final CantonSets.Mediators m, final byte[] id) {
        return m.members().stream()
                .filter(x -> Arrays.equals(x.memberId(), id))
                .findFirst()
                .orElseThrow();
    }

    private static CantonSets.Owner owner(final CantonSets.Owners o, final byte[] ns) {
        return o.owners().stream()
                .filter(x -> Arrays.equals(x.namespace(), ns))
                .findFirst()
                .orElseThrow();
    }

    private static String errorOf(final org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        final var t = catchThrowable(c);
        assertThat(t).isInstanceOf(CantonProofException.class);
        return ((CantonProofException) t).errorName();
    }

    // ── Real rotations ─────────────────────────────────────────────────────────

    @Test
    void realRotationsFromCanton() {
        final byte[] ownerSet = CantonFixtures.topologyOwnerSet();
        final byte[] mediatorSet = CantonFixtures.topologyMediatorSet();
        final byte[] rotations = CantonFixtures.topologyRotations();

        final CantonTopologyVerifier.RotationResult r = CantonTopologyVerifier.applyRotations(
                anchor(SYNCHRONIZER_ID, SYNCHRONIZER_NAMESPACE, ownerSet, mediatorSet), mediatorSet, rotations);

        final CantonSets.Mediators m = CantonSets.decodeMediators(r.newMediatorSet());
        assertThat(m.serial()).isEqualTo(2);
        assertThat(m.threshold()).isEqualTo(1);
        assertThat(m.members()).hasSize(2);

        final CantonSets.Member one = member(m, MEDIATOR1);
        assertThat(one.otkSerial()).isEqualTo(4);
        assertThat(one.current()).hasSize(2);
        assertThat(one.current().get(0).fingerprint()).isEqualTo(MEDIATOR1_KEY_2);
        assertThat(one.current().get(1).fingerprint()).isEqualTo(MEDIATOR1_P256);
        assertThat(one.current().get(1).keySpec()).isEqualTo(CantonSets.SPEC_P256);
        assertThat(one.previous()).isEmpty();
        assertThat(one.delegations()).hasSize(1);
        assertThat(one.delegations().get(0).key().fingerprint()).isEqualTo(MEDIATOR1_DELEGATED);
        assertThat(one.delegations().get(0).restriction()).isEqualTo(2);
        assertThat(one.delegations().get(0).mappings()).isEqualTo(1L << 4);

        final CantonSets.Member two = member(m, MEDIATOR2);
        assertThat(two.otkSerial()).isEqualTo(1);
        assertThat(two.current()).hasSize(1);
        assertThat(two.current().get(0).fingerprint()).isEqualTo(MEDIATOR2_KEY);

        final CantonSets.Owners o = CantonSets.decodeOwners(r.newOwnerSet());
        assertThat(o.serial()).isEqualTo(2);
        assertThat(o.threshold()).isEqualTo(1);
        assertThat(o.owners()).hasSize(2);
        final CantonSets.Owner sequencer = owner(o, OWNER_SEQUENCER);
        assertThat(sequencer.delegations()).hasSize(1);
        assertThat(sequencer.delegations().get(0).key().fingerprint()).isEqualTo(OWNER_DELEGATED_P256);
        assertThat(sequencer.delegations().get(0).restriction()).isEqualTo(1);
        assertThat(owner(o, OWNER_PARTICIPANT).namespace()).isEqualTo(OWNER_PARTICIPANT);

        final CantonAnchor.Anchor out = CantonAnchor.Anchor.decode(r.newAnchor());
        assertThat(out.ownersHash()).isEqualTo(MiscCryptoUtils.keccak256DigestOf(r.newOwnerSet()));
        assertThat(out.mediatorsHash()).isEqualTo(MiscCryptoUtils.keccak256DigestOf(r.newMediatorSet()));
    }

    @Test
    void realRotationsMustComeInOrder() {
        final byte[] ownerSet = CantonFixtures.topologyOwnerSet();
        final byte[] mediatorSet = CantonFixtures.topologyMediatorSet();
        final byte[] a = anchor(SYNCHRONIZER_ID, SYNCHRONIZER_NAMESPACE, ownerSet, mediatorSet);

        final List<byte[]> items = items(CantonFixtures.topologyRotations());

        // Skip the removal that must precede the first key rotation: the next serial no longer follows.
        final List<byte[]> skipped = new ArrayList<>(items);
        skipped.remove(1);
        assertThat(errorOf(() -> CantonTopologyVerifier.applyRotations(a, mediatorSet, rebuild(ownerSet, skipped))))
                .isEqualTo("SerialMismatch");

        // Swap the mediator join and its keys: the keys name a member that has not joined yet.
        final List<byte[]> swapped = new ArrayList<>(items);
        final byte[] tmp = swapped.get(5);
        swapped.set(5, swapped.get(6));
        swapped.set(6, tmp);
        assertThat(errorOf(() -> CantonTopologyVerifier.applyRotations(a, mediatorSet, rebuild(ownerSet, swapped))))
                .isEqualTo("InvalidTopologyChange");
    }

    @Test
    void mediatorSetNotHashingToAnchorRejected() {
        final byte[] ownerSet = CantonFixtures.topologyOwnerSet();
        final byte[] mediatorSet = CantonFixtures.topologyMediatorSet();
        final byte[] a = anchor(SYNCHRONIZER_ID, SYNCHRONIZER_NAMESPACE, ownerSet, mediatorSet);
        final byte[] tampered = mediatorSet.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThat(errorOf(
                        () -> CantonTopologyVerifier.applyRotations(a, tampered, CantonFixtures.topologyRotations())))
                .isEqualTo("TrustSetMismatch");
    }

    @Test
    void ownerSetNotHashingToAnchorRejected() {
        final byte[] ownerSet = CantonFixtures.topologyOwnerSet();
        final byte[] mediatorSet = CantonFixtures.topologyMediatorSet();
        // Anchor commits to a different owners hash, so the owner set in the rotations fails the check.
        final byte[] a = new CantonAnchor.Anchor(
                        SYNCHRONIZER_ID,
                        1L,
                        0,
                        SYNCHRONIZER_NAMESPACE,
                        MiscCryptoUtils.keccak256DigestOf(new byte[] {1, 2, 3}),
                        MiscCryptoUtils.keccak256DigestOf(mediatorSet),
                        "",
                        0L,
                        0,
                        APP)
                .encode();
        assertThat(errorOf(() ->
                        CantonTopologyVerifier.applyRotations(a, mediatorSet, CantonFixtures.topologyRotations())))
                .isEqualTo("TrustSetMismatch");
    }

    // ── Real LSU announcement ──────────────────────────────────────────────────

    private static final String LSU_SYNCHRONIZER =
            "probe::122042b2d7afca2069ab5231b4384df922987122465472121b048f886edb5f8198c0::35-0";
    private static final byte[] LSU_NAMESPACE =
            HEX.parseHex("42b2d7afca2069ab5231b4384df922987122465472121b048f886edb5f8198c0");

    @Test
    void realLsuAnnouncementSetsThePendingUpgrade() {
        final byte[] ownerSet = CantonFixtures.lsuOwnerSet();
        final byte[] mediatorSet = CantonFixtures.topologyMediatorSet();
        final byte[] rotations = rebuild(
                ownerSet,
                List.of(Rlp.encodeList(List.of(
                        Rlp.encodeBytes(CantonFixtures.lsuAnnouncement()),
                        Rlp.encodeBytes(new byte[0]),
                        Rlp.encodeBytes(new byte[0])))));

        final CantonTopologyVerifier.RotationResult r = CantonTopologyVerifier.applyRotations(
                anchor(LSU_SYNCHRONIZER, LSU_NAMESPACE, ownerSet, mediatorSet), mediatorSet, rotations);

        final CantonAnchor.Anchor out = CantonAnchor.Anchor.decode(r.newAnchor());
        assertThat(out.successorSynchronizerId())
                .isEqualTo("probe::122042b2d7afca2069ab5231b4384df922987122465472121b048f886edb5f8198c0::35-1");
        assertThat(out.upgradeTime()).isEqualTo(1_893_456_000_000_000L);
        assertThat(out.lsuSerial()).isEqualTo(1);
    }

    @Test
    void lsuAnnouncementThatIsNotASuccessorRejected() {
        final byte[] ownerSet = CantonFixtures.lsuOwnerSet();
        final byte[] mediatorSet = CantonFixtures.topologyMediatorSet();
        final byte[] rotations = rebuild(
                ownerSet,
                List.of(Rlp.encodeList(List.of(
                        Rlp.encodeBytes(CantonFixtures.lsuAnnouncement()),
                        Rlp.encodeBytes(new byte[0]),
                        Rlp.encodeBytes(new byte[0])))));
        // Same real announcement, but the anchor names a synchronizer of a different logical id: not a successor.
        final byte[] a = anchor(
                "other::12201111111111111111111111111111111111111111111111111111111111111111::35-0",
                LSU_NAMESPACE,
                ownerSet,
                mediatorSet);
        assertThat(errorOf(() -> CantonTopologyVerifier.applyRotations(a, mediatorSet, rotations)))
                .isEqualTo("InvalidLsuAnnouncement");
    }

    // ── RLP helpers ────────────────────────────────────────────────────────────

    /** The raw bytes of each transaction item in {@code rotations}' second element. */
    private static List<byte[]> items(final byte[] rotations) {
        final List<CantonRlp.Slice> r = CantonRlp.readList(CantonRlp.of(rotations));
        final List<byte[]> out = new ArrayList<>();
        for (final CantonRlp.Slice item : CantonRlp.readList(r.get(1))) {
            out.add(bytesOf(item));
        }
        return out;
    }

    /** {@code RLP([ownerSet, RLP(encodedItems)])}; {@code encodedItems} are already-encoded rotation items. */
    private static byte[] rebuild(final byte[] ownerSet, final List<byte[]> encodedItems) {
        return Rlp.encodeList(List.of(Rlp.encodeBytes(ownerSet), Rlp.encodeList(encodedItems)));
    }
}
