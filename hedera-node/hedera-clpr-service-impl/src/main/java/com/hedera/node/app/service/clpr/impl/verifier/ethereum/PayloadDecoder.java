// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.EthereumPosBeaconBlockHeader;
import com.hedera.hapi.node.state.clpr.EthereumPosCommitteeAnchor;
import com.hedera.hapi.node.state.clpr.EthereumPosLedgerConfigurationPayload;
import com.hedera.hapi.node.state.clpr.EthereumPosNonSignerProof;
import com.hedera.hapi.node.state.clpr.EthereumPosSyncAggregate;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.CommitteeRotation;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.FinalizedExecution;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.MessageDelivery;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.NextCommittee;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.ServiceAccountProof;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.SignedHeader;
import com.hedera.node.app.service.clpr.impl.verifier.evm.ClprContractStorage;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Decodes the bundle payload (RLP), the config payload (protobuf) and the config's endpoint-manifest proof (RLP) into
 * the typed object graphs ({@link BundlePayload}, {@link PeerLedgerConfigPayload}, {@link ConfigManifestProof}) the
 * verifier reads from.
 *
 * <p>This class performs <em>structural</em> validation only — RLP shapes, field counts, and byte
 * lengths. It makes no trust decision: a successfully decoded payload is not a verified one. All
 * cryptographic checks (BLS, SSZ Merkle branches, MPT proofs, supermajority) live in
 * {@link EthereumSyncCommitteeProofVerifier}.
 */
final class PayloadDecoder {

    private static final String NOT_AN_RLP_LIST = " is not an RLP list";

    /**
     * The bundle payload is {@code [rotations, delivery]}.
     */
    private static final int BUNDLE_FIELDS = 2;

    private static final int BUNDLE_INDEX_ROTATIONS = 0;
    private static final int BUNDLE_INDEX_DELIVERY = 1;

    /**
     * A rotation is {@code [attestedHeader, syncAggregate, nonSignerProofs, finalizedHeader, finalityBranch,
     * nextCommittee, nextCommitteeBranch]}.
     */
    private static final int ROTATION_FIELDS = 7;

    private static final int ROTATION_INDEX_ATTESTED_HEADER = 0;
    private static final int ROTATION_INDEX_SYNC_AGGREGATE = 1;
    private static final int ROTATION_INDEX_NON_SIGNER_PROOFS = 2;
    private static final int ROTATION_INDEX_FINALIZED_HEADER = 3;
    private static final int ROTATION_INDEX_FINALITY_BRANCH = 4;
    private static final int ROTATION_INDEX_NEXT_COMMITTEE = 5;
    private static final int ROTATION_INDEX_NEXT_COMMITTEE_BRANCH = 6;

    /**
     * A delivery is {@code [attestedHeader, signatureSlot, syncAggregate, nonSignerProofs, finalizedExecution,
     * finalityBranch, accountProof, storageProof, bundleContent, manifestProof, manifestPreimage]}. The manifest pair is
     * always present: an empty list and an empty string when the bundle carries no manifest update.
     */
    private static final int DELIVERY_FIELDS = 11;

    private static final int DELIVERY_INDEX_ATTESTED_HEADER = 0;
    private static final int DELIVERY_INDEX_SIGNATURE_SLOT = 1;
    private static final int DELIVERY_INDEX_SYNC_AGGREGATE = 2;
    private static final int DELIVERY_INDEX_NON_SIGNER_PROOFS = 3;
    private static final int DELIVERY_INDEX_FINALIZED_EXECUTION = 4;
    private static final int DELIVERY_INDEX_FINALITY_BRANCH = 5;
    private static final int DELIVERY_INDEX_ACCOUNT_PROOF = 6;
    private static final int DELIVERY_INDEX_STORAGE_PROOF = 7;
    private static final int DELIVERY_INDEX_BUNDLE_CONTENT = 8;
    private static final int DELIVERY_INDEX_MANIFEST_PROOF = 9;
    private static final int DELIVERY_INDEX_MANIFEST_PREIMAGE = 10;

    /**
     * A config's endpoint-manifest proof is {@code [attestedHeader, signatureSlot, syncAggregate, nonSignerProofs,
     * finalizedExecution, finalityBranch, accountProof, manifestProof, manifestPreimage]}. Its first seven items sit at
     * a delivery's positions, so they are read with the {@code DELIVERY_INDEX_*} constants.
     */
    private static final int CONFIG_MANIFEST_PROOF_FIELDS = 9;

    private static final int CONFIG_MANIFEST_PROOF_INDEX_MANIFEST_PROOF = 7;
    private static final int CONFIG_MANIFEST_PROOF_INDEX_MANIFEST_PREIMAGE = 8;

    /**
     * A beacon header is {@code [slot, proposerIndex, parentRoot, stateRoot, bodyRoot]}, its SSZ field order.
     */
    private static final int BEACON_HEADER_INDEX_SLOT = 0;

    private static final int BEACON_HEADER_INDEX_PROPOSER_INDEX = 1;
    private static final int BEACON_HEADER_INDEX_PARENT_ROOT = 2;
    private static final int BEACON_HEADER_INDEX_STATE_ROOT = 3;
    private static final int BEACON_HEADER_INDEX_BODY_ROOT = 4;

    /**
     * {@code syncAggregate} is {@code [bits, signature]}.
     */
    private static final int SYNC_AGGREGATE_FIELDS = 2;

    private static final int SYNC_AGGREGATE_INDEX_BITS = 0;
    private static final int SYNC_AGGREGATE_INDEX_SIGNATURE = 1;

    /**
     * {@code nextCommittee} is {@code [pubkeysRoot, aggregatePubkey]}.
     */
    private static final int NEXT_COMMITTEE_FIELDS = 2;

    private static final int NEXT_COMMITTEE_INDEX_PUBKEYS_ROOT = 0;
    private static final int NEXT_COMMITTEE_INDEX_AGGREGATE_PUBKEY = 1;

    /**
     * {@code finalizedExecution} is {@code [finalizedHeader, executionBranch, blockHeaderRlp]}.
     */
    private static final int FINALIZED_EXECUTION_FIELDS = 3;

    private static final int FINALIZED_EXECUTION_INDEX_HEADER = 0;
    private static final int FINALIZED_EXECUTION_INDEX_BRANCH = 1;
    private static final int FINALIZED_EXECUTION_INDEX_BLOCK_HEADER = 2;

    /**
     * Length of a CLPR service contract address.
     */
    private static final int SERVICE_ADDRESS_LENGTH = 20;

    /** Length of a root, hash or branch sibling. */
    private static final int HASH_LENGTH = 32;

    private PayloadDecoder() {}

    // -----------------------------------------------------------------------------------
    // Top-level payloads
    // -----------------------------------------------------------------------------------

    /**
     * Decodes a bundle payload whose committee sizes follow {@code preset}.
     *
     * @throws ProofException if the payload is not well-formed
     */
    @NonNull
    static BundlePayload decodeBundle(@NonNull final byte[] bundlePayload, @NonNull final SyncCommitteePreset preset) {
        final Rlp.Item top;
        try {
            top = Rlp.decodeOne(bundlePayload);
        } catch (final RuntimeException e) {
            throw EthProofs.fail("payload is not a valid RLP item", e);
        }
        final List<Rlp.Item> fields = list(top, BUNDLE_FIELDS, "payload [rotations, delivery]");
        final Rlp.Item rotationsItem = fields.get(BUNDLE_INDEX_ROTATIONS);
        if (!rotationsItem.isList()) {
            throw EthProofs.fail("rotations is not an RLP list");
        }
        final List<CommitteeRotation> rotations =
                new ArrayList<>(rotationsItem.children().size());
        for (int i = 0; i < rotationsItem.children().size(); i++) {
            final CommitteeRotation rotation =
                    decodeRotation(rotationsItem.children().get(i), preset, "rotations[" + i + "]");
            rotations.add(rotation);
        }
        final MessageDelivery delivery = decodeDelivery(fields.get(BUNDLE_INDEX_DELIVERY), preset);
        return new BundlePayload(Collections.unmodifiableList(rotations), delivery);
    }

    /**
     * Decodes a protobuf {@code EthereumPosLedgerConfigurationPayload} and checks its structure: the sync aggregate,
     * whose bits length selects a supported preset, two committees with a 32-byte keys root and a 48-byte aggregate key,
     * the update's non-signer proofs with 128-byte keys and branches at the preset's depth, the anchor and update
     * headers, both committee branches at the Gloas depth, a 32-byte genesis validators root, a 4-byte fork version, a
     * 32-byte code hash, and a ledger configuration with a 20-byte service address.
     *
     * @throws ProofException if the payload is not well-formed
     */
    @NonNull
    static PeerLedgerConfigPayload decodeConfig(@NonNull final byte[] configPayload) {
        final EthereumPosLedgerConfigurationPayload payload;
        try {
            // Spec §1: "Implementations MUST reject messages containing unrecognized fields." The strict parse
            // propagates to the nested messages.
            payload = EthereumPosLedgerConfigurationPayload.PROTOBUF.parseStrict(
                    Bytes.wrap(configPayload).toReadableSequentialData());
        } catch (final ParseException | RuntimeException e) {
            throw EthProofs.fail("configPayload is not a valid EthereumPosLedgerConfigurationPayload", e);
        }
        requirePresent(payload.hasSyncCommittee(), "sync_committee");
        requirePresent(payload.hasLedgerConfiguration(), "ledger_configuration");
        requirePresent(payload.hasAnchorHeader(), "anchor_header");
        requirePresent(payload.hasUpdateHeader(), "update_header");
        requirePresent(payload.hasSyncAggregate(), "sync_aggregate");
        requirePresent(payload.hasNextSyncCommittee(), "next_sync_committee");
        final SyncCommitteePreset preset = SyncCommitteePreset.fromParticipationBytes(
                (int) payload.syncAggregateOrThrow().bits().length());
        final ClprLedgerConfiguration ledgerConfiguration = payload.ledgerConfigurationOrThrow();
        fixedBytes(
                ledgerConfiguration.serviceAddress(), SERVICE_ADDRESS_LENGTH, "ledger_configuration.service_address");
        return new PeerLedgerConfigPayload(
                payload.slot(),
                preset,
                toSyncCommittee(payload.syncCommitteeOrThrow(), "sync_committee"),
                toBeaconHeader(payload.anchorHeaderOrThrow(), "anchor_header"),
                toBranch(
                        payload.currentSyncCommitteeBranch(),
                        Gloas.CURRENT_SYNC_COMMITTEE.depth(),
                        "current_sync_committee_branch"),
                toBeaconHeader(payload.updateHeaderOrThrow(), "update_header"),
                toSyncAggregate(payload.syncAggregateOrThrow(), preset, "sync_aggregate"),
                toNonSignerProofs(payload.syncAggregateNonSignerProofs(), preset),
                toSyncCommittee(payload.nextSyncCommitteeOrThrow(), "next_sync_committee"),
                toBranch(
                        payload.nextSyncCommitteeBranch(),
                        Gloas.NEXT_SYNC_COMMITTEE.depth(),
                        "next_sync_committee_branch"),
                fixedBytes(payload.genesisValidatorsRoot(), HASH_LENGTH, "genesis_validators_root"),
                fixedBytes(payload.forkVersion(), Ssz.FORK_VERSION_LENGTH, "fork_version"),
                fixedBytes(payload.codeHash(), HASH_LENGTH, "code_hash"),
                ledgerConfiguration);
    }

    /**
     * Decodes a config's endpoint-manifest proof whose committee sizes follow {@code preset}. Unlike a delivery's, its
     * manifest storage proof must hold exactly one entry.
     *
     * @throws ProofException if the proof is not well-formed
     */
    @NonNull
    static ConfigManifestProof decodeConfigManifestProof(
            @NonNull final byte[] manifestProof, @NonNull final SyncCommitteePreset preset) {
        final Rlp.Item top;
        try {
            top = Rlp.decodeOne(manifestProof);
        } catch (final RuntimeException e) {
            throw EthProofs.fail("endpointManifestProof is not a valid RLP item", e);
        }
        final String name = "endpointManifestProof";
        final List<Rlp.Item> fields = list(top, CONFIG_MANIFEST_PROOF_FIELDS, name);
        final List<StorageProofEntry> manifestStorageProof =
                decodeStorageProofList(fields.get(CONFIG_MANIFEST_PROOF_INDEX_MANIFEST_PROOF), name + ".manifestProof");
        if (manifestStorageProof.size() != 1) {
            throw EthProofs.fail(
                    name + ".manifestProof must hold exactly one entry, got " + manifestStorageProof.size());
        }
        return new ConfigManifestProof(
                decodeServiceAccountProof(fields, preset, name),
                manifestStorageProof.getFirst(),
                bytes(fields.get(CONFIG_MANIFEST_PROOF_INDEX_MANIFEST_PREIMAGE), name + ".manifestPreimage"));
    }

    // -----------------------------------------------------------------------------------
    // Bundle items
    // -----------------------------------------------------------------------------------

    @NonNull
    private static CommitteeRotation decodeRotation(
            @NonNull final Rlp.Item item, @NonNull final SyncCommitteePreset preset, @NonNull final String name) {
        final List<Rlp.Item> fields = list(item, ROTATION_FIELDS, name);
        return new CommitteeRotation(
                decodeSignedHeader(
                        fields.get(ROTATION_INDEX_ATTESTED_HEADER),
                        fields.get(ROTATION_INDEX_SYNC_AGGREGATE),
                        fields.get(ROTATION_INDEX_NON_SIGNER_PROOFS),
                        preset,
                        name),
                decodeBeaconHeader(fields.get(ROTATION_INDEX_FINALIZED_HEADER), name + ".finalizedHeader"),
                decodeBranch(
                        fields.get(ROTATION_INDEX_FINALITY_BRANCH),
                        Gloas.FINALIZED_ROOT.depth(),
                        name + ".finalityBranch"),
                decodeNextCommittee(fields.get(ROTATION_INDEX_NEXT_COMMITTEE), name + ".nextCommittee"),
                decodeBranch(
                        fields.get(ROTATION_INDEX_NEXT_COMMITTEE_BRANCH),
                        Gloas.NEXT_SYNC_COMMITTEE.depth(),
                        name + ".nextCommitteeBranch"));
    }

    @NonNull
    private static MessageDelivery decodeDelivery(
            @NonNull final Rlp.Item item, @NonNull final SyncCommitteePreset preset) {
        final List<Rlp.Item> fields = list(item, DELIVERY_FIELDS, "delivery");
        final ServiceAccountProof serviceAccount = decodeServiceAccountProof(fields, preset, "delivery");

        // Optional queue storage proof: the five Channel slots, plus the last-message running-hash slot for a
        // message-bearing bundle, or EMPTY for a manifest-only recovery bundle (spec §8.1.4) — a normal-shaped
        // bundle that carries only a manifest advance, with no queue state and no content. The verifier reads an
        // empty proof as absent queue metadata.
        final List<StorageProofEntry> storageProof =
                decodeStorageProofList(fields.get(DELIVERY_INDEX_STORAGE_PROOF), "delivery.storageProof");
        if (!storageProof.isEmpty()
                && storageProof.size() != ClprContractStorage.CHANNEL_SLOTS
                && storageProof.size() != ClprContractStorage.EXPECTED_SLOTS_WITH_MESSAGE) {
            throw EthProofs.fail("delivery.storageProof has " + storageProof.size() + " entries; expected "
                    + ClprContractStorage.CHANNEL_SLOTS
                    + " (five Channel slots incl. endpointManifestVersion) or "
                    + ClprContractStorage.EXPECTED_SLOTS_WITH_MESSAGE + " (+ last-message running hash), "
                    + "or 0 (manifest-only recovery)");
        }
        final byte[] bundleContent = bytes(fields.get(DELIVERY_INDEX_BUNDLE_CONTENT), "delivery.bundleContent");

        // Optional endpoint-manifest update (spec §4.9): a one-entry proof list plus the preimage, or an empty list
        // plus an empty string. One without the other is malformed.
        final List<StorageProofEntry> manifestProof =
                decodeStorageProofList(fields.get(DELIVERY_INDEX_MANIFEST_PROOF), "delivery.manifestProof");
        final byte[] manifestPreimage =
                bytes(fields.get(DELIVERY_INDEX_MANIFEST_PREIMAGE), "delivery.manifestPreimage");
        if (manifestProof.size() > 1) {
            throw EthProofs.fail("delivery.manifestProof must hold at most one entry, got " + manifestProof.size());
        }
        final boolean hasManifestProof = !manifestProof.isEmpty();
        if (hasManifestProof != (manifestPreimage.length > 0)) {
            throw EthProofs.fail(
                    "delivery.manifestProof and delivery.manifestPreimage must be both present or both empty");
        }

        return new MessageDelivery(
                serviceAccount,
                storageProof,
                bundleContent,
                hasManifestProof ? manifestProof.getFirst() : null,
                hasManifestProof ? manifestPreimage : null);
    }

    /**
     * Decodes the seven items a delivery and a config's endpoint-manifest proof share, at the {@code DELIVERY_INDEX_*}
     * positions of {@code fields}.
     */
    @NonNull
    private static ServiceAccountProof decodeServiceAccountProof(
            @NonNull final List<Rlp.Item> fields,
            @NonNull final SyncCommitteePreset preset,
            @NonNull final String name) {
        return new ServiceAccountProof(
                decodeSignedHeader(
                        fields.get(DELIVERY_INDEX_ATTESTED_HEADER),
                        fields.get(DELIVERY_INDEX_SYNC_AGGREGATE),
                        fields.get(DELIVERY_INDEX_NON_SIGNER_PROOFS),
                        preset,
                        name),
                decodeUint64(fields.get(DELIVERY_INDEX_SIGNATURE_SLOT), name + ".signatureSlot"),
                decodeFinalizedExecution(fields.get(DELIVERY_INDEX_FINALIZED_EXECUTION), name + ".finalizedExecution"),
                decodeBranch(
                        fields.get(DELIVERY_INDEX_FINALITY_BRANCH),
                        Gloas.FINALIZED_ROOT.depth(),
                        name + ".finalityBranch"),
                decodeNodeList(fields.get(DELIVERY_INDEX_ACCOUNT_PROOF), name + ".accountProof"));
    }

    /**
     * Decodes the signed header shared by a rotation and a delivery.
     */
    @NonNull
    private static SignedHeader decodeSignedHeader(
            @NonNull final Rlp.Item attestedHeader,
            @NonNull final Rlp.Item syncAggregate,
            @NonNull final Rlp.Item nonSignerProofs,
            @NonNull final SyncCommitteePreset preset,
            @NonNull final String name) {
        return new SignedHeader(
                decodeBeaconHeader(attestedHeader, name + ".attestedHeader"),
                decodeSyncAggregate(syncAggregate, preset, name + ".syncAggregate"),
                decodeNonSignerProofs(nonSignerProofs, preset, name + ".nonSignerProofs"));
    }

    /**
     * Beacon header SSZ schema {@code [slot, proposerIndex, parentRoot, stateRoot, bodyRoot]}:
     * two {@code uint64} scalars followed by three 32-byte roots.
     */
    @NonNull
    private static BeaconHeader decodeBeaconHeader(@NonNull final Rlp.Item item, @NonNull final String name) {
        final List<Rlp.Item> fields = list(item, Ssz.BEACON_HEADER_FIELDS, name);
        return new BeaconHeader(
                decodeUint64(fields.get(BEACON_HEADER_INDEX_SLOT), name + ".slot"),
                decodeUint64(fields.get(BEACON_HEADER_INDEX_PROPOSER_INDEX), name + ".proposerIndex"),
                fixedBytes(fields.get(BEACON_HEADER_INDEX_PARENT_ROOT), HASH_LENGTH, name + ".parentRoot"),
                fixedBytes(fields.get(BEACON_HEADER_INDEX_STATE_ROOT), HASH_LENGTH, name + ".stateRoot"),
                fixedBytes(fields.get(BEACON_HEADER_INDEX_BODY_ROOT), HASH_LENGTH, name + ".bodyRoot"));
    }

    /**
     * Sync aggregate schema {@code [bits(committee size / 8), signature(256, EIP-2537)]}.
     */
    @NonNull
    private static SyncAggregate decodeSyncAggregate(
            @NonNull final Rlp.Item item, @NonNull final SyncCommitteePreset preset, @NonNull final String name) {
        final List<Rlp.Item> fields = list(item, SYNC_AGGREGATE_FIELDS, name + " [bits, signature]");
        return new SyncAggregate(
                fixedBytes(fields.get(SYNC_AGGREGATE_INDEX_BITS), preset.participationBytes(), name + ".bits"),
                fixedBytes(
                        fields.get(SYNC_AGGREGATE_INDEX_SIGNATURE),
                        SyncAggregate.SIGNATURE_LENGTH,
                        name + ".signature"));
    }

    /**
     * Non-signer proofs: an RLP list of byte strings, each a 128-byte uncompressed key followed by the
     * {@code log2(committee size)} sibling hashes of its leaf, bottom-up.
     */
    @NonNull
    private static List<NonSignerProof> decodeNonSignerProofs(
            @NonNull final Rlp.Item item, @NonNull final SyncCommitteePreset preset, @NonNull final String name) {
        if (!item.isList()) {
            throw EthProofs.fail(name + NOT_AN_RLP_LIST);
        }
        final int depth = preset.pubkeysBranchDepth();
        final int entryLength = G1Compression.UNCOMPRESSED_LENGTH + depth * HASH_LENGTH;
        final List<NonSignerProof> out = new ArrayList<>(item.children().size());
        for (int i = 0; i < item.children().size(); i++) {
            final byte[] entry = fixedBytes(item.children().get(i), entryLength, name + "[" + i + "]");
            final byte[][] branch = new byte[depth][];
            for (int level = 0; level < depth; level++) {
                final int from = G1Compression.UNCOMPRESSED_LENGTH + level * HASH_LENGTH;
                branch[level] = Arrays.copyOfRange(entry, from, from + HASH_LENGTH);
            }
            out.add(new NonSignerProof(Arrays.copyOf(entry, G1Compression.UNCOMPRESSED_LENGTH), branch));
        }
        return List.copyOf(out);
    }

    /**
     * Next committee schema {@code [pubkeysRoot(32), aggregatePubkey(128)]}.
     */
    @NonNull
    private static NextCommittee decodeNextCommittee(@NonNull final Rlp.Item item, @NonNull final String name) {
        final List<Rlp.Item> fields = list(item, NEXT_COMMITTEE_FIELDS, name + " [pubkeysRoot, aggregatePubkey]");
        return new NextCommittee(
                fixedBytes(fields.get(NEXT_COMMITTEE_INDEX_PUBKEYS_ROOT), HASH_LENGTH, name + ".pubkeysRoot"),
                fixedBytes(
                        fields.get(NEXT_COMMITTEE_INDEX_AGGREGATE_PUBKEY),
                        G1Compression.UNCOMPRESSED_LENGTH,
                        name + ".aggregatePubkey"));
    }

    /**
     * Finalized execution schema {@code [finalizedHeader, executionBranch, blockHeaderRlp]}.
     */
    @NonNull
    private static FinalizedExecution decodeFinalizedExecution(
            @NonNull final Rlp.Item item, @NonNull final String name) {
        final List<Rlp.Item> fields =
                list(item, FINALIZED_EXECUTION_FIELDS, name + " [finalizedHeader, executionBranch, blockHeaderRlp]");
        return new FinalizedExecution(
                decodeBeaconHeader(fields.get(FINALIZED_EXECUTION_INDEX_HEADER), name + ".finalizedHeader"),
                decodeBranch(
                        fields.get(FINALIZED_EXECUTION_INDEX_BRANCH),
                        Gloas.EXECUTION_BLOCK_HASH.depth(),
                        name + ".executionBranch"),
                bytes(fields.get(FINALIZED_EXECUTION_INDEX_BLOCK_HEADER), name + ".blockHeaderRlp"));
    }

    /**
     * Decodes an SSZ Merkle branch: an RLP list of exactly {@code expectedDepth} sibling hashes of
     * 32 bytes each, ordered leaf-to-root.
     */
    @NonNull
    private static byte[][] decodeBranch(
            @NonNull final Rlp.Item item, final int expectedDepth, @NonNull final String name) {
        if (!item.isList()) {
            throw EthProofs.fail(name + NOT_AN_RLP_LIST);
        }
        final List<Rlp.Item> children = item.children();
        if (children.size() != expectedDepth) {
            throw EthProofs.fail(name + " must have " + expectedDepth + " nodes, got " + children.size());
        }
        final byte[][] out = new byte[expectedDepth][];
        for (int i = 0; i < expectedDepth; i++) {
            out[i] = fixedBytes(children.get(i), HASH_LENGTH, name + "[" + i + "]");
        }
        return out;
    }

    @NonNull
    private static List<StorageProofEntry> decodeStorageProofList(
            @NonNull final Rlp.Item item, @NonNull final String name) {
        if (!item.isList()) {
            throw EthProofs.fail(name + NOT_AN_RLP_LIST);
        }
        final List<Rlp.Item> entries = item.children();
        final List<StorageProofEntry> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            out.add(decodeStorageProofEntry(entries.get(i), name + "[" + i + "]"));
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

    /**
     * Decodes an RLP list of opaque byte strings into a {@code byte[][]} for MPT consumption.
     */
    @NonNull
    private static byte[][] decodeNodeList(@NonNull final Rlp.Item item, @NonNull final String name) {
        if (!item.isList()) {
            throw EthProofs.fail(name + NOT_AN_RLP_LIST);
        }
        final List<Rlp.Item> children = item.children();
        final byte[][] out = new byte[children.size()][];
        for (int i = 0; i < children.size(); i++) {
            out[i] = bytes(children.get(i), name + "[" + i + "]");
        }
        return out;
    }

    // -----------------------------------------------------------------------------------
    // Config items
    // -----------------------------------------------------------------------------------

    /** Requires a message field of the config payload to be set. */
    private static void requirePresent(final boolean present, @NonNull final String name) {
        if (!present) {
            throw EthProofs.fail("configPayload has no " + name);
        }
    }

    /**
     * A committee with a 32-byte keys root and a 48-byte aggregate key.
     */
    @NonNull
    private static SyncCommittee toSyncCommittee(
            @NonNull final EthereumPosCommitteeAnchor committee, @NonNull final String name) {
        return new SyncCommittee(
                fixedBytes(committee.pubkeysRoot(), HASH_LENGTH, name + ".pubkeys_root"),
                fixedBytes(committee.aggregatePubkey(), Ssz.BLS_PUBKEY_LENGTH, name + ".aggregate_pubkey"));
    }

    /** A beacon header with 32-byte roots. */
    @NonNull
    private static BeaconHeader toBeaconHeader(
            @NonNull final EthereumPosBeaconBlockHeader header, @NonNull final String name) {
        return new BeaconHeader(
                header.slot(),
                header.proposerIndex(),
                fixedBytes(header.parentRoot(), HASH_LENGTH, name + ".parent_root"),
                fixedBytes(header.stateRoot(), HASH_LENGTH, name + ".state_root"),
                fixedBytes(header.bodyRoot(), HASH_LENGTH, name + ".body_root"));
    }

    /** An SSZ branch of exactly {@code expectedDepth} 32-byte siblings, bottom-up. */
    @NonNull
    private static byte[][] toBranch(
            @NonNull final List<Bytes> siblings, final int expectedDepth, @NonNull final String name) {
        if (siblings.size() != expectedDepth) {
            throw EthProofs.fail(name + " must have " + expectedDepth + " nodes, got " + siblings.size());
        }
        final byte[][] out = new byte[expectedDepth][];
        for (int i = 0; i < expectedDepth; i++) {
            out[i] = fixedBytes(siblings.get(i), HASH_LENGTH, name + "[" + i + "]");
        }
        return out;
    }

    /** A sync aggregate with {@code preset}'s bits length and a 256-byte signature. */
    @NonNull
    private static SyncAggregate toSyncAggregate(
            @NonNull final EthereumPosSyncAggregate aggregate,
            @NonNull final SyncCommitteePreset preset,
            @NonNull final String name) {
        return new SyncAggregate(
                fixedBytes(aggregate.bits(), preset.participationBytes(), name + ".bits"),
                fixedBytes(aggregate.signature(), SyncAggregate.SIGNATURE_LENGTH, name + ".signature"));
    }

    /**
     * Non-signer proofs with 128-byte uncompressed keys and branches of {@code preset}'s keys-tree depth.
     */
    @NonNull
    private static List<NonSignerProof> toNonSignerProofs(
            @NonNull final List<EthereumPosNonSignerProof> proofs, @NonNull final SyncCommitteePreset preset) {
        final List<NonSignerProof> out = new ArrayList<>(proofs.size());
        for (int i = 0; i < proofs.size(); i++) {
            final EthereumPosNonSignerProof proof = proofs.get(i);
            final String proofName = "sync_aggregate_non_signer_proofs[" + i + "]";
            final NonSignerProof nonSignerProof = new NonSignerProof(
                    fixedBytes(proof.pubkey(), G1Compression.UNCOMPRESSED_LENGTH, proofName + ".pubkey"),
                    toBranch(proof.branch(), preset.pubkeysBranchDepth(), proofName + ".branch"));
            out.add(nonSignerProof);
        }
        return List.copyOf(out);
    }

    // -----------------------------------------------------------------------------------
    // Primitives
    // -----------------------------------------------------------------------------------

    /**
     * Requires {@code item} to be an RLP list of exactly {@code expectedSize} items and returns them.
     */
    @NonNull
    private static List<Rlp.Item> list(
            @NonNull final Rlp.Item item, final int expectedSize, @NonNull final String name) {
        if (!item.isList() || item.children().size() != expectedSize) {
            throw EthProofs.fail("expected " + name + " to be an RLP list of " + expectedSize + " items, got "
                    + (item.isList() ? item.children().size() + " items" : "non-list"));
        }
        return item.children();
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
        return fixedBytes(bytes(item, name), length, name);
    }

    /**
     * Requires a protobuf {@code bytes} field to be exactly {@code length} bytes.
     */
    @NonNull
    private static byte[] fixedBytes(@NonNull final Bytes value, final int length, @NonNull final String name) {
        return fixedBytes(value.toByteArray(), length, name);
    }

    @NonNull
    private static byte[] fixedBytes(@NonNull final byte[] value, final int length, @NonNull final String name) {
        if (value.length != length) {
            throw EthProofs.fail(name + " must be " + length + " bytes, got " + value.length);
        }
        return value;
    }
}
