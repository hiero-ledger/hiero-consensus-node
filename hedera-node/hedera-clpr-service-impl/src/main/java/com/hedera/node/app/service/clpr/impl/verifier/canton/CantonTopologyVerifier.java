// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonRlp.Slice;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
 * Ed25519 through {@link CantonTopology#signedBy}, which uses the static {@link Ed25519Verify}.
 */
public final class CantonTopologyVerifier {

    private CantonTopologyVerifier() {}

    /** The anchor, mediator set and owner set after the rotations. */
    public record RotationResult(
            @NonNull byte[] newAnchor,
            @NonNull byte[] newMediatorSet,
            @NonNull byte[] newOwnerSet) {}

    private record Rotation(
            @NonNull CantonTopology.Transaction tx,
            @NonNull List<CantonSets.Key> signerKeys,
            @NonNull byte[] mediatorUid) {}

    // ── Mutable working state ────────────────────────────────────────────────

    private static final class MDelegation {
        private CantonSets.Key key;
        private int restriction;
        private long mappings;
        private long serial;
        private boolean removed;

        MDelegation(final CantonSets.Delegation d) {
            key = d.key();
            restriction = d.restriction();
            mappings = d.mappings();
            serial = d.serial();
            removed = d.removed();
        }

        MDelegation() {}

        CantonSets.Delegation toImmutable() {
            return new CantonSets.Delegation(key, restriction, mappings, serial, removed);
        }
    }

    private static final class MOwner {
        private byte[] namespace;
        private CantonSets.Key root;
        private List<MDelegation> delegations;

        MOwner(final CantonSets.Owner o) {
            namespace = o.namespace();
            root = o.root();
            delegations = new ArrayList<>();
            for (final CantonSets.Delegation d : o.delegations()) {
                delegations.add(new MDelegation(d));
            }
        }

        MOwner() {
            delegations = new ArrayList<>();
        }

        CantonSets.Owner toImmutable() {
            final List<CantonSets.Delegation> d = new ArrayList<>(delegations.size());
            for (final MDelegation x : delegations) {
                d.add(x.toImmutable());
            }
            return new CantonSets.Owner(namespace, root, d);
        }
    }

    private static final class MMember {
        private byte[] memberId;
        private long otkSerial;
        private List<MDelegation> delegations;
        private List<CantonSets.Key> current;
        private List<CantonSets.Key> previous;

        MMember(final CantonSets.Member m) {
            memberId = m.memberId();
            otkSerial = m.otkSerial();
            delegations = new ArrayList<>();
            for (final CantonSets.Delegation d : m.delegations()) {
                delegations.add(new MDelegation(d));
            }
            current = new ArrayList<>(m.current());
            previous = new ArrayList<>(m.previous());
        }

        MMember() {
            delegations = new ArrayList<>();
            current = new ArrayList<>();
            previous = new ArrayList<>();
        }

        CantonSets.Member toImmutable() {
            final List<CantonSets.Delegation> d = new ArrayList<>(delegations.size());
            for (final MDelegation x : delegations) {
                d.add(x.toImmutable());
            }
            return new CantonSets.Member(memberId, otkSerial, d, new ArrayList<>(current), new ArrayList<>(previous));
        }
    }

    private static final class MOwners {
        private long serial;
        private long threshold;
        private List<MOwner> owners;

        MOwners(final CantonSets.Owners s) {
            serial = s.serial();
            threshold = s.threshold();
            owners = new ArrayList<>();
            for (final CantonSets.Owner o : s.owners()) {
                owners.add(new MOwner(o));
            }
        }

        CantonSets.Owners toImmutable() {
            final List<CantonSets.Owner> o = new ArrayList<>(owners.size());
            for (final MOwner x : owners) {
                o.add(x.toImmutable());
            }
            return new CantonSets.Owners(serial, threshold, o);
        }
    }

    private static final class MMediators {
        private long serial;
        private long threshold;
        private List<MMember> members;

        MMediators(final CantonSets.Mediators s) {
            serial = s.serial();
            threshold = s.threshold();
            members = new ArrayList<>();
            for (final CantonSets.Member m : s.members()) {
                members.add(new MMember(m));
            }
        }

        CantonSets.Mediators toImmutable() {
            final List<CantonSets.Member> m = new ArrayList<>(members.size());
            for (final MMember x : members) {
                m.add(x.toImmutable());
            }
            return new CantonSets.Mediators(serial, threshold, m);
        }
    }

    /** The anchor fields that an {@code LsuAnnouncement} mutates while the rotations run. */
    private static final class Lsu {
        private String successor;
        private long upgradeTime;
        private int serial;

        Lsu(final CantonAnchor.Anchor a) {
            successor = a.successorSynchronizerId();
            upgradeTime = a.upgradeTime();
            serial = a.lsuSerial();
        }
    }

    // ── applyRotations ───────────────────────────────────────────────────────

    /**
     * Apply {@code rotations} to {@code trustAnchor} and its sets. Mirrors the reference {@code applyRotations}.
     *
     * @param trustAnchor the anchor of {@link CantonAnchor}
     * @param mediatorSet the packed mediator set that the anchor commits to
     * @param rotations bundle field 4, not empty
     * @return the anchor, mediator set and owner set after the rotations; {@code lastRequestId} unchanged
     * @throws CantonProofException on any verification failure
     */
    @NonNull
    public static RotationResult applyRotations(
            @NonNull final byte[] trustAnchor, @NonNull final byte[] mediatorSet, @NonNull final byte[] rotations) {
        Objects.requireNonNull(trustAnchor, "trustAnchor");
        Objects.requireNonNull(mediatorSet, "mediatorSet");
        Objects.requireNonNull(rotations, "rotations");

        final CantonAnchor.Anchor anchor = CantonAnchor.Anchor.decode(trustAnchor);
        if (!Arrays.equals(MiscCryptoUtils.keccak256DigestOf(mediatorSet), anchor.mediatorsHash())) {
            throw CantonProofException.trustSetMismatch();
        }
        final List<Slice> r = CantonRlp.readList(CantonRlp.of(rotations));
        if (r.size() != 2) {
            throw CantonProofException.invalidPayloadShape("rotations has " + r.size() + " items");
        }
        final byte[] ownerSet = CantonRlp.readBytes(r.get(0));
        if (!Arrays.equals(MiscCryptoUtils.keccak256DigestOf(ownerSet), anchor.ownersHash())) {
            throw CantonProofException.trustSetMismatch();
        }
        MOwners owners = new MOwners(CantonSets.decodeOwners(ownerSet));
        final MMediators mediators = new MMediators(CantonSets.decodeMediators(mediatorSet));
        final Lsu lsu = new Lsu(anchor);

        final List<Slice> list = CantonRlp.readList(r.get(1));
        if (list.isEmpty()) {
            throw CantonProofException.invalidPayloadShape("rotations list is empty");
        }
        for (final Slice item : list) {
            final Rotation rot = readRotation(item);
            final long kind = rot.tx().mappingKind();
            if (kind == CantonTopology.MAPPING_LSU_ANNOUNCEMENT) {
                noSigners(rot);
                requireOwners(owners, rot.tx(), CantonTopology.CODE_LSU_ANNOUNCEMENT);
                applyLsuAnnouncement(anchor, lsu, rot.tx());
            } else if (kind == CantonTopology.MAPPING_DECENTRALIZED_NAMESPACE) {
                if (rot.mediatorUid().length != 0) {
                    throw CantonProofException.rotationsNotSupported();
                }
                owners = applyOwners(anchor, owners, rot);
            } else if (kind == CantonTopology.MAPPING_MEDIATOR_STATE) {
                noSigners(rot);
                applyMediatorState(anchor, owners, mediators, rot.tx());
            } else if (kind == CantonTopology.MAPPING_OWNER_TO_KEY) {
                if (rot.mediatorUid().length != 0) {
                    throw CantonProofException.rotationsNotSupported();
                }
                applyOwnerToKey(mediators, rot);
            } else if (kind == CantonTopology.MAPPING_NAMESPACE_DELEGATION) {
                if (rot.mediatorUid().length == 0) {
                    if (!rot.signerKeys().isEmpty()) {
                        throw CantonProofException.rotationsNotSupported();
                    }
                    applyOwnerDelegation(owners, rot.tx());
                } else {
                    applyMediatorDelegation(mediators, rot);
                }
            } else {
                throw CantonProofException.rotationsNotSupported();
            }
        }

        final byte[] newOwnerSet = CantonSets.encodeOwners(owners.toImmutable());
        final byte[] newMediatorSet = CantonSets.encodeMediators(mediators.toImmutable());
        final CantonAnchor.Anchor next = new CantonAnchor.Anchor(
                anchor.synchronizerId(),
                anchor.lastRequestId(),
                anchor.mediatorGroup(),
                anchor.synchronizerNamespace(),
                MiscCryptoUtils.keccak256DigestOf(newOwnerSet),
                MiscCryptoUtils.keccak256DigestOf(newMediatorSet),
                lsu.successor,
                lsu.upgradeTime,
                lsu.serial,
                anchor.app());
        return new RotationResult(next.encode(), newMediatorSet, newOwnerSet);
    }

    private static Rotation readRotation(final Slice item) {
        final List<Slice> l = CantonRlp.readList(item);
        if (l.size() != 3) {
            throw CantonProofException.invalidPayloadShape("rotation has " + l.size() + " items");
        }
        final CantonTopology.Transaction tx = CantonTopology.readTransaction(CantonRlp.readBytes(l.get(0)));
        final List<CantonSets.Key> signerKeys = new ArrayList<>();
        if (!isEmpty(l.get(1))) {
            for (final Slice ks : CantonRlp.readList(l.get(1))) {
                final CantonSets.Key key = CantonSets.decodeKey(CantonRlp.readBytes(ks));
                if (!CantonSets.hasOwnFingerprint(key)) {
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

    private static int ownerSignatures(final MOwners owners, final CantonTopology.Transaction t, final int code) {
        int count = 0;
        for (final MOwner o : owners.owners) {
            if (ownerSigned(o, t, code)) {
                ++count;
            }
        }
        return count;
    }

    private static boolean ownerSigned(final MOwner owner, final CantonTopology.Transaction t, final int code) {
        if (CantonTopology.signedBy(t, owner.root)) {
            return true;
        }
        return delegateSigned(owner.delegations, t, code);
    }

    private static boolean delegateSigned(
            final List<MDelegation> delegations, final CantonTopology.Transaction t, final int code) {
        for (final MDelegation d : delegations) {
            if (allows(d, code) && CantonTopology.signedBy(t, d.key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allows(final MDelegation d, final int code) {
        if (d.removed) {
            return false;
        }
        if (d.restriction == 0) {
            return true;
        }
        if (d.restriction == 1) {
            return code != CantonTopology.CODE_NAMESPACE_DELEGATION;
        }
        return d.restriction == 2 && ((d.mappings >> code) & 1) == 1;
    }

    private static void requireOwners(final MOwners owners, final CantonTopology.Transaction t, final int code) {
        if (ownerSignatures(owners, t, code) < owners.threshold) {
            throw CantonProofException.topologyUnauthorized();
        }
    }

    private static boolean rootSigned(final Rotation rot, final byte[] namespace) {
        for (final CantonSets.Key k : rot.signerKeys()) {
            if (Arrays.equals(k.fingerprint(), namespace)) {
                return CantonTopology.signedBy(rot.tx(), k);
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

    // ── Owners ─────────────────────────────────────────────────────────────

    private static MOwners applyOwners(final CantonAnchor.Anchor anchor, final MOwners owners, final Rotation rot) {
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
        final MOwners next =
                new MOwners(new CantonSets.Owners(nextSerial(owners.serial, t), dn.threshold(), List.of()));
        requireOwners(owners, t, CantonTopology.CODE_DECENTRALIZED_NAMESPACE);
        for (final byte[] member : dn.owners()) {
            final MOwner found = owner(owners, member);
            final MOwner o;
            if (found != null) {
                o = found;
            } else {
                o = new MOwner();
                o.namespace = member;
                o.root = signerKey(rot, member);
                if (!CantonTopology.signedBy(t, o.root)) {
                    throw CantonProofException.topologyUnauthorized();
                }
            }
            insertOwner(next.owners, o);
        }
        return next;
    }

    private static MOwner owner(final MOwners owners, final byte[] namespace) {
        for (final MOwner o : owners.owners) {
            if (Arrays.equals(o.namespace, namespace)) {
                return o;
            }
        }
        return null;
    }

    /** Insert {@code o} into {@code list}, kept sorted by namespace; a namespace twice reverts. */
    private static void insertOwner(final List<MOwner> list, final MOwner o) {
        int j = list.size();
        list.add(o);
        while (j > 0 && Arrays.compareUnsigned(list.get(j - 1).namespace, o.namespace) > 0) {
            list.set(j, list.get(j - 1));
            --j;
        }
        if (j > 0 && Arrays.equals(list.get(j - 1).namespace, o.namespace)) {
            throw CantonProofException.invalidTopologyChange();
        }
        list.set(j, o);
    }

    private static CantonSets.Key signerKey(final Rotation rot, final byte[] namespace) {
        for (final CantonSets.Key k : rot.signerKeys()) {
            if (Arrays.equals(k.fingerprint(), namespace)) {
                return k;
            }
        }
        throw CantonProofException.topologyUnauthorized();
    }

    private static void applyOwnerDelegation(final MOwners owners, final CantonTopology.Transaction t) {
        final CantonTopology.NamespaceDelegation nd = CantonTopology.readNamespaceDelegation(t.body());
        final MOwner owner = owner(owners, nd.namespace());
        if (owner == null) {
            throw CantonProofException.invalidTopologyChange();
        }
        if (!CantonTopology.signedBy(t, owner.root)) {
            throw CantonProofException.topologyUnauthorized();
        }
        applyDelegation(owner.delegations, t, nd);
    }

    // ── Mediators ────────────────────────────────────────────────────────────

    private static void applyMediatorState(
            final CantonAnchor.Anchor anchor,
            final MOwners owners,
            final MMediators mediators,
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
        final long serial = nextSerial(mediators.serial, t);
        requireOwners(owners, t, CantonTopology.CODE_MEDIATOR_STATE);
        final List<MMember> next = new ArrayList<>();
        for (final byte[] active : ms.active()) {
            final byte[] memberId = sha256(active);
            final MMember existing = member(mediators, memberId);
            final MMember m;
            if (existing != null) {
                m = existing;
            } else {
                m = new MMember();
                m.memberId = memberId;
            }
            int j = next.size();
            next.add(m);
            while (j > 0 && Arrays.compareUnsigned(next.get(j - 1).memberId, memberId) > 0) {
                next.set(j, next.get(j - 1));
                --j;
            }
            if (j > 0 && Arrays.equals(next.get(j - 1).memberId, memberId)) {
                throw CantonProofException.invalidTopologyChange();
            }
            next.set(j, m);
        }
        mediators.serial = serial;
        mediators.threshold = ms.threshold();
        mediators.members = next;
    }

    private static MMember member(final MMediators mediators, final byte[] memberId) {
        for (final MMember m : mediators.members) {
            if (Arrays.equals(m.memberId, memberId)) {
                return m;
            }
        }
        return null;
    }

    private static void applyOwnerToKey(final MMediators mediators, final Rotation rot) {
        final CantonTopology.Transaction t = rot.tx();
        final CantonTopology.OwnerToKey otk = CantonTopology.readOwnerToKey(t.body());
        final MMember m = member(mediators, sha256(otk.uid()));
        if (m == null) {
            throw CantonProofException.invalidTopologyChange();
        }
        m.otkSerial = nextSerial(m.otkSerial, t);
        if (!rootSigned(rot, CantonTopology.namespaceOfUid(otk.uid()))
                && !delegateSigned(m.delegations, t, CantonTopology.CODE_OWNER_TO_KEY)) {
            throw CantonProofException.topologyUnauthorized();
        }
        if (t.operation() == CantonTopology.OP_REMOVE) {
            m.current = new ArrayList<>();
            m.previous = new ArrayList<>();
            return;
        }
        if (t.operation() != CantonTopology.OP_ADD_REPLACE) {
            throw CantonProofException.rotationsNotSupported();
        }
        for (final CantonSets.Key k : otk.keys()) {
            if (!holds(m.current, k.fingerprint()) && !CantonTopology.signedBy(t, k)) {
                throw CantonProofException.topologyUnauthorized();
            }
        }
        final List<CantonSets.Key> replaced = new ArrayList<>();
        for (final CantonSets.Key c : m.current) {
            if (!holds(otk.keys(), c.fingerprint())) {
                replaced.add(c);
            }
        }
        m.previous = replaced;
        m.current = new ArrayList<>(otk.keys());
    }

    private static boolean holds(final List<CantonSets.Key> keys, final byte[] fingerprint) {
        for (final CantonSets.Key k : keys) {
            if (Arrays.equals(k.fingerprint(), fingerprint)) {
                return true;
            }
        }
        return false;
    }

    private static void applyMediatorDelegation(final MMediators mediators, final Rotation rot) {
        final CantonTopology.Transaction t = rot.tx();
        final CantonTopology.NamespaceDelegation nd = CantonTopology.readNamespaceDelegation(t.body());
        if (!Arrays.equals(nd.namespace(), CantonTopology.namespaceOfUid(rot.mediatorUid()))) {
            throw CantonProofException.invalidTopologyChange();
        }
        final MMember m = member(mediators, sha256(rot.mediatorUid()));
        if (m == null) {
            throw CantonProofException.invalidTopologyChange();
        }
        if (!rootSigned(rot, nd.namespace())) {
            throw CantonProofException.topologyUnauthorized();
        }
        applyDelegation(m.delegations, t, nd);
    }

    // ── Delegations ──────────────────────────────────────────────────────────

    private static void applyDelegation(
            final List<MDelegation> delegations,
            final CantonTopology.Transaction t,
            final CantonTopology.NamespaceDelegation nd) {
        if (!nd.ok() || Arrays.equals(nd.target().fingerprint(), nd.namespace())) {
            throw CantonProofException.rotationsNotSupported();
        }
        for (final MDelegation d : delegations) {
            if (!Arrays.equals(d.key.fingerprint(), nd.target().fingerprint())) {
                continue;
            }
            d.serial = nextSerial(d.serial, t);
            setDelegation(d, t, nd);
            return;
        }
        if (t.operation() != CantonTopology.OP_ADD_REPLACE) {
            throw CantonProofException.invalidTopologyChange();
        }
        final MDelegation grown = new MDelegation();
        grown.serial = nextSerial(0, t);
        setDelegation(grown, t, nd);
        delegations.add(grown);
    }

    private static void setDelegation(
            final MDelegation d, final CantonTopology.Transaction t, final CantonTopology.NamespaceDelegation nd) {
        if (t.operation() == CantonTopology.OP_REMOVE) {
            d.removed = true;
            return;
        }
        if (t.operation() != CantonTopology.OP_ADD_REPLACE) {
            throw CantonProofException.rotationsNotSupported();
        }
        d.key = nd.target();
        d.restriction = nd.restriction();
        d.mappings = nd.mappings();
        d.removed = false;
    }

    // ── Logical Synchronizer Upgrade ───────────────────────────────────────

    private static void applyLsuAnnouncement(
            final CantonAnchor.Anchor anchor, final Lsu lsu, final CantonTopology.Transaction t) {
        final CantonTopology.LsuAnnouncement a = CantonTopology.readLsuAnnouncement(t.body());
        final boolean pending = !lsu.successor.isEmpty();
        if (pending && Long.compareUnsigned(t.serial(), Integer.toUnsignedLong(lsu.serial)) <= 0) {
            throw CantonProofException.invalidLsuAnnouncement();
        }
        if (t.operation() == CantonTopology.OP_REMOVE) {
            if (!pending) {
                throw CantonProofException.invalidLsuAnnouncement();
            }
            lsu.successor = "";
            lsu.upgradeTime = 0;
            lsu.serial = 0;
            return;
        }
        if (t.operation() != CantonTopology.OP_ADD_REPLACE || Long.compareUnsigned(t.serial(), 0xffffffffL) > 0) {
            throw CantonProofException.invalidLsuAnnouncement();
        }
        if (!succeeds(a.successor(), anchor.synchronizerId().getBytes(StandardCharsets.UTF_8))) {
            throw CantonProofException.invalidLsuAnnouncement();
        }
        lsu.successor = new String(a.successor(), StandardCharsets.UTF_8);
        lsu.upgradeTime = a.upgradeTime();
        lsu.serial = (int) t.serial();
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

    // ── Primitives ───────────────────────────────────────────────────────────

    /** {@code ClprCantonRlp.isEmpty}: an empty RLP string or list. */
    private static boolean isEmpty(final Slice s) {
        if (s.len() != 1) {
            return s.len() == 0;
        }
        final int head = s.buf()[s.off()] & 0xff;
        return head == 0x80 || head == 0xc0;
    }

    private static byte[] sha256(final byte[] message) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(message);
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
