// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.be32;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.repeat;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.sha256;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.utf8;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.MEDIATOR1_DELEGATED;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.MEDIATOR1_KEY_2;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.MEDIATOR1_P256;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.MEDIATOR2_KEY;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.OWNER_DELEGATED_P256;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.OWNER_SEQUENCER;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.SYNCHRONIZER_NAMESPACE;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.formatHex;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.fp;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.hex;
import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.versionedV30;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonFixtures.Pb;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hiero.base.crypto.Ed25519;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CantonTopologyVerifier#applyRotations} against the real topology fixtures of a Canton network
 * (the eight signed transactions in {@code topology-rotations.hex}, and a real {@code LsuAnnouncement}), and
 * against CONSTRUCTED topology: owner and mediator sets keyed by fresh Ed25519 keys, and hand-built topology
 * transactions that those keys really sign, so that every signature verifies (or fails to) as it would on a real
 * network.
 *
 * <p>REAL = bytes straight from the Canton fixtures. CONSTRUCTED = a real announcement checked under an anchor whose
 * synchronizer id was changed here to exercise the non-successor rejection, and the order tests that re-encode the
 * real transaction list with one transaction skipped or two swapped. No topology bytes are invented.
 */
class CantonTopologyVerifierTest {

    // Fixture constants from reference implementation
    private static final String SYNCHRONIZER_ID =
            "probe::1220fe3624c1c2190638c3a9f88ec65abdb7b8a3908031d62dc2a0f65ed68c1e9922::35-0";
    private static final byte[] OWNER_PARTICIPANT =
            hex("1a356465bbf4bc3a2e5f22fbb6cbc74ddb20169b68b60218d7c2edd94c053ca5");
    private static final byte[] MEDIATOR2 = hex("8e1a7f24091ce78a600a76759af67ac913bf3ff954e8eb8a166c5a1cb184892c");

    private static final String APP = "app::1220" + "ff".repeat(32);
    private static final String LSU_SYNCHRONIZER =
            "probe::122042b2d7afca2069ab5231b4384df922987122465472121b048f886edb5f8198c0::35-0";
    private static final byte[] LSU_NAMESPACE = hex("42b2d7afca2069ab5231b4384df922987122465472121b048f886edb5f8198c0");

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
    private static final String SYNC_LOGICAL = "sync::1220" + formatHex(SYNC_NAMESPACE);
    private static final String SYNC_ID = SYNC_LOGICAL + "::35-0";

    private static final long ADD = CantonTopology.OP_ADD_REPLACE;
    private static final long REMOVE = CantonTopology.OP_REMOVE;
    private static final long OTHER_OP = 3;

    private static final byte[] MEDIATOR_UID = utf8("med::1220" + formatHex(MEDIATOR_ROOT.ns()));

    @Nested
    class RealRotations {

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

            final CantonSets.Member one = member(m, CantonFixtures.MEDIATOR1);
            assertThat(one.otkSerial()).isEqualTo(4);
            assertThat(one.current()).hasSize(2);
            assertThat(one.current().get(0).fingerprint()).isEqualTo(MEDIATOR1_KEY_2);
            assertThat(one.current().get(1).fingerprint()).isEqualTo(MEDIATOR1_P256);
            assertThat(one.current().get(1).keySpec()).isEqualTo(CantonSets.SPEC_P256);
            assertThat(one.previous()).isEmpty();
            assertThat(one.delegations()).hasSize(1);
            assertThat(one.delegations().getFirst().key().fingerprint()).isEqualTo(MEDIATOR1_DELEGATED);
            assertThat(one.delegations().getFirst().restriction()).isEqualTo(2);
            assertThat(one.delegations().getFirst().mappings()).isEqualTo(1L << 4);

            final CantonSets.Member two = member(m, MEDIATOR2);
            assertThat(two.otkSerial()).isEqualTo(1);
            assertThat(two.current()).hasSize(1);
            assertThat(two.current().getFirst().fingerprint()).isEqualTo(MEDIATOR2_KEY);

            final CantonSets.Owners o = CantonSets.decodeOwners(r.newOwnerSet());
            assertThat(o.serial()).isEqualTo(2);
            assertThat(o.threshold()).isEqualTo(1);
            assertThat(o.owners()).hasSize(2);
            final CantonSets.Owner sequencer = owner(o, OWNER_SEQUENCER);
            assertThat(sequencer.delegations()).hasSize(1);
            assertThat(sequencer.delegations().getFirst().key().fingerprint()).isEqualTo(OWNER_DELEGATED_P256);
            assertThat(sequencer.delegations().getFirst().restriction()).isEqualTo(1);
            assertThat(owner(o, OWNER_PARTICIPANT).namespace()).isEqualTo(OWNER_PARTICIPANT);

            final CantonAnchor.Anchor out = r.newAnchor();
            assertThat(out.ownersHash()).isEqualTo(MiscCryptoUtils.keccak256DigestOf(r.newOwnerSet()));
            assertThat(out.mediatorsHash()).isEqualTo(MiscCryptoUtils.keccak256DigestOf(r.newMediatorSet()));
        }

        @Test
        void realRotationsMustComeInOrder() {
            final byte[] ownerSet = CantonFixtures.topologyOwnerSet();
            final byte[] mediatorSet = CantonFixtures.topologyMediatorSet();
            final CantonAnchor.Anchor a = anchor(SYNCHRONIZER_ID, SYNCHRONIZER_NAMESPACE, ownerSet, mediatorSet);

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
        void ownerSetNotHashingToAnchorRejected() {
            final byte[] ownerSet = CantonFixtures.topologyOwnerSet();
            final byte[] mediatorSet = CantonFixtures.topologyMediatorSet();
            final CantonAnchor.Anchor a = anchor(SYNCHRONIZER_ID, SYNCHRONIZER_NAMESPACE, ownerSet, mediatorSet);
            // Keep the real rotation items but replace the embedded owner set with a tampered copy.
            final byte[] tampered = ownerSet.clone();
            tampered[tampered.length - 1] ^= 1;
            final byte[] rotations = rebuild(tampered, items(CantonFixtures.topologyRotations()));
            assertThat(errorOf(() -> CantonTopologyVerifier.applyRotations(a, mediatorSet, rotations)))
                    .isEqualTo("TrustSetMismatch");
        }
    }

    /** Each rotation is authorized against the sets that the rotations before it left behind. */
    @Nested
    class RotationsBuildOnEachOther {
        private final String successor = SYNC_LOGICAL + "::35-1";

        @Test
        void ownersGrownByARotationAreHeldToTheNewThreshold() {
            // OWNER_A alone grows the set to {A, B} at threshold 2; B is new and signs for itself.
            final Rot grow = rot(
                    tx(
                            ADD,
                            2,
                            CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE,
                            dn(SYNC_NAMESPACE, 2, OWNER_A, OWNER_B),
                            OWNER_A,
                            OWNER_B),
                    List.of(OWNER_B),
                    NO_BYTES);

            // The announcement that follows is judged by the grown set, so A alone no longer carries it.
            assertThat(error(owners(OWNER_A), mediators(), grow, lsu(ADD, 1, successor, OWNER_A)))
                    .isEqualTo("TopologyUnauthorized");
            final CantonTopologyVerifier.RotationResult res =
                    run(owners(OWNER_A), mediators(), grow, lsu(ADD, 1, successor, OWNER_A, OWNER_B));
            assertThat(res.newAnchor().successorSynchronizerId()).isEqualTo(successor);
        }

        @Test
        void mediatorDelegationAddedByARotationAuthorizesTheNextRotation() {
            final Rot delegate = mediatorDelegation(
                    MEDIATOR_ROOT.ns(), MEDIATOR_UID, List.of(MEDIATOR_ROOT), List.of(MEDIATOR_ROOT));
            // The delegate signs the key rotation, with no root key at all.
            final Rot rotateKeys = otk(ADD, 2, List.of(KEY_OLD, KEY_NEW), List.of(DELEGATE_1, KEY_NEW), List.of());

            // Without the delegation the delegate holds no right to it.
            assertThat(error(owners(OWNER_A), mediators(), rotateKeys)).isEqualTo("TopologyUnauthorized");
            final CantonTopologyVerifier.RotationResult res = run(owners(OWNER_A), mediators(), delegate, rotateKeys);
            assertThat(CantonSets.decodeMediators(res.newMediatorSet())
                            .members()
                            .getFirst()
                            .current())
                    .hasSize(2);
        }

        /**
         * A guard against future shared state, not against a present bug: the rotation state is immutable and the
         * RLP byte strings are copied out of the input, so nothing today can make this fail except a deliberate write
         * to an argument. It would catch a later change that caches decoded sets, or lets a rotation write into the
         * caller's bytes.
         */
        @Test
        void rotationsNeitherChangeTheirInputsNorDependOnEarlierCalls() {
            final byte[] ownerSet = CantonFixtures.topologyOwnerSet();
            final byte[] mediatorSet = CantonFixtures.topologyMediatorSet();
            final byte[] rotations = CantonFixtures.topologyRotations();
            final CantonAnchor.Anchor trustAnchor =
                    anchor(SYNCHRONIZER_ID, SYNCHRONIZER_NAMESPACE, ownerSet, mediatorSet);
            final byte[] anchorBefore = trustAnchor.encode();
            final byte[] mediatorsBefore = mediatorSet.clone();
            final byte[] rotationsBefore = rotations.clone();

            final CantonTopologyVerifier.RotationResult first =
                    CantonTopologyVerifier.applyRotations(trustAnchor, mediatorSet, rotations);
            final CantonTopologyVerifier.RotationResult second =
                    CantonTopologyVerifier.applyRotations(trustAnchor, mediatorSet, rotations);

            assertThat(trustAnchor.encode()).isEqualTo(anchorBefore);
            assertThat(mediatorSet).isEqualTo(mediatorsBefore);
            assertThat(rotations).isEqualTo(rotationsBefore);
            assertThat(second.newAnchor().encode()).isEqualTo(first.newAnchor().encode());
            assertThat(second.newMediatorSet()).isEqualTo(first.newMediatorSet());
            assertThat(second.newOwnerSet()).isEqualTo(first.newOwnerSet());
        }
    }

    @Nested
    class RealLsuAnnouncement {

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

            final CantonAnchor.Anchor out = r.newAnchor();
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
            final CantonAnchor.Anchor a = anchor(
                    "other::12201111111111111111111111111111111111111111111111111111111111111111::35-0",
                    LSU_NAMESPACE,
                    ownerSet,
                    mediatorSet);
            assertThat(errorOf(() -> CantonTopologyVerifier.applyRotations(a, mediatorSet, rotations)))
                    .isEqualTo("InvalidLsuAnnouncement");
        }
    }

    @Nested
    class DecentralizedNamespaceOwners {
        @Test
        void ownersRemovalRejected() {
            final Rot r = rot(tx(
                    REMOVE,
                    2,
                    CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE,
                    dn(SYNC_NAMESPACE, 1, OWNER_A),
                    OWNER_A));
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
                    tx(ADD, 2, CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE, body, OWNER_A),
                    List.of(OWNER_B),
                    NO_BYTES);
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
    }

    @Nested
    class OwnerNamespaceDelegations {
        @Test
        void ownerDelegationOfAnUnknownNamespaceOrByANonRootRejected() {
            final Rot unknown = rot(
                    tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_B.ns(), DELEGATE_1, 4), OWNER_B));
            assertThat(error(owners(OWNER_A), mediators(), unknown)).isEqualTo("InvalidTopologyChange");

            final Rot notRoot = rot(tx(
                    ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 4), DELEGATE_1));
            assertThat(error(owners(OWNER_A), mediators(), notRoot)).isEqualTo("TopologyUnauthorized");
        }

        @Test
        void delegationOfTheNamespaceKeyOrAnUndecodableKeyRejected() {
            final Rot self = rot(
                    tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), OWNER_A, 4), OWNER_A));
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
            final Rot r = rot(tx(
                    REMOVE, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 4), OWNER_A));
            assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("InvalidTopologyChange");
        }

        @Test
        void unsupportedOperationOnADelegationRejected() {
            final Rot add = rot(
                    tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 4), OWNER_A));
            final Rot other = rot(tx(
                    OTHER_OP,
                    2,
                    CantonTopology.MAPPING_NAMESPACE_DELEGATION,
                    nsd(OWNER_A.ns(), DELEGATE_1, 4),
                    OWNER_A));
            assertThat(error(owners(OWNER_A), mediators(), add, other)).isEqualTo("RotationsNotSupported");
        }

        @Test
        void ownerDelegationsAreAddedChangedAndRemoved() {
            final long lsuBit = 1L << CantonTopology.CODE_LSU_ANNOUNCEMENT;
            final Rot addD1 = rot(
                    tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 5), OWNER_A));
            final Rot addD2 = rot(
                    tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_2, 4), OWNER_A));
            // D2 is changed to the LSU announcement only; D1, which comes first in the list, is skipped over.
            final Rot changeD2 = rot(
                    tx(ADD, 2, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_2, 6), OWNER_A));
            final Rot removeD1 = rot(tx(
                    REMOVE, 2, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 5), OWNER_A));

            final CantonTopologyVerifier.RotationResult res =
                    run(owners(OWNER_A), mediators(), addD1, addD2, changeD2, removeD1);

            final List<CantonSets.Delegation> d = CantonSets.decodeOwners(res.newOwnerSet())
                    .owners()
                    .getFirst()
                    .delegations();
            assertThat(d).hasSize(2);
            assertThat(d.get(0).key().fingerprint()).isEqualTo(DELEGATE_1.ns());
            assertThat(d.get(0).removed()).isTrue();
            assertThat(d.get(0).serial()).isEqualTo(2);
            assertThat(d.get(1).key().fingerprint()).isEqualTo(DELEGATE_2.ns());
            assertThat(d.get(1).removed()).isFalse();
            assertThat(d.get(1).restriction()).isEqualTo(2);
            assertThat(d.get(1).mappings()).isEqualTo(lsuBit);
        }
    }

    @Nested
    class LogicalSynchronizerUpgrade {
        @Test
        void lsuSuccessorsAreOrderedBySerialThenProtocolVersion() {
            for (final String successor :
                    List.of(SYNC_LOGICAL + "::35-1", SYNC_LOGICAL + "::36-0", SYNC_LOGICAL + "::36-1")) {
                final CantonTopologyVerifier.RotationResult res =
                        run(owners(OWNER_A), mediators(), lsu(ADD, 1, successor, OWNER_A));
                final CantonAnchor.Anchor out = res.newAnchor();
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
            assertThat(lsuError(SYNC_LOGICAL + "::35-2", SYNC_LOGICAL + "::36-1"))
                    .isEqualTo("InvalidLsuAnnouncement");
            // The current id is malformed.
            assertThat(lsuError("not-a-physical-id", SYNC_LOGICAL + "::35-1")).isEqualTo("InvalidLsuAnnouncement");
        }

        @Test
        void lsuAnnouncementOverAPendingOneMustHaveAGreaterSerial() {
            final CantonAnchor.Anchor a =
                    anchorOf(SYNC_ID, SYNC_LOGICAL + "::35-1", 1_000L, 5, owners(OWNER_A), mediators());
            assertThat(error(a, owners(OWNER_A), mediators(), lsu(ADD, 5, SYNC_LOGICAL + "::35-2", OWNER_A)))
                    .isEqualTo("InvalidLsuAnnouncement");

            final CantonTopologyVerifier.RotationResult res =
                    run(a, owners(OWNER_A), mediators(), lsu(ADD, 6, SYNC_LOGICAL + "::35-2", OWNER_A));
            final CantonAnchor.Anchor out = res.newAnchor();
            assertThat(out.successorSynchronizerId()).isEqualTo(SYNC_LOGICAL + "::35-2");
            assertThat(out.lsuSerial()).isEqualTo(6);
        }

        @Test
        void lsuAnnouncementRemovalCancelsAPendingUpgradeOnly() {
            final CantonAnchor.Anchor pending =
                    anchorOf(SYNC_ID, SYNC_LOGICAL + "::35-1", 1_000L, 5, owners(OWNER_A), mediators());
            final CantonTopologyVerifier.RotationResult res =
                    run(pending, owners(OWNER_A), mediators(), lsu(REMOVE, 6, SYNC_LOGICAL + "::35-1", OWNER_A));
            final CantonAnchor.Anchor out = res.newAnchor();
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
            final byte[] ownerSet = (new CantonSets.Owners(
                            1,
                            1,
                            List.of(new CantonSets.Owner(
                                    OWNER_A.ns(),
                                    OWNER_A.key(),
                                    List.of(
                                            new CantonSets.Delegation(d1, 2, 0, 1, false), // not LSU announcements
                                            new CantonSets.Delegation(d2, 2, lsuBit, 1, false))))))
                    .encode();
            // D1 holds no right to this mapping.
            assertThat(error(ownerSet, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", DELEGATE_1)))
                    .isEqualTo("TopologyUnauthorized");
            // D2 does.
            final CantonTopologyVerifier.RotationResult res =
                    run(ownerSet, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", DELEGATE_2));
            assertThat(res.newAnchor().successorSynchronizerId()).isEqualTo(SYNC_LOGICAL + "::35-1");
        }

        @Test
        void lsuAnnouncementAuthorizedByDelegatesOfEveryOpenRestriction() {
            for (final int restriction : new int[] {0, 1}) {
                final byte[] ownerSet = (new CantonSets.Owners(
                                1,
                                1,
                                List.of(new CantonSets.Owner(
                                        OWNER_A.ns(),
                                        OWNER_A.key(),
                                        List.of(
                                                new CantonSets.Delegation(DELEGATE_1.key(), 0, 0, 1, true), // removed
                                                new CantonSets.Delegation(
                                                        DELEGATE_2.key(), restriction, 0, 1, false))))))
                        .encode();
                assertThat(error(ownerSet, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", DELEGATE_1)))
                        .isEqualTo("TopologyUnauthorized");
                final CantonTopologyVerifier.RotationResult res =
                        run(ownerSet, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", DELEGATE_2));
                assertThat(res.newAnchor().lsuSerial()).isEqualTo(1);
            }
        }
    }

    @Nested
    class MediatorState {
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
            final CantonAnchor.Anchor a = anchorOf("not-a-physical-id", "", 0, 0, owners(OWNER_A), mediators());
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
            final List<byte[]> ascending =
                    new ArrayList<>(uids.stream().map(CantonBytes::sha256).toList());
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
    }

    @Nested
    class MediatorKeys {
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
            final byte[] mediatorSet = (new CantonSets.Mediators(1, 1, List.of(member))).encode();

            // The removed delegate may not sign, and nor may a stranger.
            assertThat(error(
                            owners(OWNER_A),
                            mediatorSet,
                            otk(ADD, 2, List.of(KEY_OLD), List.of(DELEGATE_1), List.of())))
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
    }

    @Nested
    class MediatorDelegations {
        @Test
        void mediatorDelegationRejections() {
            // For another namespace than the mediator's.
            assertThat(error(
                            owners(OWNER_A),
                            mediators(),
                            mediatorDelegation(
                                    OWNER_B.ns(), MEDIATOR_UID, List.of(MEDIATOR_ROOT), List.of(MEDIATOR_ROOT))))
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
                    mediatorDelegation(
                            MEDIATOR_ROOT.ns(), MEDIATOR_UID, List.of(MEDIATOR_ROOT), List.of(MEDIATOR_ROOT)));
            final List<CantonSets.Delegation> d = CantonSets.decodeMediators(res.newMediatorSet())
                    .members()
                    .getFirst()
                    .delegations();
            assertThat(d).hasSize(1);
            assertThat(d.getFirst().key().fingerprint()).isEqualTo(DELEGATE_1.ns());
        }
    }

    @Nested
    class OwnerThreshold {
        private final byte[] twoOfTwo = ownersWithThreshold(2, OWNER_A, OWNER_B);

        @Test
        void lsuAnnouncementBelowTheThresholdRejected() {
            assertThat(error(twoOfTwo, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", OWNER_A)))
                    .isEqualTo("TopologyUnauthorized");
        }

        @Test
        void lsuAnnouncementAtTheThresholdAccepted() {
            final CantonTopologyVerifier.RotationResult res =
                    run(twoOfTwo, mediators(), lsu(ADD, 1, SYNC_LOGICAL + "::35-1", OWNER_A, OWNER_B));
            assertThat(res.newAnchor().lsuSerial()).isEqualTo(1);
        }

        @Test
        void ownersChangeBelowTheThresholdRejected() {
            final Rot r = rot(tx(
                    ADD,
                    2,
                    CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE,
                    dn(SYNC_NAMESPACE, 1, OWNER_A, OWNER_B),
                    OWNER_A));
            assertThat(error(twoOfTwo, mediators(), r)).isEqualTo("TopologyUnauthorized");
        }

        @Test
        void mediatorStateBelowTheThresholdRejected() {
            // mediatorState() is signed by OWNER_A alone.
            final Rot r = mediatorState(ADD, 2, utf8(SYNC_LOGICAL), 0, 1, List.of(MEDIATOR_UID));
            assertThat(error(twoOfTwo, mediators(), r)).isEqualTo("TopologyUnauthorized");
        }

        @Test
        void anOwnerAndItsDelegateCountOnce() {
            final byte[] ownerSet = new CantonSets.Owners(
                            1,
                            2,
                            List.of(
                                    new CantonSets.Owner(
                                            OWNER_A.ns(),
                                            OWNER_A.key(),
                                            List.of(new CantonSets.Delegation(DELEGATE_1.key(), 0, 0, 1, false))),
                                    new CantonSets.Owner(OWNER_B.ns(), OWNER_B.key(), List.of())))
                    .encode();
            final String successor = SYNC_LOGICAL + "::35-1";

            assertThat(error(ownerSet, mediators(), lsu(ADD, 1, successor, OWNER_A, DELEGATE_1)))
                    .isEqualTo("TopologyUnauthorized");
            // A delegate of one owner and the root of the other are two owners.
            assertThat(run(ownerSet, mediators(), lsu(ADD, 1, successor, DELEGATE_1, OWNER_B))
                            .newAnchor()
                            .successorSynchronizerId())
                    .isEqualTo(successor);
        }
    }

    @Nested
    class DelegationRestrictions {
        private static final int[] CODES = {
            CantonTopology.CODE_NAMESPACE_DELEGATION,
            CantonTopology.CODE_DECENTRALIZED_NAMESPACE,
            CantonTopology.CODE_OWNER_TO_KEY,
            CantonTopology.CODE_MEDIATOR_STATE,
            CantonTopology.CODE_LSU_ANNOUNCEMENT
        };

        @Test
        void restrictionZeroAllowsEveryMapping() {
            for (final int code : CODES) {
                assertThat(CantonTopologyVerifier.allows(0, 0, code))
                        .as("code %d", code)
                        .isTrue();
            }
        }

        @Test
        void restrictionOneRefusesOnlyNamespaceDelegations() {
            assertThat(CantonTopologyVerifier.allows(1, 0, CantonTopology.CODE_NAMESPACE_DELEGATION))
                    .isFalse();
            for (final int code : CODES) {
                if (code != CantonTopology.CODE_NAMESPACE_DELEGATION) {
                    assertThat(CantonTopologyVerifier.allows(1, 0, code))
                            .as("code %d", code)
                            .isTrue();
                }
            }
        }

        @Test
        void restrictionOneIgnoresTheMappingsSet() {
            final long all = -1L;
            assertThat(CantonTopologyVerifier.allows(1, all, CantonTopology.CODE_NAMESPACE_DELEGATION))
                    .isFalse();
        }

        @Test
        void restrictionTwoAllowsExactlyTheMappingsWhoseBitIsSet() {
            for (final int allowed : CODES) {
                final long mappings = 1L << allowed;
                for (final int code : CODES) {
                    assertThat(CantonTopologyVerifier.allows(2, mappings, code))
                            .as("allowed %d, asked %d", allowed, code)
                            .isEqualTo(code == allowed);
                }
            }
        }

        @Test
        void restrictionTwoWithNoMappingsAllowsNothing() {
            for (final int code : CODES) {
                assertThat(CantonTopologyVerifier.allows(2, 0, code))
                        .as("code %d", code)
                        .isFalse();
            }
        }

        @Test
        void unknownRestrictionAllowsNothing() {
            for (final int code : CODES) {
                assertThat(CantonTopologyVerifier.allows(3, -1L, code))
                        .as("code %d", code)
                        .isFalse();
            }
        }

        private static final long DN_BIT = 1L << CantonTopology.CODE_DECENTRALIZED_NAMESPACE;
        private static final long MEDIATOR_BIT = 1L << CantonTopology.CODE_MEDIATOR_STATE;

        @Test
        void restrictionTwoAllowsOnlyTheMappingsInItsSet() {
            final byte[] ownerSet = delegatedOwners(new CantonSets.Delegation(DELEGATE_1.key(), 2, DN_BIT, 1, false));
            final Rot mediatorState = rot(tx(
                    ADD,
                    2,
                    CantonTopology.MAPPING_MEDIATOR_STATE,
                    msBody(utf8(SYNC_LOGICAL), 0, 1, List.of(MEDIATOR_UID)),
                    DELEGATE_1));
            assertThat(error(ownerSet, mediators(), mediatorState)).isEqualTo("TopologyUnauthorized");

            final byte[] allowed =
                    delegatedOwners(new CantonSets.Delegation(DELEGATE_1.key(), 2, MEDIATOR_BIT, 1, false));
            final CantonSets.Mediators out = CantonSets.decodeMediators(
                    run(allowed, mediators(), mediatorState).newMediatorSet());
            assertThat(out.serial()).isEqualTo(2);
        }

        @Test
        void restrictionOneAllowsEveryMappingButNamespaceDelegations() {
            final byte[] ownerSet = delegatedOwners(new CantonSets.Delegation(DELEGATE_1.key(), 1, 0, 1, false));
            final Rot mediatorState = rot(tx(
                    ADD,
                    2,
                    CantonTopology.MAPPING_MEDIATOR_STATE,
                    msBody(utf8(SYNC_LOGICAL), 0, 1, List.of(MEDIATOR_UID)),
                    DELEGATE_1));
            assertThat(CantonSets.decodeMediators(
                                    run(ownerSet, mediators(), mediatorState).newMediatorSet())
                            .serial())
                    .isEqualTo(2);

            // A delegate signs for no namespace delegation: only an owner's root may.
            final Rot delegation = rot(tx(
                    ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_2, 4), DELEGATE_1));
            assertThat(error(ownerSet, mediators(), delegation)).isEqualTo("TopologyUnauthorized");
        }

        @Test
        void removedDelegationNoLongerAuthorizes() {
            final byte[] ownerSet = delegatedOwners(new CantonSets.Delegation(DELEGATE_1.key(), 0, 0, 1, true));
            final Rot mediatorState = rot(tx(
                    ADD,
                    2,
                    CantonTopology.MAPPING_MEDIATOR_STATE,
                    msBody(utf8(SYNC_LOGICAL), 0, 1, List.of(MEDIATOR_UID)),
                    DELEGATE_1));
            assertThat(error(ownerSet, mediators(), mediatorState)).isEqualTo("TopologyUnauthorized");
        }

        @Test
        void delegationRemovedByARotationNoLongerAuthorizes() {
            final Rot add = rot(
                    tx(ADD, 1, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 4), OWNER_A));
            final Rot remove = rot(tx(
                    REMOVE, 2, CantonTopology.MAPPING_NAMESPACE_DELEGATION, nsd(OWNER_A.ns(), DELEGATE_1, 4), OWNER_A));
            final String successor = SYNC_LOGICAL + "::35-1";

            // While it stands, the delegate announces an upgrade for the owner.
            assertThat(run(owners(OWNER_A), mediators(), add, lsu(ADD, 1, successor, DELEGATE_1))
                            .newAnchor()
                            .successorSynchronizerId())
                    .isEqualTo(successor);
            // Once removed, it may not.
            assertThat(error(owners(OWNER_A), mediators(), add, remove, lsu(ADD, 1, successor, DELEGATE_1)))
                    .isEqualTo("TopologyUnauthorized");
        }

        /** A one-owner set whose owner, {@link #OWNER_A}, has {@code delegation}. */
        private byte[] delegatedOwners(final CantonSets.Delegation delegation) {
            return new CantonSets.Owners(
                            1, 1, List.of(new CantonSets.Owner(OWNER_A.ns(), OWNER_A.key(), List.of(delegation))))
                    .encode();
        }
    }

    @Nested
    class TopologySignatures {
        private final byte[] body = lsuBody(SYNC_LOGICAL + "::35-1");

        @Test
        void signatureThatDoesNotVerifyRejected() {
            final Rot r = rot(badlySignedTx(ADD, 1, CantonTopology.MAPPING_LSU_ANNOUNCEMENT, body, OWNER_A));
            assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("InvalidTopologySignature");
        }

        @Test
        void multiTransactionSignatureOverHashesIncludingTheTransactionAuthorizes() {
            final byte[] own = topologyHash(txBytes(ADD, 1, CantonTopology.MAPPING_LSU_ANNOUNCEMENT, body));
            final List<byte[]> hashes = List.of(sha256(utf8("another transaction")), own);
            final Rot r = rot(multiTx(ADD, 1, CantonTopology.MAPPING_LSU_ANNOUNCEMENT, body, hashes, OWNER_A));

            final CantonAnchor.Anchor out = run(owners(OWNER_A), mediators(), r).newAnchor();
            assertThat(out.successorSynchronizerId()).isEqualTo(SYNC_LOGICAL + "::35-1");
        }

        @Test
        void multiTransactionSignatureOverHashesNotIncludingTheTransactionRejected() {
            final List<byte[]> hashes = List.of(sha256(utf8("another transaction")), sha256(utf8("and another")));
            final Rot r = rot(multiTx(ADD, 1, CantonTopology.MAPPING_LSU_ANNOUNCEMENT, body, hashes, OWNER_A));

            assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("CantonProtoMalformed");
        }

        @Test
        void multiTransactionSignatureThatDoesNotVerifyRejected() {
            final byte[] own = topologyHash(txBytes(ADD, 1, CantonTopology.MAPPING_LSU_ANNOUNCEMENT, body));
            // OWNER_A signs the combined hash of a different set than the one the transaction lists.
            final byte[] txBytes = txBytes(ADD, 1, CantonTopology.MAPPING_LSU_ANNOUNCEMENT, body);
            final Pb multi = new Pb()
                    .field(1, CantonBytes.concat(new byte[] {0x12, 0x20}, own))
                    .field(2, sigItem(OWNER_A, combinedHash(List.of(own, sha256(utf8("extra"))))));
            final Rot r = rot(versionedV30(
                    new Pb().field(1, txBytes).field(4, multi.build()).build()));

            assertThat(error(owners(OWNER_A), mediators(), r)).isEqualTo("InvalidTopologySignature");
        }
    }

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

    private static CantonAnchor.Anchor anchor(
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
                APP);
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

    private static byte[] uidOf(final String name) {
        return utf8(name + "::1220" + formatHex(MEDIATOR_ROOT.ns()));
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

    /** An Ed25519 key pair and its Canton key. */
    private record Signer(byte[] secret, byte[] pub, CantonSets.Key key) {
        static Signer of(final int seed) {
            final byte[] s = new byte[Ed25519.SEED_BYTES];
            Arrays.fill(s, (byte) seed);
            final byte[] pub = new byte[Ed25519.PUBLIC_KEY_BYTES];
            final byte[] secret = new byte[Ed25519.SECRET_KEY_BYTES];
            Ed25519.newKeypair(pub, secret, s);
            final CantonSets.Key key = CantonSets.keyFromDer(
                            CantonSets.SPEC_ED25519, CantonBytes.concat(CantonSets.DER_ED25519, pub))
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
                        .map(s -> Rlp.encodeBytes(s.key().encode()))
                        .toList());
        return Rlp.encodeList(List.of(Rlp.encodeBytes(r.signedTx()), offered, Rlp.encodeBytes(r.uid())));
    }

    private static byte[] owners(final Signer... roots) {
        return ownersWithThreshold(1, roots);
    }

    private static byte[] ownersWithThreshold(final long threshold, final Signer... roots) {
        final List<Signer> sorted = new ArrayList<>(List.of(roots));
        sorted.sort((x, y) -> Arrays.compareUnsigned(x.ns(), y.ns()));
        return (new CantonSets.Owners(
                        1,
                        threshold,
                        sorted.stream()
                                .map(s -> new CantonSets.Owner(s.ns(), s.key(), List.of()))
                                .toList()))
                .encode();
    }

    /** One mediator, {@link #MEDIATOR_UID}, holding {@link #KEY_OLD}, at serial 1. */
    private static byte[] mediators() {
        return (new CantonSets.Mediators(
                        1,
                        1,
                        List.of(new CantonSets.Member(
                                sha256(MEDIATOR_UID), 1, List.of(), List.of(KEY_OLD.key()), List.of()))))
                .encode();
    }

    private static CantonAnchor.Anchor anchorOf(
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
                APP);
    }

    private static CantonTopologyVerifier.RotationResult run(
            final byte[] ownerSet, final byte[] mediatorSet, final Rot... rots) {
        return run(anchorOf(SYNC_ID, "", 0, 0, ownerSet, mediatorSet), ownerSet, mediatorSet, rots);
    }

    private static CantonTopologyVerifier.RotationResult run(
            final CantonAnchor.Anchor anchor, final byte[] ownerSet, final byte[] mediatorSet, final Rot... rots) {
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
            final CantonAnchor.Anchor anchor, final byte[] ownerSet, final byte[] mediatorSet, final Rot... rots) {
        final Throwable t = catchThrowable(() -> run(anchor, ownerSet, mediatorSet, rots));
        assertThat(t).isInstanceOf(CantonProofException.class);
        return ((CantonProofException) t).errorName();
    }

    /** A versioned signed topology transaction with a one-field mapping, signed by each of {@code signers}. */
    private static byte[] tx(
            final long op, final long serial, final long mappingKind, final byte[] body, final Signer... signers) {
        final byte[] txBytes = txBytes(op, serial, mappingKind, body);
        final byte[] hash = topologyHash(txBytes);
        final Pb signed = new Pb().field(1, txBytes);
        for (final Signer s : signers) {
            signed.field(2, sigItem(s, hash));
        }
        return versionedV30(signed.build());
    }

    /** Like {@link #tx} for one signer, whose signature is over another hash, so that it does not verify. */
    private static byte[] badlySignedTx(
            final long op, final long serial, final long mappingKind, final byte[] body, final Signer signer) {
        final byte[] txBytes = txBytes(op, serial, mappingKind, body);
        final byte[] otherHash = sha256(utf8("not the transaction"));
        return versionedV30(
                new Pb().field(1, txBytes).field(2, sigItem(signer, otherHash)).build());
    }

    /**
     * Like {@link #tx}, but signed as a multi-transaction signature: each of {@code signers} signs the combined
     * hash of {@code hashes}, which should include this transaction's {@link #topologyHash} to be valid.
     */
    private static byte[] multiTx(
            final long op,
            final long serial,
            final long mappingKind,
            final byte[] body,
            final List<byte[]> hashes,
            final Signer... signers) {
        final byte[] txBytes = txBytes(op, serial, mappingKind, body);
        final Pb multi = new Pb();
        for (final byte[] h : hashes) {
            multi.field(1, CantonBytes.concat(new byte[] {0x12, 0x20}, h));
        }
        final byte[] combined = combinedHash(hashes);
        for (final Signer s : signers) {
            multi.field(2, sigItem(s, combined));
        }
        return versionedV30(new Pb().field(1, txBytes).field(4, multi.build()).build());
    }

    private static byte[] txBytes(final long op, final long serial, final long mappingKind, final byte[] body) {
        return versionedV30(new Pb()
                .varint(1, op)
                .varint(2, serial)
                .field(3, new Pb().field((int) mappingKind, body).build())
                .build());
    }

    private static byte[] topologyHash(final byte[] txBytes) {
        return sha256(CantonBytes.concat(new byte[] {0, 0, 0, CantonTopology.PURPOSE_TOPOLOGY_TRANSACTION}, txBytes));
    }

    /** A signature item by {@code signer} over the multihash of {@code hash}. */
    private static byte[] sigItem(final Signer signer, final byte[] hash) {
        final byte[] message = CantonBytes.concat(new byte[] {0x12, 0x20}, hash);
        return new Pb().field(2, signer.sign(message)).field(3, fp(signer.ns())).build();
    }

    /** {@code SHA256(BE32(55) ‖ BE32(n) ‖ (BE32(34) ‖ 0x1220 ‖ h) for each distinct h, ascending)}. */
    private static byte[] combinedHash(final List<byte[]> hashes) {
        final List<byte[]> sorted = new ArrayList<>();
        for (final byte[] h : hashes) {
            if (sorted.stream().noneMatch(x -> Arrays.equals(x, h))) {
                sorted.add(h);
            }
        }
        sorted.sort(Arrays::compareUnsigned);
        byte[] buf = CantonBytes.concat(be32(CantonTopology.PURPOSE_MULTI_TOPOLOGY_TRANSACTION), be32(sorted.size()));
        for (final byte[] h : sorted) {
            buf = CantonBytes.concat(buf, be32(34), new byte[] {0x12, 0x20}, h);
        }
        return sha256(buf);
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
        final Pb pb = new Pb().field(1, CantonBytes.concat(utf8("MED::"), uid));
        for (final Signer k : keys) {
            pb.field(2, new Pb().field(1, signingKey(k)).build());
        }
        return pb.build();
    }

    /** A {@code SigningPublicKey}: DER format, Ed25519, usage {@code PROTOCOL}. */
    private static byte[] signingKey(final Signer s) {
        return new Pb()
                .varint(2, 4)
                .field(3, CantonBytes.concat(CantonSets.DER_ED25519, s.pub()))
                .varint(5, 4)
                .varint(6, 1)
                .build();
    }
}
