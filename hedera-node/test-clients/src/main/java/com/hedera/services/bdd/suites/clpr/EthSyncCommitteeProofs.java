// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.ClprBundleContent;
import com.hederahashgraph.api.proto.java.ClprChannelStatus;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprEndpointManifest;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprQueueMetadata;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bouncycastle.jcajce.provider.digest.Keccak;

/**
 * Self-contained generator for the Ethereum sync-committee verifier's wire payloads, used by
 * {@link ClprEthSyncCommitteeVerifierSuite}.
 * <p>
 * This class supports the generation of Ethereum configuration and bundle payloads, used by the CLPR HAPI tests
 * involving this ledger.
 *
 * <p><b>BLS.</b> The node's verifier currently runs a fake BLS check that accepts any signature, so a
 * placeholder 96-byte signature suffices today. The signing root the real verifier would check is
 * computed here and handed to {@link BlsSigner} so that, when a real {@code BlsSignatureVerifier} and
 * a BLS library land, only {@link BlsSigner} needs a real implementation — the rest of the payload is
 * already shaped as if signatures were verified.
 *
 * <p>The config payload is the raw RLP {@code [slot, syncCommittee, genesisValidatorsRoot,
 * forkVersion, ledgerConfigBytes]} that the verifier's {@code verifyConfig(bytes)} expects — <em>not</em>
 * the {@code StateProof} envelope {@link ClprTestProofs} builds for the passthrough verifier. The bundle
 * payload is the raw RLP 9-item list that {@code verifyBundle(bytes,bytes)} expects.
 */
final class EthSyncCommitteeProofs {

    private EthSyncCommitteeProofs() {}

    // ── Committee / chain constants (a config payload and its bundles must share these) ──
    static final int COMMITTEE_SIZE = 512;
    private static final byte[][] PUBKEYS = buildCommitteePubkeys();
    private static final byte[] AGGREGATE_PUBKEY = generateBytes(48, 0x33);
    private static final byte[] GENESIS_VALIDATORS_ROOT = generateBytes(32, 0x44);
    private static final byte[] FORK_VERSION = {0x05, 0x00, 0x00, 0x00};
    /** 20-byte execution-layer CLPR service contract address proven by every bundle. */
    static final byte[] SERVICE_ADDRESS = generateBytes(20, 0x55);

    // ── Next-committee constants (used by rotation-bundle methods) ──
    static final byte[][] NEXT_PUBKEYS = buildNextCommitteePubkeys();
    static final byte[] NEXT_AGGREGATE_PUBKEY = generateBytes(48, 0x77);
    /** SSZ generalized-index constants matching {@code Ssz.NEXT_SYNC_COMMITTEE_*} in the production verifier. */
    private static final int NEXT_SYNC_COMMITTEE_BRANCH_DEPTH = 6;

    private static final int NEXT_SYNC_COMMITTEE_LEAF_INDEX = 23;

    static final String CHAIN_ID = "eip155:1";
    private static final long CONFIG_SLOT = 9_300_000L;
    private static final long BUNDLE_SLOT = 9_300_064L;
    private static final long ROTATION_BUNDLE_SLOT = 9_300_128L;
    private static final long POST_ROTATION_BUNDLE_SLOT = 9_300_192L;
    private static final long MANIFEST_BUNDLE_SLOT = 9_300_256L;

    /**
     * EVM storage-slot index holding the {@code ClprEndpointManifest} commitment on the CLPR service contract —
     * mirrors {@code EthereumSyncCommitteeProofVerifier.MANIFEST_COMMITMENT_SLOT} ({@code leftPad32(0x12)} = slot 18).
     */
    private static final int MANIFEST_COMMITMENT_SLOT_INDEX = 0x12;

    private static final byte[] ZERO_32 = new byte[32];
    /** Depth/leaf-index of {@code execution_payload.state_root} within the beacon body (Electra). */
    private static final int EXECUTION_STATE_ROOT_DEPTH = 9;

    private static final int EXECUTION_STATE_ROOT_LEAF_INDEX = 290;

    // ─────────────────────────────────────────────────────────────────────────────
    //  Config payload
    // ─────────────────────────────────────────────────────────────────────────────

    /** A valid config payload: self-describing committee and a peer config with throttles and one endpoint. */
    static byte[] configPayload() {
        return configPayload(peerLedgerConfig(SERVICE_ADDRESS));
    }

    /**
     * A config payload whose peer {@code serviceAddress} is not 20 bytes. The Ethereum verifier needs a
     * 20-byte execution-layer address to derive the trust anchor, so {@code verifyConfigPayload} rejects
     * this inside the verifier (surfacing as {@code CLPR_VERIFIER_CONFIG_FAILED}) — unlike endpoint/throttle
     * checks, which the verifier ignores and the completion handler enforces downstream.
     */
    static byte[] configPayloadBadServiceAddress() {
        return configPayload(peerLedgerConfig(generateBytes(3, 0x66)));
    }

    private static byte[] configPayload(final ClprLedgerConfiguration ledgerConfig) {
        final Object syncCommittee = List.of(pubkeysList(), AGGREGATE_PUBKEY);
        return RLPEncoder.list(List.of(
                minimalBE(CONFIG_SLOT),
                syncCommittee,
                GENESIS_VALIDATORS_ROOT,
                FORK_VERSION,
                ledgerConfig.toByteArray()));
    }

    private static ClprLedgerConfiguration peerLedgerConfig(final byte[] serviceAddress) {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId(CHAIN_ID)
                .setServiceAddress(ByteString.copyFrom(serviceAddress))
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(100)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(1000)
                        .setMaxSyncBytes(1_048_576L)
                        .build())
                .addEndpoints(ClprEndpoint.newBuilder()
                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                .setIpAddress("127.0.0.1")
                                .setPort(50211)
                                .build())
                        .setTlsCertificate(ByteString.copyFrom(new byte[] {0x01}))
                        .build())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Endpoint manifest (raw ClprEndpointManifest protobuf preimage)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Raw {@code ClprEndpointManifest} protobuf bytes bound to {@link #SERVICE_ADDRESS} — the value carried as the
     * config path's {@code endpoint_manifest_proof_bytes} (arg2) and as bundle item 9. {@code endpointCount} manifest
     * endpoints are generated deterministically via {@link #manifestEndpointIp}/{@link #manifestEndpointPort}.
     */
    static byte[] manifestBytes(final long version, final int endpointCount) {
        return manifest(version, SERVICE_ADDRESS, endpointCount).toByteArray();
    }

    /**
     * A manifest whose {@code service_address} is a valid 20-byte address that nonetheless differs from the config's
     * {@link #SERVICE_ADDRESS}. The verifier enforces spec §4.8 (manifest service_address == config service_address),
     * so completeChannel reverts with {@code CLPR_VERIFIER_CONFIG_FAILED}.
     */
    static byte[] manifestBytesBadServiceAddress() {
        return manifest(1L, generateBytes(20, 0x66), 1).toByteArray();
    }

    /** Deterministic IP address of manifest endpoint {@code i}, mirrored by the suite's assertions. */
    static String manifestEndpointIp(final int i) {
        return "10.0.0." + (i + 1);
    }

    /** Deterministic port of manifest endpoint {@code i}, mirrored by the suite's assertions. */
    static int manifestEndpointPort(final int i) {
        return 50_000 + i;
    }

    private static ClprEndpointManifest manifest(
            final long version, final byte[] serviceAddress, final int endpointCount) {
        final var builder = ClprEndpointManifest.newBuilder()
                .setVersion(version)
                .setServiceAddress(ByteString.copyFrom(serviceAddress));
        for (int i = 0; i < endpointCount; i++) {
            builder.addEndpoints(ClprEndpoint.newBuilder()
                    .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                            .setIpAddress(manifestEndpointIp(i))
                            .setPort(manifestEndpointPort(i))
                            .build())
                    .setTlsCertificate(ByteString.copyFrom(new byte[] {(byte) (0xA0 + i)}))
                    .build());
        }
        return builder.build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Bundle content + payload
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the {@link ClprBundleContent} for the first bundle on a fresh channel delivering exactly
     * one inbound message. The peer reports {@code nextMessageId = 2} (it has sent message #1 and acked
     * none of ours) and {@code sentRunningHash = SHA-256(ZERO || SHA-256(serialized(payload)))}, matching
     * what the submit handler recomputes when it folds the delivered message.
     *
     * <p>{@code receivedMessageId} is intentionally left at 0 so that its storage slot stays zero: the
     * {@link StorageTrie} builder only supports up to 2 non-zero slots, and slots 0 (packed status) and 2
     * (sentRunningHash) are already non-zero here. A non-zero {@code receivedMessageId} would push slot 1
     * non-zero too and exceed that limit.
     */
    static ClprBundleContent singleMessageBundleContent(final ClprMessagePayload payload) {
        final byte[] sentRunningHash = sha256(ZERO_32, sha256(serializePayload(payload)));
        final var metadata = ClprQueueMetadata.newBuilder()
                .setNextMessageId(2L)
                .setReceivedMessageId(0L)
                .setStatus(ClprChannelStatus.ACTIVE)
                .setSentRunningHash(ByteString.copyFrom(sentRunningHash))
                .setReceivedRunningHash(ByteString.copyFrom(ZERO_32))
                .build();
        return ClprBundleContent.newBuilder()
                .setMetadata(metadata)
                .addMessages(payload)
                .build();
    }

    /** A valid bundle payload whose proven storage slots encode exactly {@code content}'s metadata. */
    static byte[] bundlePayload(final ClprBundleContent content) {
        return bundlePayload(content, false);
    }

    /**
     * A bundle payload with one byte of the account proof flipped, so the account Merkle-Patricia proof
     * fails to verify against the proven execution state root and the verifier rejects the bundle with
     * {@code CLPR_BUNDLE_VERIFICATION_FAILED}.
     */
    static byte[] tamperedBundlePayload(final ClprBundleContent content) {
        return bundlePayload(content, true);
    }

    private static byte[] bundlePayload(final ClprBundleContent content, final boolean tamperAccountProof) {
        final var metadata = content.getMetadata();

        // 1) Proven storage-slot values, in QueueMetadata slot order (see QueueMetadata.decode).
        final byte[][] slotValues = computeSlotValues(metadata);

        // 2) Storage trie over the non-zero slots; zero slots resolve "absent" against the same root.
        final StorageTrie storageTrie = StorageTrie.of(slotValues);

        // 3) Account proof: single leaf for SERVICE_ADDRESS committing the storage root. The execution
        //    state root commits the clean leaf; when tampering, the leaf served in the proof is
        //    corrupted so it no longer hashes to that root and the account proof fails.
        final byte[] accountLeaf = accountLeaf(SERVICE_ADDRESS, storageTrie.root);
        final byte[] executionStateRoot = keccak256(accountLeaf);
        final byte[] servedAccountLeaf;
        if (tamperAccountProof) {
            servedAccountLeaf = accountLeaf.clone();
            servedAccountLeaf[servedAccountLeaf.length - 1] ^= 0x01;
        } else {
            servedAccountLeaf = accountLeaf;
        }

        // 4) Beacon header: bodyRoot folds the execution state root up an all-zero branch at index 290.
        final byte[] bodyRoot =
                foldBranch(executionStateRoot, zeroBranch(EXECUTION_STATE_ROOT_DEPTH), EXECUTION_STATE_ROOT_LEAF_INDEX);
        final byte[] parentRoot = generateBytes(32, 0x21);
        final byte[] stateRoot = generateBytes(32, 0x22);
        final Object attestedHeader = List.of(minimalBE(BUNDLE_SLOT), minimalBE(7L), parentRoot, stateRoot, bodyRoot);

        // 5) Sync aggregate: full participation; signature comes from the (placeholder) signer over the
        //    real signing root, so the shape is correct when a real BLS verifier replaces the fake one.
        final byte[] bits = new byte[64];
        java.util.Arrays.fill(bits, (byte) 0xFF);
        final byte[] beaconBlockRoot = beaconHeaderRoot(BUNDLE_SLOT, 7L, parentRoot, stateRoot, bodyRoot);
        final byte[] signingRoot = computeSigningRoot(beaconBlockRoot, FORK_VERSION, GENESIS_VALIDATORS_ROOT);
        final byte[] signature = BlsSigner.PLACEHOLDER.sign(signingRoot, COMMITTEE_SIZE);
        final Object syncAggregate = List.of(bits, signature);

        // 6) Storage proof entries — every entry carries the full node set; only matching keys resolve.
        final List<Object> storageEntries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            storageEntries.add(List.of(slotKey(i), storageTrie.nodes));
        }

        return RLPEncoder.list(List.of(
                attestedHeader,
                syncAggregate,
                executionStateRoot,
                zeroBranchList(EXECUTION_STATE_ROOT_DEPTH),
                new byte[0], // nextCommittee absent (no rotation)
                List.of(), // nextCommitteeBranch absent
                List.of((Object) servedAccountLeaf),
                storageEntries,
                content.toByteArray()));
    }

    /**
     * Builds the {@link ClprBundleContent} for a second bundle that delivers two inbound messages. The peer
     * reports {@code nextMessageId = 3} (messages #1 and #2 sent) and a running hash chained over both
     * payloads. {@code receivedMessageId} is left at 0 so slot 1 stays zero and the {@link StorageTrie}
     * 2-slot limit is not exceeded.
     */
    static ClprBundleContent twoMessageBundleContent(
            final ClprMessagePayload payload1, final ClprMessagePayload payload2) {
        final byte[] hash1 = sha256(ZERO_32, sha256(serializePayload(payload1)));
        final byte[] sentRunningHash = sha256(hash1, sha256(serializePayload(payload2)));
        final var metadata = ClprQueueMetadata.newBuilder()
                .setNextMessageId(3L)
                .setReceivedMessageId(0L)
                .setStatus(ClprChannelStatus.ACTIVE)
                .setSentRunningHash(ByteString.copyFrom(sentRunningHash))
                .setReceivedRunningHash(ByteString.copyFrom(ZERO_32))
                .build();
        return ClprBundleContent.newBuilder()
                .setMetadata(metadata)
                .addMessages(payload1)
                .addMessages(payload2)
                .build();
    }

    /**
     * A bundle payload signed by the <em>current</em> committee that carries a rotation proof: the
     * {@code nextCommittee} ({@link #NEXT_PUBKEYS} + {@link #NEXT_AGGREGATE_PUBKEY}) is proven against an
     * all-zero SSZ branch of depth {@value #NEXT_SYNC_COMMITTEE_BRANCH_DEPTH} at leaf index
     * {@value #NEXT_SYNC_COMMITTEE_LEAF_INDEX}. The verifier will produce a successor trust anchor
     * for {@link #NEXT_PUBKEYS} if the branch verifies.
     */
    static byte[] rotationBundlePayload(final ClprBundleContent content) {
        final StorageTrie storageTrie = StorageTrie.of(computeSlotValues(content.getMetadata()));
        final byte[] accountLeaf = accountLeaf(SERVICE_ADDRESS, storageTrie.root);
        final byte[] executionStateRoot = keccak256(accountLeaf);

        // stateRoot must commit nextCommitteeRoot at leaf index 23 via an all-zero branch.
        final byte[] nextCommitteeRoot = syncCommitteeHashTreeRoot(NEXT_PUBKEYS, NEXT_AGGREGATE_PUBKEY);
        final byte[] stateRoot = foldBranch(
                nextCommitteeRoot, zeroBranch(NEXT_SYNC_COMMITTEE_BRANCH_DEPTH), NEXT_SYNC_COMMITTEE_LEAF_INDEX);

        final byte[] bodyRoot =
                foldBranch(executionStateRoot, zeroBranch(EXECUTION_STATE_ROOT_DEPTH), EXECUTION_STATE_ROOT_LEAF_INDEX);
        final byte[] parentRoot = generateBytes(32, 0x23);
        final Object attestedHeader =
                List.of(minimalBE(ROTATION_BUNDLE_SLOT), minimalBE(7L), parentRoot, stateRoot, bodyRoot);

        final byte[] bits = new byte[64];
        java.util.Arrays.fill(bits, (byte) 0xFF);
        final byte[] beaconBlockRoot = beaconHeaderRoot(ROTATION_BUNDLE_SLOT, 7L, parentRoot, stateRoot, bodyRoot);
        final byte[] signingRoot = computeSigningRoot(beaconBlockRoot, FORK_VERSION, GENESIS_VALIDATORS_ROOT);
        final byte[] signature = BlsSigner.PLACEHOLDER.sign(signingRoot, COMMITTEE_SIZE);
        final Object syncAggregate = List.of(bits, signature);

        final List<Object> storageEntries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            storageEntries.add(List.of(slotKey(i), storageTrie.nodes));
        }

        return RLPEncoder.list(List.of(
                attestedHeader,
                syncAggregate,
                executionStateRoot,
                zeroBranchList(EXECUTION_STATE_ROOT_DEPTH),
                List.of(nextPubkeysList(), NEXT_AGGREGATE_PUBKEY), // nextCommittee present
                zeroBranchList(NEXT_SYNC_COMMITTEE_BRANCH_DEPTH), // nextCommitteeBranch
                List.of((Object) accountLeaf),
                storageEntries,
                content.toByteArray()));
    }

    /**
     * A bundle payload to be submitted <em>after</em> a rotation bundle has been accepted. It is
     * structurally identical to a normal bundle (no {@code nextCommittee}) but uses
     * {@link #POST_ROTATION_BUNDLE_SLOT} so the beacon header is distinct. Signed by
     * {@link #NEXT_PUBKEYS} (the new committee) so the shape is correct for when real BLS
     * verification replaces the current placeholder.
     */
    static byte[] postRotationBundlePayload(final ClprBundleContent content) {
        final StorageTrie storageTrie = StorageTrie.of(computeSlotValues(content.getMetadata()));
        final byte[] accountLeaf = accountLeaf(SERVICE_ADDRESS, storageTrie.root);
        final byte[] executionStateRoot = keccak256(accountLeaf);

        final byte[] bodyRoot =
                foldBranch(executionStateRoot, zeroBranch(EXECUTION_STATE_ROOT_DEPTH), EXECUTION_STATE_ROOT_LEAF_INDEX);
        final byte[] parentRoot = generateBytes(32, 0x25);
        final byte[] stateRoot = generateBytes(32, 0x26);
        final Object attestedHeader =
                List.of(minimalBE(POST_ROTATION_BUNDLE_SLOT), minimalBE(7L), parentRoot, stateRoot, bodyRoot);

        final byte[] bits = new byte[64];
        java.util.Arrays.fill(bits, (byte) 0xFF);
        final byte[] beaconBlockRoot = beaconHeaderRoot(POST_ROTATION_BUNDLE_SLOT, 7L, parentRoot, stateRoot, bodyRoot);
        final byte[] signingRoot = computeSigningRoot(beaconBlockRoot, FORK_VERSION, GENESIS_VALIDATORS_ROOT);
        final byte[] signature = BlsSigner.PLACEHOLDER.sign(signingRoot, COMMITTEE_SIZE);
        final Object syncAggregate = List.of(bits, signature);

        final List<Object> storageEntries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            storageEntries.add(List.of(slotKey(i), storageTrie.nodes));
        }

        return RLPEncoder.list(List.of(
                attestedHeader,
                syncAggregate,
                executionStateRoot,
                zeroBranchList(EXECUTION_STATE_ROOT_DEPTH),
                new byte[0], // nextCommittee absent
                List.of(), // nextCommitteeBranch absent
                List.of((Object) accountLeaf),
                storageEntries,
                content.toByteArray()));
    }

    /**
     * An 11-item bundle payload that carries an endpoint-manifest advance (spec §4.9). The manifest commitment
     * ({@code keccak256(manifestBytes)}) is proven at {@link #MANIFEST_COMMITMENT_SLOT_INDEX} (slot 18) in the SAME
     * storage trie — under the SAME account storage root — as the queue-metadata slots, so the single account proof
     * commits to both. Item 9 is the manifest preimage; item 10 is the slot-18 {@code [key, proofNodes[]]} entry.
     * No committee rotation is carried.
     */
    static byte[] bundlePayloadWithManifest(final ClprBundleContent content, final byte[] manifestBytes) {
        final byte[] manifestCommitment = keccak256(manifestBytes);
        final StorageTrie storageTrie =
                StorageTrie.ofWithManifest(computeSlotValues(content.getMetadata()), manifestCommitment);
        final byte[] accountLeaf = accountLeaf(SERVICE_ADDRESS, storageTrie.root);
        final byte[] executionStateRoot = keccak256(accountLeaf);

        final byte[] bodyRoot =
                foldBranch(executionStateRoot, zeroBranch(EXECUTION_STATE_ROOT_DEPTH), EXECUTION_STATE_ROOT_LEAF_INDEX);
        final byte[] parentRoot = generateBytes(32, 0x27);
        final byte[] stateRoot = generateBytes(32, 0x28);
        final Object attestedHeader =
                List.of(minimalBE(MANIFEST_BUNDLE_SLOT), minimalBE(7L), parentRoot, stateRoot, bodyRoot);

        final byte[] bits = new byte[64];
        java.util.Arrays.fill(bits, (byte) 0xFF);
        final byte[] beaconBlockRoot = beaconHeaderRoot(MANIFEST_BUNDLE_SLOT, 7L, parentRoot, stateRoot, bodyRoot);
        final byte[] signingRoot = computeSigningRoot(beaconBlockRoot, FORK_VERSION, GENESIS_VALIDATORS_ROOT);
        final byte[] signature = BlsSigner.PLACEHOLDER.sign(signingRoot, COMMITTEE_SIZE);
        final Object syncAggregate = List.of(bits, signature);

        // Queue-metadata storage entries (item 7): exactly QueueMetadata.EXPECTED_SLOTS (5), each serving the full
        // node set; the extra slot-18 leaf in the trie is simply unused when resolving these keys.
        final List<Object> storageEntries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            storageEntries.add(List.of(slotKey(i), storageTrie.nodes));
        }
        // Manifest commitment-slot storage proof (item 10): a single [key, proofNodes[]] entry for slot 18.
        final Object manifestStorageEntry = List.of(slotKey(MANIFEST_COMMITMENT_SLOT_INDEX), storageTrie.nodes);

        return RLPEncoder.list(List.of(
                attestedHeader,
                syncAggregate,
                executionStateRoot,
                zeroBranchList(EXECUTION_STATE_ROOT_DEPTH),
                new byte[0], // nextCommittee absent (no rotation)
                List.of(), // nextCommitteeBranch absent
                List.of((Object) accountLeaf),
                storageEntries,
                content.toByteArray(),
                manifestBytes, // item 9: manifest protobuf preimage
                manifestStorageEntry)); // item 10: slot-18 commitment storage proof
    }

    // ── Storage-slot packing (mirrors QueueMetadata.decode's byte layout) ──

    private static byte[][] computeSlotValues(final ClprQueueMetadata metadata) {
        final byte[][] slotValues = new byte[5][];
        slotValues[0] = packedStatusSlot(
                metadata.getNextMessageId(), metadata.getStatus().getNumber());
        slotValues[1] = packedReceivedMessageIdSlot(metadata.getReceivedMessageId());
        slotValues[2] = leftPad32(metadata.getSentRunningHash().toByteArray());
        slotValues[3] = leftPad32(metadata.getReceivedRunningHash().toByteArray());
        slotValues[4] = ZERO_32; // lastMessageRunningHash — not reconciled, left zero.
        return slotValues;
    }

    // ── Storage-slot packing (mirrors QueueMetadata.decode's byte layout) ──

    /** Slot 0: MSB→LSB = 3B pad | 8B nextMessageId | 1B status | 20B verifier(zero). */
    private static byte[] packedStatusSlot(final long nextMessageId, final int status) {
        final byte[] slot = new byte[32];
        putUint64BigEndian(slot, 3, nextMessageId);
        slot[11] = (byte) status;
        return slot;
    }

    /** Slot 1: MSB→LSB = 8B pad | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId. */
    private static byte[] packedReceivedMessageIdSlot(final long receivedMessageId) {
        final byte[] slot = new byte[32];
        putUint64BigEndian(slot, 16, receivedMessageId);
        return slot;
    }

    private static void putUint64BigEndian(final byte[] buf, final int offset, final long value) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) (value >>> (8 * (7 - i)));
        }
    }

    /** EVM storage slot key for QueueMetadata position {@code i}; chosen so ascending order = field order. */
    static byte[] slotKey(final int i) {
        final byte[] key = new byte[32];
        key[31] = (byte) i;
        return key;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Merkle-Patricia trie construction
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A storage trie holding the non-zero slot values. Holds the root hash and every node, ready to be
     * served as each storage entry's proof; absent keys resolve to all-zero against the same root.
     *
     * <p>A general recursive hexary Merkle-Patricia-Trie builder over an arbitrary set of 64-nibble keys, so a bundle
     * can prove queue-metadata slots and the endpoint-manifest commitment slot together under one root.
     */
    private static final class StorageTrie {
        final byte[] root;
        final List<Object> nodes;

        private StorageTrie(final byte[] root, final List<Object> nodes) {
            this.root = root;
            this.nodes = nodes;
        }

        /** Trie over the non-zero queue-metadata slots only. */
        static StorageTrie of(final byte[][] slotValues) {
            final List<int[]> keyHashes = new ArrayList<>();
            final List<byte[]> values = new ArrayList<>();
            collectNonZeroSlots(slotValues, keyHashes, values);
            return build(keyHashes, values);
        }

        /**
         * Trie over the non-zero queue-metadata slots PLUS the endpoint-manifest commitment at slot 18
         * ({@link #MANIFEST_COMMITMENT_SLOT_INDEX}). {@code manifestCommitment} is {@code keccak256} of the manifest
         * preimage and is stored under the same root as the queue-metadata slots.
         */
        static StorageTrie ofWithManifest(final byte[][] slotValues, final byte[] manifestCommitment) {
            final List<int[]> keyHashes = new ArrayList<>();
            final List<byte[]> values = new ArrayList<>();
            collectNonZeroSlots(slotValues, keyHashes, values);
            keyHashes.add(nibbles(keccak256(slotKey(MANIFEST_COMMITMENT_SLOT_INDEX))));
            values.add(manifestCommitment);
            return build(keyHashes, values);
        }

        private static void collectNonZeroSlots(
                final byte[][] slotValues, final List<int[]> keyHashes, final List<byte[]> values) {
            for (int i = 0; i < slotValues.length; i++) {
                if (!isZero(slotValues[i])) {
                    keyHashes.add(nibbles(keccak256(slotKey(i))));
                    values.add(slotValues[i]);
                }
            }
        }

        private static StorageTrie build(final List<int[]> keyHashes, final List<byte[]> values) {
            if (keyHashes.isEmpty()) {
                // No non-zero slots: an empty trie. Empty proof nodes + empty-trie root resolve all to zero.
                return new StorageTrie(keccak256(RLPEncoder.sequence(new byte[0])), List.of());
            }
            final List<Object> nodes = new ArrayList<>();
            final List<Integer> all = new ArrayList<>(keyHashes.size());
            for (int i = 0; i < keyHashes.size(); i++) {
                all.add(i);
            }
            final byte[] rootNode = buildNode(keyHashes, values, all, 0, nodes);
            return new StorageTrie(keccak256(rootNode), nodes);
        }

        /**
         * Builds the sub-trie node covering {@code group} (indices into {@code keys}/{@code values}) whose keys share
         * nibbles {@code [0, depth)}. Appends every created node to {@code nodes} and returns this node's RLP bytes.
         */
        private static byte[] buildNode(
                final List<int[]> keys,
                final List<byte[]> values,
                final List<Integer> group,
                final int depth,
                final List<Object> nodes) {
            if (group.size() == 1) {
                final int e = group.get(0);
                final byte[] leaf = leafNode(keys.get(e), depth, values.get(e));
                nodes.add(leaf);
                return leaf;
            }
            // Longest common prefix shared by all keys in the group, starting at depth.
            int cpl = depth;
            while (cpl < 64 && shareNibble(keys, group, cpl)) {
                cpl++;
            }
            if (cpl == 64) {
                throw new IllegalStateException("two storage slots hashed to the same key");
            }
            if (cpl > depth) {
                // Shared prefix [depth, cpl): an extension node sits above the branch built at cpl.
                final byte[] branch = buildBranch(keys, values, group, cpl, nodes);
                final byte[] extension = RLPEncoder.list(
                        List.of(hexPrefix(keys.get(group.get(0)), depth, cpl, false), childRef(branch)));
                nodes.add(extension);
                return extension;
            }
            return buildBranch(keys, values, group, depth, nodes);
        }

        /** Builds a 17-slot branch node at {@code depth}: keys are grouped by their nibble at {@code depth}. */
        private static byte[] buildBranch(
                final List<int[]> keys,
                final List<byte[]> values,
                final List<Integer> group,
                final int depth,
                final List<Object> nodes) {
            final Object[] branch = new Object[17];
            for (int i = 0; i < 17; i++) {
                branch[i] = new byte[0];
            }
            final Map<Integer, List<Integer>> byNibble = new LinkedHashMap<>();
            for (final int e : group) {
                byNibble.computeIfAbsent(keys.get(e)[depth], k -> new ArrayList<>())
                        .add(e);
            }
            for (final var entry : byNibble.entrySet()) {
                final byte[] child = buildNode(keys, values, entry.getValue(), depth + 1, nodes);
                branch[entry.getKey()] = childRef(child);
            }
            final byte[] branchNode = RLPEncoder.list(branch);
            nodes.add(branchNode);
            return branchNode;
        }

        private static boolean shareNibble(final List<int[]> keys, final List<Integer> group, final int pos) {
            final int first = keys.get(group.get(0))[pos];
            for (final int e : group) {
                if (keys.get(e)[pos] != first) {
                    return false;
                }
            }
            return true;
        }
    }

    /** A single-leaf account proof committing {@code storageRoot}; returns the leaf node RLP. */
    private static byte[] accountLeaf(final byte[] address20, final byte[] storageRoot32) {
        final int[] path = nibbles(keccak256(address20));
        // account = [nonce=0, balance=0, storageRoot, codeHash=0]
        final byte[] account = RLPEncoder.list(List.of(new byte[0], new byte[0], storageRoot32, ZERO_32));
        return RLPEncoder.list(List.of(hexPrefix(path, 0, 64, true), account));
    }

    /** A storage/leaf node {@code [hexPrefix(path[from..64], leaf), rlp(value32)]}. */
    private static byte[] leafNode(final int[] path, final int from, final byte[] value32) {
        return RLPEncoder.list(List.of(hexPrefix(path, from, 64, true), RLPEncoder.sequence(value32)));
    }

    /** A branch/extension child reference: the node inline if &lt;32 bytes, else its keccak hash. */
    private static byte[] childRef(final byte[] node) {
        return node.length < 32 ? node : keccak256(node);
    }

    /** Hex-prefix encoding of {@code nibbles[from..to)} per the Ethereum trie spec. */
    private static byte[] hexPrefix(final int[] nibbles, final int from, final int to, final boolean leaf) {
        final int len = to - from;
        final boolean odd = (len & 1) == 1;
        final byte[] out = new byte[1 + len / 2];
        final int flag = (leaf ? 2 : 0);
        int idx = from;
        if (odd) {
            out[0] = (byte) (((flag + 1) << 4) | nibbles[idx++]);
        } else {
            out[0] = (byte) (flag << 4);
        }
        for (int i = 1; idx < to; i++) {
            out[i] = (byte) ((nibbles[idx++] << 4) | nibbles[idx++]);
        }
        return out;
    }

    private static int[] nibbles(final byte[] bytes) {
        final int[] out = new int[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = (bytes[i] >> 4) & 0x0F;
            out[2 * i + 1] = bytes[i] & 0x0F;
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  SSZ (independent of the production Ssz class)
    // ─────────────────────────────────────────────────────────────────────────────

    private static byte[] beaconHeaderRoot(
            final long slot,
            final long proposerIndex,
            final byte[] parentRoot,
            final byte[] stateRoot,
            final byte[] bodyRoot) {
        final byte[][] chunks = new byte[8][];
        chunks[0] = uint64Leaf(slot);
        chunks[1] = uint64Leaf(proposerIndex);
        chunks[2] = parentRoot;
        chunks[3] = stateRoot;
        chunks[4] = bodyRoot;
        chunks[5] = ZERO_32;
        chunks[6] = ZERO_32;
        chunks[7] = ZERO_32;
        return merkleize(chunks);
    }

    private static byte[] computeSigningRoot(
            final byte[] beaconBlockRoot, final byte[] forkVersion4, final byte[] genesisValidatorsRoot32) {
        final byte[] paddedVersion = new byte[32];
        System.arraycopy(forkVersion4, 0, paddedVersion, 0, forkVersion4.length);
        final byte[] forkDataRoot = sha256(paddedVersion, genesisValidatorsRoot32);
        final byte[] domain = new byte[32];
        domain[0] = 0x07; // DOMAIN_SYNC_COMMITTEE
        System.arraycopy(forkDataRoot, 0, domain, 4, 28);
        return sha256(beaconBlockRoot, domain);
    }

    private static byte[] merkleize(final byte[][] chunks) {
        byte[][] level = chunks;
        while (level.length > 1) {
            final byte[][] next = new byte[level.length / 2][];
            for (int i = 0; i < next.length; i++) {
                next[i] = sha256(level[2 * i], level[2 * i + 1]);
            }
            level = next;
        }
        return level[0];
    }

    private static byte[] uint64Leaf(final long value) {
        final byte[] leaf = new byte[32];
        for (int i = 0; i < 8; i++) {
            leaf[i] = (byte) (value >>> (8 * i));
        }
        return leaf;
    }

    private static byte[] foldBranch(final byte[] leaf, final byte[][] branch, final int index) {
        byte[] node = leaf;
        for (int i = 0; i < branch.length; i++) {
            node = ((index >>> i) & 1) == 1 ? sha256(branch[i], node) : sha256(node, branch[i]);
        }
        return node;
    }

    private static byte[][] zeroBranch(final int depth) {
        final byte[][] branch = new byte[depth][];
        for (int i = 0; i < depth; i++) {
            branch[i] = new byte[32];
        }
        return branch;
    }

    private static List<Object> zeroBranchList(final int depth) {
        final List<Object> nodes = new ArrayList<>(depth);
        for (int i = 0; i < depth; i++) {
            nodes.add(new byte[32]);
        }
        return nodes;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  BLS signing seam
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Produces the sync-aggregate signature over the committee signing root. The node currently runs a
     * fake BLS verifier that accepts any signature, so {@link #PLACEHOLDER} returns a structurally-valid
     * 96-byte zero signature. When a real {@code BlsSignatureVerifier} and a BLS12-381 library land, swap
     * in a real signer here (signing over G2 with the {@code BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_POP_}
     * ciphersuite) and expose its public keys via {@code PUBKEYS}; nothing else in this generator changes.
     */
    interface BlsSigner {
        byte[] sign(byte[] signingRoot32, int numParticipants);

        BlsSigner PLACEHOLDER = (signingRoot32, numParticipants) -> new byte[96];
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Primitives
    // ─────────────────────────────────────────────────────────────────────────────

    private static List<Object> pubkeysList() {
        final List<Object> keys = new ArrayList<>(COMMITTEE_SIZE);
        for (final byte[] key : PUBKEYS) {
            keys.add(key);
        }
        return keys;
    }

    /** Computes the SSZ {@code hash_tree_root} of a sync committee — mirrors {@code SyncCommittee.hashTreeRoot()}. */
    private static byte[] syncCommitteeHashTreeRoot(final byte[][] pubkeys, final byte[] aggregatePubkey48) {
        final byte[][] chunks = new byte[COMMITTEE_SIZE][];
        for (int i = 0; i < COMMITTEE_SIZE; i++) {
            chunks[i] = pubkeyChunk(pubkeys[i]);
        }
        return sha256(merkleize(chunks), pubkeyChunk(aggregatePubkey48));
    }

    /** SHA-256 of the 48-byte pubkey zero-padded to 64 bytes — mirrors {@code Ssz.pubkeyHash64}. */
    private static byte[] pubkeyChunk(final byte[] pubkey48) {
        final byte[] padded = new byte[64];
        System.arraycopy(pubkey48, 0, padded, 0, 48);
        return sha256(padded);
    }

    private static List<Object> nextPubkeysList() {
        final List<Object> keys = new ArrayList<>(COMMITTEE_SIZE);
        for (final byte[] key : NEXT_PUBKEYS) {
            keys.add(key);
        }
        return keys;
    }

    /**
     * Generate fake public keys for the sync committee members.
     * The values used are deterministic and based on the member and bytes indexes.
     */
    private static byte[][] buildCommitteePubkeys() {
        final byte[][] keys = new byte[COMMITTEE_SIZE][];
        for (int i = 0; i < COMMITTEE_SIZE; i++) {
            keys[i] = new byte[48];
            for (int j = 0; j < 48; j++) {
                keys[i][j] = (byte) (i * 7 + j);
            }
        }
        return keys;
    }

    private static byte[][] buildNextCommitteePubkeys() {
        final byte[][] keys = new byte[COMMITTEE_SIZE][];
        for (int i = 0; i < COMMITTEE_SIZE; i++) {
            keys[i] = new byte[48];
            for (int j = 0; j < 48; j++) {
                keys[i][j] = (byte) (i * 11 + j + 3); // distinct pattern from buildCommitteePubkeys
            }
        }
        return keys;
    }

    private static byte[] serializePayload(final ClprMessagePayload payload) {
        final var pbj = protoToPbj(payload, com.hedera.hapi.node.state.clpr.ClprMessagePayload.class);
        return com.hedera.hapi.node.state.clpr.ClprMessagePayload.PROTOBUF
                .toBytes(pbj)
                .toByteArray();
    }

    /** Minimal big-endian RLP integer encoding: leading zero bytes stripped, zero is the empty string. */
    private static byte[] minimalBE(final long value) {
        if (value == 0) {
            return new byte[0];
        }
        int len = 8;
        while (len > 1 && (value >>> (8 * (len - 1))) == 0) {
            len--;
        }
        final byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[len - 1 - i] = (byte) (value >>> (8 * i));
        }
        return out;
    }

    private static byte[] leftPad32(final byte[] value) {
        if (value.length > 32) {
            throw new IllegalArgumentException("cannot left-pad a value longer than 32 bytes: " + value.length);
        }
        if (value.length == 32) {
            return value;
        }
        final byte[] out = new byte[32];
        System.arraycopy(value, 0, out, 32 - value.length, value.length);
        return out;
    }

    private static boolean isZero(final byte[] bytes) {
        for (final byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Generates a deterministic byte array of specified length based on a seed value.
     * Each byte is calculated as {@code (byte) (seed + i * 31)}, where {@code i} is the byte index.
     */
    private static byte[] generateBytes(final int length, final int seed) {
        final byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }

    static byte[] keccak256(final byte[] input) {
        return new Keccak.Digest256().digest(input);
    }

    private static byte[] sha256(final byte[]... inputs) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final byte[] input : inputs) {
                digest.update(input);
            }
            return digest.digest();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
