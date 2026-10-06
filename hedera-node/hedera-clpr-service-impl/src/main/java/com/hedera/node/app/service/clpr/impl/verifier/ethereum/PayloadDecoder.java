// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the top-level RLP payloads into typed object graphs ({@link BundlePayload},
 * {@link PeerLedgerConfigPayload}, {@link TrustAnchor}) the verifier reads from.
 *
 * <p>This class performs <em>structural</em> validation only — RLP shapes, field counts, and byte
 * lengths. It makes no trust decision: a successfully decoded payload is not a verified one. All
 * cryptographic checks (BLS, SSZ Merkle branches, MPT proofs, supermajority) live in
 * {@link EthereumSyncCommitteeProofVerifier}.
 */
final class PayloadDecoder {

    /**
     * Number of RLP items in the top-level bundle payload list. The current sync committee is no
     * longer carried here — it lives in the trust anchor — so the bundle has one fewer field than
     * the config payload.
     */
    private static final int PAYLOAD_FIELDS = 9;

    // Bundle layout is not yet SC-189-aligned (clpr-smart-contracts EthMainnetVerifier.sol); tracked in #433.
    private static final int PAYLOAD_INDEX_ATTESTED_HEADER = 0;
    private static final int PAYLOAD_INDEX_SYNC_AGGREGATE = 1;
    private static final int PAYLOAD_INDEX_EXECUTION_STATE_ROOT = 2;
    private static final int PAYLOAD_INDEX_EXECUTION_BRANCH = 3;
    private static final int PAYLOAD_INDEX_NEXT_COMMITTEE = 4;
    private static final int PAYLOAD_INDEX_NEXT_COMMITTEE_BRANCH = 5;
    private static final int PAYLOAD_INDEX_ACCOUNT_PROOF = 6;
    private static final int PAYLOAD_INDEX_STORAGE_PROOF = 7;
    private static final int PAYLOAD_INDEX_BUNDLE_CONTENT = 8;
    private static final int PAYLOAD_INDEX_MANIFEST_BYTES = 9;
    private static final int PAYLOAD_INDEX_MANIFEST_STORAGE_PROOF = 10;

    /**
     * Bundle field count when the peer appends an endpoint-manifest advance: the base {@link #PAYLOAD_FIELDS} plus the
     * manifest preimage bytes and the commitment-slot storage proof. A {@link #PAYLOAD_FIELDS}-item bundle carries no
     * manifest advance.
     */
    private static final int PAYLOAD_FIELDS_WITH_MANIFEST = 11;

    private static final int CONFIG_INDEX_SLOT = 0;
    private static final int CONFIG_INDEX_SYNC_COMMITTEE = 1;
    private static final int CONFIG_INDEX_GENESIS_VALIDATORS_ROOT = 2;
    private static final int CONFIG_INDEX_FORK_VERSION = 3;
    private static final int CONFIG_INDEX_LEDGER_CONFIGURATION = 4;
    /**
     * Number of RLP items in the top-level config payload list. The config payload is self-describing — it carries no
     * proof and no signature — so it holds only the initial committee, the chain-pinning fields, and the ledger
     * configuration.
     */
    private static final int CONFIG_PAYLOAD_FIELDS = 5;

    /** Number of RLP items in the trust anchor list. */
    private static final int TRUST_ANCHOR_FIELDS = 4;

    private PayloadDecoder() {}

    // -----------------------------------------------------------------------------------
    // Trust anchor
    // -----------------------------------------------------------------------------------

    @NonNull
    static TrustAnchor decodeTrustAnchor(@NonNull final byte[] trustAnchor) {
        final Rlp.Item item;
        try {
            item = Rlp.decodeOne(trustAnchor);
        } catch (final RuntimeException e) {
            throw EthProofs.fail("trustAnchor is not a valid RLP item: " + e.getMessage());
        }
        if (!item.isList() || item.children().size() != TRUST_ANCHOR_FIELDS) {
            throw EthProofs.fail("trustAnchor is not an RLP list of " + TRUST_ANCHOR_FIELDS
                    + " items [syncCommittee, genesisValidatorsRoot, forkVersion, serviceAddress]");
        }
        final SyncCommittee committee = decodeSyncCommittee(item.children().get(0));
        final byte[] genesisValidatorsRoot =
                fixedBytes(item.children().get(1), 32, "trustAnchor.genesisValidatorsRoot");
        final byte[] forkVersion =
                fixedBytes(item.children().get(2), Ssz.FORK_VERSION_LENGTH, "trustAnchor.forkVersion");
        final byte[] serviceAddress =
                fixedBytes(item.children().get(3), TrustAnchor.SERVICE_ADDRESS_LENGTH, "trustAnchor.serviceAddress");
        return new TrustAnchor(committee, genesisValidatorsRoot, forkVersion, serviceAddress);
    }

    // -----------------------------------------------------------------------------------
    // Top-level payloads
    // -----------------------------------------------------------------------------------

    @NonNull
    static BundlePayload decodeBundle(@NonNull final byte[] bundlePayload) {
        final Rlp.Item top;
        try {
            top = Rlp.decodeOne(bundlePayload);
        } catch (final RuntimeException e) {
            throw EthProofs.fail("payload is not a valid RLP item", e);
        }
        final int childCount = top.isList() ? top.children().size() : -1;
        if (childCount != PAYLOAD_FIELDS && childCount != PAYLOAD_FIELDS_WITH_MANIFEST) {
            throw EthProofs.fail("expected top-level RLP list of " + PAYLOAD_FIELDS + " or "
                    + PAYLOAD_FIELDS_WITH_MANIFEST + " items, got "
                    + (top.isList() ? top.children().size() + " items" : "non-list"));
        }
        final List<Rlp.Item> fields = top.children();

        final BeaconHeader attestedHeader = decodeBeaconHeader(fields.get(PAYLOAD_INDEX_ATTESTED_HEADER));
        final SyncAggregate syncAggregate = decodeSyncAggregate(fields.get(PAYLOAD_INDEX_SYNC_AGGREGATE));
        final byte[] executionStateRoot32 =
                fixedBytes(fields.get(PAYLOAD_INDEX_EXECUTION_STATE_ROOT), 32, "executionStateRoot");
        final byte[][] executionBranch = decodeBranch(
                fields.get(PAYLOAD_INDEX_EXECUTION_BRANCH), Ssz.EXECUTION_STATE_ROOT_BRANCH_DEPTH, "executionBranch");

        // Optional rotation proof: both items present (the next committee + its branch) or both absent.
        final Rlp.Item committeeItem = fields.get(PAYLOAD_INDEX_NEXT_COMMITTEE);
        final Rlp.Item branchItem = fields.get(PAYLOAD_INDEX_NEXT_COMMITTEE_BRANCH);
        final boolean committeeAbsent = !committeeItem.isList() && committeeItem.asBytes().length == 0;
        final boolean branchAbsent =
                branchItem.isList() && branchItem.children().isEmpty();
        if (committeeAbsent != branchAbsent) {
            throw EthProofs.fail("nextSyncCommittee and nextSyncCommitteeBranch must be both present or both absent");
        }
        final SyncCommittee nextCommittee = committeeAbsent ? null : decodeSyncCommittee(committeeItem);
        final byte[][] nextCommitteeBranch = committeeAbsent
                ? null
                : decodeBranch(branchItem, Ssz.NEXT_SYNC_COMMITTEE_BRANCH_DEPTH, "nextSyncCommitteeBranch");

        final byte[][] accountProof = decodeNodeList(fields.get(PAYLOAD_INDEX_ACCOUNT_PROOF), "accountProof");
        // Optional queue storage proof: the five queue slots for a normal bundle, or EMPTY for a manifest-only
        // recovery bundle (spec §8.1.4) — a normal-shaped bundle that carries only a manifest advance, with no
        // queue state and no content. The verifier reads an empty proof as absent queue metadata. (Content-based:
        // manifest-only is not a distinct wire shape, so it decodes as a normal bundle.)
        final List<StorageProofEntry> storageProof = decodeStorageProofList(fields.get(PAYLOAD_INDEX_STORAGE_PROOF));
        if (!storageProof.isEmpty() && storageProof.size() != QueueMetadata.EXPECTED_SLOTS) {
            throw EthProofs.fail("storageProof has " + storageProof.size() + " entries; expected "
                    + QueueMetadata.EXPECTED_SLOTS + " (last-msg running hash + 4 channel-metadata slots) "
                    + "or 0 (manifest-only recovery)");
        }
        final byte[] bundleContent = bytes(fields.get(PAYLOAD_INDEX_BUNDLE_CONTENT), "bundleContent");

        // Optional endpoint-manifest advance (spec §4.9): the manifest preimage plus a storage proof of its
        // commitment slot. Present iff the bundle is the 11-item form.
        final boolean hasManifest = childCount == PAYLOAD_FIELDS_WITH_MANIFEST;
        final byte[] manifestBytes =
                hasManifest ? bytes(fields.get(PAYLOAD_INDEX_MANIFEST_BYTES), "manifestBytes") : null;
        final StorageProofEntry manifestStorageProof = hasManifest
                ? decodeStorageProofEntry(fields.get(PAYLOAD_INDEX_MANIFEST_STORAGE_PROOF), "manifestStorageProof")
                : null;

        return new BundlePayload(
                attestedHeader,
                syncAggregate,
                executionStateRoot32,
                executionBranch,
                nextCommittee,
                nextCommitteeBranch,
                accountProof,
                storageProof,
                bundleContent,
                manifestBytes,
                manifestStorageProof);
    }

    @NonNull
    static PeerLedgerConfigPayload decodeConfig(@NonNull final byte[] configPayload) {
        final Rlp.Item top;
        try {
            top = Rlp.decodeOne(configPayload);
        } catch (final RuntimeException e) {
            throw EthProofs.fail("configPayload is not a valid RLP item", e);
        }
        if (!top.isList() || top.children().size() != CONFIG_PAYLOAD_FIELDS) {
            throw EthProofs.fail("expected top-level RLP list of " + CONFIG_PAYLOAD_FIELDS + " items, got "
                    + (top.isList() ? top.children().size() + " items" : "non-list"));
        }
        final List<Rlp.Item> fields = top.children();

        final long slot = decodeUint64(fields.get(CONFIG_INDEX_SLOT), "config.slot");
        final SyncCommittee committee = decodeSyncCommittee(fields.get(CONFIG_INDEX_SYNC_COMMITTEE));
        final byte[] genesisValidatorsRoot32 =
                fixedBytes(fields.get(CONFIG_INDEX_GENESIS_VALIDATORS_ROOT), 32, "genesisValidatorsRoot");
        final byte[] forkVersion4 =
                fixedBytes(fields.get(CONFIG_INDEX_FORK_VERSION), Ssz.FORK_VERSION_LENGTH, "forkVersion");
        final byte[] ledgerConfigBytes = bytes(fields.get(CONFIG_INDEX_LEDGER_CONFIGURATION), "ledgerConfiguration");

        return new PeerLedgerConfigPayload(slot, committee, genesisValidatorsRoot32, forkVersion4, ledgerConfigBytes);
    }

    // -----------------------------------------------------------------------------------
    // Field decoders
    // -----------------------------------------------------------------------------------

    /**
     * Beacon header SSZ schema {@code [slot, proposerIndex, parentRoot, stateRoot, bodyRoot]}:
     * two {@code uint64} scalars followed by three 32-byte roots.
     */
    @NonNull
    private static BeaconHeader decodeBeaconHeader(@NonNull final Rlp.Item item) {
        if (!item.isList() || item.children().size() != Ssz.BEACON_HEADER_FIELDS) {
            throw EthProofs.fail("attestedHeader is not an RLP list of " + Ssz.BEACON_HEADER_FIELDS
                    + " fields [slot, proposerIndex, parentRoot, stateRoot, bodyRoot]");
        }
        final List<Rlp.Item> fields = item.children();
        return new BeaconHeader(
                decodeUint64(fields.get(0), "attestedHeader.slot"),
                decodeUint64(fields.get(1), "attestedHeader.proposerIndex"),
                fixedBytes(fields.get(2), 32, "attestedHeader.parentRoot"),
                fixedBytes(fields.get(3), 32, "attestedHeader.stateRoot"),
                fixedBytes(fields.get(4), 32, "attestedHeader.bodyRoot"));
    }

    /**
     * Sync committee schema {@code [pubkeys[512], aggregatePubkey]}: a list of exactly 512 48-byte
     * pubkeys plus the precomputed 48-byte aggregate.
     */
    @NonNull
    private static SyncCommittee decodeSyncCommittee(@NonNull final Rlp.Item item) {
        if (!item.isList() || item.children().size() != 2) {
            throw EthProofs.fail("syncCommittee is not an RLP list of 2 items [pubkeys[], aggregatePubkey]");
        }
        final Rlp.Item pubkeysItem = item.children().get(0);
        if (!pubkeysItem.isList() || pubkeysItem.children().size() != Ssz.SYNC_COMMITTEE_SIZE) {
            throw EthProofs.fail(
                    "syncCommittee.pubkeys must be an RLP list of " + Ssz.SYNC_COMMITTEE_SIZE + " items, got "
                            + (pubkeysItem.isList() ? pubkeysItem.children().size() + " items" : "non-list"));
        }
        final byte[][] pubkeys = new byte[Ssz.SYNC_COMMITTEE_SIZE][];
        for (int i = 0; i < Ssz.SYNC_COMMITTEE_SIZE; i++) {
            pubkeys[i] = fixedBytes(
                    pubkeysItem.children().get(i), Ssz.BLS_PUBKEY_LENGTH, "syncCommittee.pubkeys[" + i + "]");
        }
        final byte[] aggregatePubkey =
                fixedBytes(item.children().get(1), Ssz.BLS_PUBKEY_LENGTH, "syncCommittee.aggregatePubkey");
        return new SyncCommittee(pubkeys, aggregatePubkey);
    }

    /** Sync aggregate schema {@code [bits(64), signature(96)]}. */
    @NonNull
    private static SyncAggregate decodeSyncAggregate(@NonNull final Rlp.Item item) {
        if (!item.isList() || item.children().size() != 2) {
            throw EthProofs.fail("syncAggregate is not an RLP list of 2 items [bits, signature]");
        }
        final byte[] bits = fixedBytes(item.children().get(0), Ssz.SYNC_COMMITTEE_BITS_LENGTH, "syncAggregate.bits");
        final byte[] signature =
                fixedBytes(item.children().get(1), Ssz.BLS_SIGNATURE_LENGTH, "syncAggregate.signature");
        return new SyncAggregate(bits, signature);
    }

    /**
     * Decodes an SSZ Merkle branch: an RLP list of exactly {@code expectedDepth} sibling hashes of
     * 32 bytes each, ordered leaf-to-root.
     */
    @NonNull
    private static byte[][] decodeBranch(
            @NonNull final Rlp.Item item, final int expectedDepth, @NonNull final String name) {
        if (!item.isList()) {
            throw EthProofs.fail(name + " is not an RLP list");
        }
        final List<Rlp.Item> children = item.children();
        if (children.size() != expectedDepth) {
            throw EthProofs.fail(name + " must have " + expectedDepth + " nodes, got " + children.size());
        }
        final byte[][] out = new byte[expectedDepth][];
        for (int i = 0; i < expectedDepth; i++) {
            out[i] = fixedBytes(children.get(i), 32, name + "[" + i + "]");
        }
        return out;
    }

    /** Decodes an RLP list of opaque byte strings into a {@code byte[][]} for MPT consumption. */
    @NonNull
    private static byte[][] decodeNodeList(@NonNull final Rlp.Item item, @NonNull final String name) {
        if (!item.isList()) {
            throw EthProofs.fail(name + " is not an RLP list");
        }
        final List<Rlp.Item> children = item.children();
        final byte[][] out = new byte[children.size()][];
        for (int i = 0; i < children.size(); i++) {
            out[i] = bytes(children.get(i), name + "[" + i + "]");
        }
        return out;
    }

    @NonNull
    private static List<StorageProofEntry> decodeStorageProofList(@NonNull final Rlp.Item item) {
        if (!item.isList()) {
            throw EthProofs.fail("storageProof is not an RLP list");
        }
        final List<Rlp.Item> entries = item.children();
        final List<StorageProofEntry> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            out.add(decodeStorageProofEntry(entries.get(i), "storageProof[" + i + "]"));
        }
        return List.copyOf(out);
    }

    /** Decodes a single {@code [key, proof[]]} storage-proof entry. */
    @NonNull
    private static StorageProofEntry decodeStorageProofEntry(
            @NonNull final Rlp.Item entry, @NonNull final String name) {
        if (!entry.isList() || entry.children().size() != 2) {
            throw EthProofs.fail(name + " is not a [key, proof[]] RLP list");
        }
        return new StorageProofEntry(
                bytes(entry.children().get(0), name + ".key"),
                decodeNodeList(entry.children().get(1), name + ".proof"));
    }

    private static long decodeUint64(@NonNull final Rlp.Item item, @NonNull final String name) {
        final byte[] bytes = bytes(item, name);
        if (bytes.length > 8) {
            throw EthProofs.fail(name + " is longer than 8 bytes (" + bytes.length + ")");
        }
        // RLP integers are minimally encoded: a leading zero byte is non-canonical.
        if (bytes.length > 0 && bytes[0] == 0) {
            throw EthProofs.fail(name + " has a non-canonical leading zero byte");
        }
        long value = 0;
        for (final byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    /**
     * Reads a variable-length RLP byte string, raising a {@link ProofException} (the verifier's
     * malformed-proof surface).
     */
    @NonNull
    private static byte[] bytes(@NonNull final Rlp.Item item, @NonNull final String name) {
        if (item.isList()) {
            throw EthProofs.fail(name + " must be an RLP string, not a list");
        }
        return item.asBytes();
    }

    /**
     * Reads a fixed-length RLP byte string, raising a {@link ProofException} on a wrong length or a
     * list — so malformed external proof fields surface consistently as proof failures rather than
     * as {@link IllegalArgumentException} escaping the verifier.
     */
    @NonNull
    private static byte[] fixedBytes(@NonNull final Rlp.Item item, final int length, @NonNull final String name) {
        final byte[] value = bytes(item, name);
        if (value.length != length) {
            throw EthProofs.fail(name + " must be " + length + " bytes, got " + value.length);
        }
        return value;
    }
}
