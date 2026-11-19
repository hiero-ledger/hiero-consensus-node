// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static com.hedera.node.app.service.clpr.impl.verifier.canton.CantonBytes.sha256;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.Ed25519Verify;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonRlp.Slice;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Applies the topology rotations of a Canton bundle to the channel's trust anchor: the synchronizer owners'
 * changes, the mediator group's changes, the mediators' key rotations, the namespace delegations of owners and
 * mediators, and the announcement of a Logical Synchronizer Upgrade. Each transaction is authorized as Canton
 * authorizes it, from the keys that the anchor already trusts. Mirrors the reference {@code CantonTopologyVerifier}
 * byte for byte.
 *
 * <p>{@code rotations} = RLP {@code [ownerSet, [RLP [signedTopologyTransaction, signerKeys, mediatorUid], …]]},
 * applied in order, each serial going up by exactly one from the one in the sets.
 *
 * <p>Unlike the Solidity reference, no {@code IEd25519Verifier} argument is threaded through: this port verifies
 * Ed25519 through {@link CantonTopology.Transaction#signedBy}, which uses the static {@link Ed25519Verify}.
 */
final class CantonTopologyVerifier {

    private CantonTopologyVerifier() {}

    /** The anchor, mediator set and owner set after the rotations. */
    record RotationResult(
            @NonNull CantonAnchor.Anchor newAnchor,
            @NonNull byte[] newMediatorSet,
            @NonNull byte[] newOwnerSet) {}

    private record Rotation(
            @NonNull CantonTopology.Transaction tx,
            @NonNull List<CantonSets.Key> signerKeys,
            @NonNull byte[] mediatorUid) {}

    /** The sets and anchor fields that the rotations change, as they stand after the rotations applied so far. */
    private record RotationState(
            @NonNull CantonSets.Owners owners,
            @NonNull CantonSets.Mediators mediators,
            @NonNull Lsu lsu) {

        RotationState withOwners(final CantonSets.Owners next) {
            return new RotationState(next, mediators, lsu);
        }

        RotationState withMediators(final CantonSets.Mediators next) {
            return new RotationState(owners, next, lsu);
        }

        RotationState withLsu(final Lsu next) {
            return new RotationState(owners, mediators, next);
        }
    }

    /** The anchor fields that an {@code LsuAnnouncement} changes. */
    private record Lsu(@NonNull String successor, long upgradeTime, int serial) {

        static Lsu of(final CantonAnchor.Anchor a) {
            return new Lsu(a.successorSynchronizerId(), a.upgradeTime(), a.lsuSerial());
        }
    }

    // ── applyRotations ───────────────────────────────────────────────────────

    /**
     * Apply {@code rotations} to {@code anchor} and its sets. Mirrors the reference {@code applyRotations}.
     *
     * @param anchor the decoded trust anchor
     * @param mediatorSet the packed mediator set that the anchor commits to; the caller has already checked that it
     *     hashes to {@code anchor.mediatorsHash()} (see {@link CantonVerdictVerifier#verifyBundleVerdict})
     * @param rotations bundle field 4, not empty
     * @return the anchor, mediator set and owner set after the rotations; {@code lastRequestId} unchanged
     * @throws CantonProofException on any verification failure
     */
    @NonNull
    static RotationResult applyRotations(
            @NonNull final CantonAnchor.Anchor anchor,
            @NonNull final byte[] mediatorSet,
            @NonNull final byte[] rotations) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(mediatorSet, "mediatorSet");
        Objects.requireNonNull(rotations, "rotations");

        final List<Slice> r = CantonRlp.readList(CantonRlp.of(rotations));
        if (r.size() != 2) {
            throw CantonProofException.invalidPayloadShape("rotations has " + r.size() + " items");
        }
        final byte[] ownerSet = CantonRlp.readBytes(r.getFirst());
        if (!Arrays.equals(MiscCryptoUtils.keccak256DigestOf(ownerSet), anchor.ownersHash())) {
            throw CantonProofException.trustSetMismatch();
        }
        RotationState state = new RotationState(
                CantonSets.decodeOwners(ownerSet), CantonSets.decodeMediators(mediatorSet), Lsu.of(anchor));

        final List<Slice> list = CantonRlp.readList(r.get(1));
        if (list.isEmpty()) {
            throw CantonProofException.invalidPayloadShape("rotations list is empty");
        }
        for (final Slice item : list) {
            state = applyRotation(anchor, state, readRotation(item));
        }

        final byte[] newOwnerSet = state.owners().encode();
        final byte[] newMediatorSet = state.mediators().encode();
        final Lsu lsu = state.lsu();
        final CantonAnchor.Anchor next = anchor.withRotation(
                MiscCryptoUtils.keccak256DigestOf(newOwnerSet),
                MiscCryptoUtils.keccak256DigestOf(newMediatorSet),
                lsu.successor(),
                lsu.upgradeTime(),
                lsu.serial());
        return new RotationResult(next, newMediatorSet, newOwnerSet);
    }

    /** The state after one rotation, which is authorized against the state before it. */
    private static RotationState applyRotation(
            final CantonAnchor.Anchor anchor, final RotationState state, final Rotation rot) {
        final long kind = rot.tx().mappingKind();
        if (kind == CantonTopology.MAPPING_LSU_ANNOUNCEMENT) {
            noSigners(rot);
            requireOwners(state.owners(), rot.tx(), CantonTopology.CODE_LSU_ANNOUNCEMENT);
            return state.withLsu(applyLsuAnnouncement(anchor, state.lsu(), rot.tx()));
        } else if (kind == CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE) {
            if (rot.mediatorUid().length != 0) {
                throw CantonProofException.rotationsNotSupported();
            }
            return state.withOwners(applyOwners(anchor, state.owners(), rot));
        } else if (kind == CantonTopology.MAPPING_MEDIATOR_STATE) {
            noSigners(rot);
            return state.withMediators(applyMediatorState(anchor, state.owners(), state.mediators(), rot.tx()));
        } else if (kind == CantonTopology.MAPPING_OWNER_TO_KEY) {
            if (rot.mediatorUid().length != 0) {
                throw CantonProofException.rotationsNotSupported();
            }
            return state.withMediators(applyOwnerToKey(state.mediators(), rot));
        } else if (kind == CantonTopology.MAPPING_NAMESPACE_DELEGATION) {
            if (rot.mediatorUid().length == 0) {
                if (!rot.signerKeys().isEmpty()) {
                    throw CantonProofException.rotationsNotSupported();
                }
                return state.withOwners(applyOwnerDelegation(state.owners(), rot.tx()));
            }
            return state.withMediators(applyMediatorDelegation(state.mediators(), rot));
        }
        throw CantonProofException.rotationsNotSupported();
    }

    private static Rotation readRotation(final Slice item) {
        final List<Slice> l = CantonRlp.readList(item);
        if (l.size() != 3) {
            throw CantonProofException.invalidPayloadShape("rotation has " + l.size() + " items");
        }
        final CantonTopology.Transaction tx = CantonTopology.readTransaction(CantonRlp.readBytes(l.get(0)));
        final List<CantonSets.Key> signerKeys = new ArrayList<>();
        if (!CantonRlp.isEmpty(l.get(1))) {
            for (final Slice ks : CantonRlp.readList(l.get(1))) {
                final CantonSets.Key key = CantonSets.decodeKey(CantonRlp.readBytes(ks));
                if (!key.hasOwnFingerprint()) {
                    throw CantonProofException.invalidSignerKey();
                }
                signerKeys.add(key);
            }
        }
        return new Rotation(tx, signerKeys, CantonRlp.readBytes(l.get(2)));
    }

    private static void noSigners(final Rotation rot) {
        if (!rot.signerKeys().isEmpty() || rot.mediatorUid().length != 0) {
            throw CantonProofException.rotationsNotSupported();
        }
    }

    // ── Authorization ────────────────────────────────────────────────────────

    private static int ownerSignatures(
            final CantonSets.Owners owners, final CantonTopology.Transaction t, final int code) {
        int count = 0;
        for (final CantonSets.Owner o : owners.owners()) {
            if (ownerSigned(o, t, code)) {
                ++count;
            }
        }
        return count;
    }

    private static boolean ownerSigned(
            final CantonSets.Owner owner, final CantonTopology.Transaction t, final int code) {
        if (t.signedBy(owner.root())) {
            return true;
        }
        return delegateSigned(owner.delegations(), t, code);
    }

    private static boolean delegateSigned(
            final List<CantonSets.Delegation> delegations, final CantonTopology.Transaction t, final int code) {
        for (final CantonSets.Delegation d : delegations) {
            if (allows(d, code) && t.signedBy(d.key())) {
                return true;
            }
        }
        return false;
    }

    private static boolean allows(final CantonSets.Delegation d, final int code) {
        return !d.removed() && allows(d.restriction(), d.mappings(), code);
    }

    /**
     * Whether a live delegation with {@code restriction} and {@code mappings} may sign a mapping of {@code code}:
     * restriction 0 allows every mapping, 1 every mapping but namespace delegations, and 2 only the mappings whose
     * bit is set in {@code mappings}.
     */
    static boolean allows(final int restriction, final long mappings, final int code) {
        if (restriction == 0) {
            return true;
        }
        if (restriction == 1) {
            return code != CantonTopology.CODE_NAMESPACE_DELEGATION;
        }
        return restriction == 2 && ((mappings >> code) & 1) == 1;
    }

    private static void requireOwners(
            final CantonSets.Owners owners, final CantonTopology.Transaction t, final int code) {
        if (ownerSignatures(owners, t, code) < owners.threshold()) {
            throw CantonProofException.topologyUnauthorized();
        }
    }

    private static boolean rootSigned(final Rotation rot, final byte[] namespace) {
        for (final CantonSets.Key k : rot.signerKeys()) {
            if (Arrays.equals(k.fingerprint(), namespace)) {
                return rot.tx().signedBy(k);
            }
        }
        return false;
    }

    private static long nextSerial(final long current, final CantonTopology.Transaction t) {
        if (t.serial() != current + 1) {
            throw CantonProofException.serialMismatch();
        }
        return t.serial();
    }

    /** A copy of {@code list} with {@code item} at {@code index}. */
    private static <T> List<T> replaced(final List<T> list, final int index, final T item) {
        final List<T> copy = new ArrayList<>(list);
        copy.set(index, item);
        return copy;
    }

    /**
     * Insert {@code item} into {@code list}, kept sorted by {@code key}; a key twice reverts.
     */
    private static <T> void insertSorted(final List<T> list, final T item, final Function<T, byte[]> key) {
        final byte[] k = key.apply(item);
        int j = list.size();
        list.add(item);
        while (j > 0 && Arrays.compareUnsigned(key.apply(list.get(j - 1)), k) > 0) {
            list.set(j, list.get(j - 1));
            --j;
        }
        if (j > 0 && Arrays.equals(key.apply(list.get(j - 1)), k)) {
            throw CantonProofException.invalidTopologyChange();
        }
        list.set(j, item);
    }

    // ── Owners ─────────────────────────────────────────────────────────────

    private static CantonSets.Owners applyOwners(
            final CantonAnchor.Anchor anchor, final CantonSets.Owners owners, final Rotation rot) {
        final CantonTopology.Transaction t = rot.tx();
        if (t.operation() != CantonTopology.OP_ADD_REPLACE) {
            throw CantonProofException.rotationsNotSupported();
        }
        final CantonTopology.DecentralizedNamespace dn = CantonTopology.readDecentralizedNamespace(t.body());
        if (!Arrays.equals(dn.namespace(), anchor.synchronizerNamespace())
                || dn.threshold() == 0
                || dn.threshold() > dn.owners().size()) {
            throw CantonProofException.invalidTopologyChange();
        }
        final long serial = nextSerial(owners.serial(), t);
        requireOwners(owners, t, CantonTopology.CODE_DECENTRALIZED_NAMESPACE);
        final List<CantonSets.Owner> next = new ArrayList<>();
        for (final byte[] member : dn.owners()) {
            final int found = ownerIndex(owners, member);
            final CantonSets.Owner o;
            if (found >= 0) {
                o = owners.owners().get(found);
            } else {
                final CantonSets.Key root = signerKey(rot, member);
                if (!t.signedBy(root)) {
                    throw CantonProofException.topologyUnauthorized();
                }
                o = new CantonSets.Owner(member, root, List.of());
            }
            insertSorted(next, o, CantonSets.Owner::namespace);
        }
        return new CantonSets.Owners(serial, dn.threshold(), next);
    }

    private static int ownerIndex(final CantonSets.Owners owners, final byte[] namespace) {
        for (int i = 0; i < owners.owners().size(); i++) {
            if (Arrays.equals(owners.owners().get(i).namespace(), namespace)) {
                return i;
            }
        }
        return -1;
    }

    private static CantonSets.Key signerKey(final Rotation rot, final byte[] namespace) {
        for (final CantonSets.Key k : rot.signerKeys()) {
            if (Arrays.equals(k.fingerprint(), namespace)) {
                return k;
            }
        }
        throw CantonProofException.topologyUnauthorized();
    }

    private static CantonSets.Owners applyOwnerDelegation(
            final CantonSets.Owners owners, final CantonTopology.Transaction t) {
        final CantonTopology.NamespaceDelegation nd = CantonTopology.readNamespaceDelegation(t.body());
        final int index = ownerIndex(owners, nd.namespace());
        if (index < 0) {
            throw CantonProofException.invalidTopologyChange();
        }
        final CantonSets.Owner owner = owners.owners().get(index);
        if (!t.signedBy(owner.root())) {
            throw CantonProofException.topologyUnauthorized();
        }
        final CantonSets.Owner updated =
                new CantonSets.Owner(owner.namespace(), owner.root(), applyDelegation(owner.delegations(), t, nd));
        return new CantonSets.Owners(owners.serial(), owners.threshold(), replaced(owners.owners(), index, updated));
    }

    // ── Mediators ────────────────────────────────────────────────────────────

    private static CantonSets.Mediators applyMediatorState(
            final CantonAnchor.Anchor anchor,
            final CantonSets.Owners owners,
            final CantonSets.Mediators mediators,
            final CantonTopology.Transaction t) {
        if (t.operation() != CantonTopology.OP_ADD_REPLACE) {
            throw CantonProofException.rotationsNotSupported();
        }
        final CantonTopology.MediatorState ms = CantonTopology.readMediatorState(t.body());
        final byte[] logicalId = logicalId(anchor.synchronizerId().getBytes(StandardCharsets.UTF_8));
        if (!CantonProto.equals(t.body(), ms.synchronizerId(), logicalId)
                || ms.group() != Integer.toUnsignedLong(anchor.mediatorGroup())
                || ms.threshold() == 0
                || ms.threshold() > ms.active().size()) {
            throw CantonProofException.invalidTopologyChange();
        }
        final long serial = nextSerial(mediators.serial(), t);
        requireOwners(owners, t, CantonTopology.CODE_MEDIATOR_STATE);
        final List<CantonSets.Member> next = new ArrayList<>();
        for (final byte[] active : ms.active()) {
            final byte[] memberId = sha256(active);
            final int existing = memberIndex(mediators, memberId);
            final CantonSets.Member m = existing >= 0
                    ? mediators.members().get(existing)
                    : new CantonSets.Member(memberId, 0, List.of(), List.of(), List.of());
            insertSorted(next, m, CantonSets.Member::memberId);
        }
        return new CantonSets.Mediators(serial, ms.threshold(), next);
    }

    private static int memberIndex(final CantonSets.Mediators mediators, final byte[] memberId) {
        for (int i = 0; i < mediators.members().size(); i++) {
            if (Arrays.equals(mediators.members().get(i).memberId(), memberId)) {
                return i;
            }
        }
        return -1;
    }

    private static CantonSets.Mediators applyOwnerToKey(final CantonSets.Mediators mediators, final Rotation rot) {
        final CantonTopology.Transaction t = rot.tx();
        final CantonTopology.OwnerToKey otk = CantonTopology.readOwnerToKey(t.body());
        final int index = memberIndex(mediators, sha256(otk.uid()));
        if (index < 0) {
            throw CantonProofException.invalidTopologyChange();
        }
        final CantonSets.Member m = mediators.members().get(index);
        final long otkSerial = nextSerial(m.otkSerial(), t);
        if (!rootSigned(rot, CantonTopology.namespaceOfUid(otk.uid()))
                && !delegateSigned(m.delegations(), t, CantonTopology.CODE_OWNER_TO_KEY)) {
            throw CantonProofException.topologyUnauthorized();
        }
        if (t.operation() == CantonTopology.OP_REMOVE) {
            return withMember(
                    mediators,
                    index,
                    new CantonSets.Member(m.memberId(), otkSerial, m.delegations(), List.of(), List.of()));
        }
        if (t.operation() != CantonTopology.OP_ADD_REPLACE) {
            throw CantonProofException.rotationsNotSupported();
        }
        for (final CantonSets.Key k : otk.keys()) {
            if (!holds(m.current(), k.fingerprint()) && !t.signedBy(k)) {
                throw CantonProofException.topologyUnauthorized();
            }
        }
        final List<CantonSets.Key> previous = new ArrayList<>();
        for (final CantonSets.Key c : m.current()) {
            if (!holds(otk.keys(), c.fingerprint())) {
                previous.add(c);
            }
        }
        return withMember(
                mediators,
                index,
                new CantonSets.Member(m.memberId(), otkSerial, m.delegations(), List.copyOf(otk.keys()), previous));
    }

    private static CantonSets.Mediators withMember(
            final CantonSets.Mediators mediators, final int index, final CantonSets.Member member) {
        return new CantonSets.Mediators(
                mediators.serial(), mediators.threshold(), replaced(mediators.members(), index, member));
    }

    private static boolean holds(final List<CantonSets.Key> keys, final byte[] fingerprint) {
        for (final CantonSets.Key k : keys) {
            if (Arrays.equals(k.fingerprint(), fingerprint)) {
                return true;
            }
        }
        return false;
    }

    private static CantonSets.Mediators applyMediatorDelegation(
            final CantonSets.Mediators mediators, final Rotation rot) {
        final CantonTopology.Transaction t = rot.tx();
        final CantonTopology.NamespaceDelegation nd = CantonTopology.readNamespaceDelegation(t.body());
        if (!Arrays.equals(nd.namespace(), CantonTopology.namespaceOfUid(rot.mediatorUid()))) {
            throw CantonProofException.invalidTopologyChange();
        }
        final int index = memberIndex(mediators, sha256(rot.mediatorUid()));
        if (index < 0) {
            throw CantonProofException.invalidTopologyChange();
        }
        if (!rootSigned(rot, nd.namespace())) {
            throw CantonProofException.topologyUnauthorized();
        }
        final CantonSets.Member m = mediators.members().get(index);
        return withMember(
                mediators,
                index,
                new CantonSets.Member(
                        m.memberId(),
                        m.otkSerial(),
                        applyDelegation(m.delegations(), t, nd),
                        m.current(),
                        m.previous()));
    }

    // ── Delegations ──────────────────────────────────────────────────────────

    private static List<CantonSets.Delegation> applyDelegation(
            final List<CantonSets.Delegation> delegations,
            final CantonTopology.Transaction t,
            final CantonTopology.NamespaceDelegation nd) {
        if (!nd.ok() || Arrays.equals(nd.target().fingerprint(), nd.namespace())) {
            throw CantonProofException.rotationsNotSupported();
        }
        for (int i = 0; i < delegations.size(); i++) {
            final CantonSets.Delegation d = delegations.get(i);
            if (!Arrays.equals(d.key().fingerprint(), nd.target().fingerprint())) {
                continue;
            }
            return replaced(delegations, i, setDelegation(d, nextSerial(d.serial(), t), t, nd));
        }
        if (t.operation() != CantonTopology.OP_ADD_REPLACE) {
            throw CantonProofException.invalidTopologyChange();
        }
        final List<CantonSets.Delegation> grown = new ArrayList<>(delegations);
        grown.add(delegationOf(nd, nextSerial(0, t)));
        return grown;
    }

    private static CantonSets.Delegation setDelegation(
            final CantonSets.Delegation d,
            final long serial,
            final CantonTopology.Transaction t,
            final CantonTopology.NamespaceDelegation nd) {
        if (t.operation() == CantonTopology.OP_REMOVE) {
            return new CantonSets.Delegation(d.key(), d.restriction(), d.mappings(), serial, true);
        }
        if (t.operation() != CantonTopology.OP_ADD_REPLACE) {
            throw CantonProofException.rotationsNotSupported();
        }
        return delegationOf(nd, serial);
    }

    private static CantonSets.Delegation delegationOf(final CantonTopology.NamespaceDelegation nd, final long serial) {
        return new CantonSets.Delegation(nd.target(), nd.restriction(), nd.mappings(), serial, false);
    }

    // ── Logical Synchronizer Upgrade ───────────────────────────────────────

    private static Lsu applyLsuAnnouncement(
            final CantonAnchor.Anchor anchor, final Lsu lsu, final CantonTopology.Transaction t) {
        final CantonTopology.LsuAnnouncement a = CantonTopology.readLsuAnnouncement(t.body());
        final boolean pending = !lsu.successor().isEmpty();
        if (pending && Long.compareUnsigned(t.serial(), Integer.toUnsignedLong(lsu.serial())) <= 0) {
            throw CantonProofException.invalidLsuAnnouncement();
        }
        if (t.operation() == CantonTopology.OP_REMOVE) {
            if (!pending) {
                throw CantonProofException.invalidLsuAnnouncement();
            }
            return new Lsu("", 0, 0);
        }
        if (t.operation() != CantonTopology.OP_ADD_REPLACE || Long.compareUnsigned(t.serial(), 0xffffffffL) > 0) {
            throw CantonProofException.invalidLsuAnnouncement();
        }
        if (!succeeds(a.successor(), anchor.synchronizerId().getBytes(StandardCharsets.UTF_8))) {
            throw CantonProofException.invalidLsuAnnouncement();
        }
        return new Lsu(new String(a.successor(), StandardCharsets.UTF_8), a.upgradeTime(), (int) t.serial());
    }

    private record Physical(boolean ok, int logical, long protocolVersion, long serial) {}

    private record Decimal(boolean ok, long value, int next) {}

    /**
     * Whether {@code successor} is a greater physical id of the same logical synchronizer than {@code current}, as
     * Canton orders them: by serial, then by protocol version, which must not go down. A physical id is
     * {@code <logical id>::<protocol version>-<serial>}.
     *
     * <p>Where the reference compares {@code keccak256} of the logical-id prefixes, this compares the prefix bytes
     * directly — equivalent, and it avoids a hash the Java port does not otherwise need.
     */
    private static boolean succeeds(final byte[] successor, final byte[] current) {
        final Physical s = physicalParts(successor);
        final Physical c = physicalParts(current);
        if (!s.ok() || !c.ok() || s.logical() != c.logical()) {
            return false;
        }
        if (!Arrays.equals(successor, 0, s.logical(), current, 0, c.logical())) {
            return false;
        }
        return s.protocolVersion() >= c.protocolVersion()
                && (s.serial() > c.serial() || (s.serial() == c.serial() && s.protocolVersion() > c.protocolVersion()));
    }

    /** The logical id of a physical id: the part before its last {@code ::}. */
    private static byte[] logicalId(final byte[] id) {
        final Physical p = physicalParts(id);
        if (!p.ok()) {
            throw CantonProofException.invalidTopologyChange();
        }
        return Arrays.copyOfRange(id, 0, p.logical());
    }

    /** The length of the logical id before the last {@code ::}, and the protocol version and serial after it. */
    private static Physical physicalParts(final byte[] id) {
        int i = id.length;
        while (i > 1 && !(id[i - 1] == ':' && id[i - 2] == ':')) {
            --i;
        }
        if (i < 3) {
            return new Physical(false, 0, 0, 0);
        }
        final int logical = i - 2;
        final Decimal pv = decimal(id, i, (byte) '-');
        if (!pv.ok() || pv.next() == id.length) {
            return new Physical(false, 0, 0, 0);
        }
        final Decimal serial = decimal(id, pv.next() + 1, (byte) ':');
        return new Physical(serial.ok() && serial.next() == id.length, logical, pv.value(), serial.value());
    }

    /** The decimal number of at most 9 digits at {@code i}, up to {@code stop} or the end of {@code s}. */
    private static Decimal decimal(final byte[] s, final int from, final byte stop) {
        int i = from;
        long v = 0;
        for (; i < s.length && s[i] != stop; ++i) {
            final int c = s[i] & 0xff;
            if (c < 0x30 || c > 0x39 || i - from >= 9) {
                return new Decimal(false, 0, i);
            }
            v = v * 10 + (c - 0x30);
        }
        return new Decimal(i > from, v, i);
    }
}
