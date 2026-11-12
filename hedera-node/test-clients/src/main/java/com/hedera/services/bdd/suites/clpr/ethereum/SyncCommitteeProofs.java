// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr.ethereum;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hederahashgraph.api.proto.java.ClprBundleContent;
import com.hederahashgraph.api.proto.java.ClprChannelStatus;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprEndpointManifest;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprMessagePayload;
import com.hederahashgraph.api.proto.java.ClprQueueMetadata;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.hederahashgraph.api.proto.java.EthereumPosBeaconBlockHeader;
import com.hederahashgraph.api.proto.java.EthereumPosLedgerConfigurationPayload;
import com.hederahashgraph.api.proto.java.EthereumPosSyncAggregate;
import com.hederahashgraph.api.proto.java.EthereumPosSyncCommittee;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained generator for the Ethereum sync-committee verifier's wire payloads, used by
 * {@code ClprEthSyncCommitteeVerifierSuite}.
 * <p>
 * This class supports the generation of Ethereum configuration and bundle payloads, used by the CLPR HAPI tests
 * involving this ledger.
 *
 * <p><b>BLS.</b> The node verifies the sync-aggregate signatures for real, so each committee holds secret keys and
 * {@link BlsSigner} signs the signing roots computed here. The keys are small deterministic scalars, distinct per
 * committee and member: fine for tests, never for a real validator.
 *
 * <p>The config payload is the protobuf {@code EthereumPosLedgerConfigurationPayload} that the verifier's
 * {@code verifyConfig(bytes,bytes32,bytes)} expects — <em>not</em> the {@code StateProof} envelope
 * {@code ClprTestProofs} builds for the passthrough verifier: a light-client bootstrap of the initial committee, and an
 * update it signs proving the next committee. The channel then trusts the initial committee for the configured period
 * and the next one for the period after. The bundle payload is the RLP {@code [ [rotation...], delivery ]} that
 * {@code verifyBundle(bytes,bytes,bytes)} expects, in the Gloas format.
 */
public final class SyncCommitteeProofs {

    private SyncCommitteeProofs() {}

    // ── Chain constants (a config payload and its bundles must share these) ──
    private static final byte[] GENESIS_VALIDATORS_ROOT = generateBytes(32, 0x44);
    private static final byte[] FORK_VERSION = {0x08, 0x00, 0x00, 0x00};
    /** 20-byte execution-layer CLPR service contract address proven by every bundle. */
    static final byte[] SERVICE_ADDRESS = generateBytes(20, 0x55);

    /** The keccak256 of the CLPR service contract's code: pinned by the config, carried by every account proof. */
    private static final byte[] CODE_HASH = generateBytes(32, 0x77);

    static final String CHAIN_ID = "eip155:1";

    /** The sync-committee period of the initial trust anchor. */
    private static final long CONFIG_PERIOD = 1_135L;

    /** Members that do not sign the bundles, so every bundle exercises the non-signer proofs. */
    private static final int[] NON_SIGNERS = {3, 17};

    /** Every member signs the config payload's update. */
    private static final int[] NO_NON_SIGNERS = {};

    // ── Gloas generalized indices (consensus-specs light-client constants) ──
    private static final long FINALIZED_ROOT_GINDEX = 735L;
    private static final long CURRENT_SYNC_COMMITTEE_GINDEX = 2945L;
    private static final long NEXT_SYNC_COMMITTEE_GINDEX = 2946L;
    private static final long EXECUTION_BLOCK_HASH_GINDEX = 2856L;

    /** ClprService storage layout: the {@code _channels} mapping slot and the proven Channel member offsets. */
    private static final int CHANNELS_BASE_SLOT = 15;

    private static final int[] CHANNEL_SLOT_OFFSETS = {1, 2, 4, 5, 16};

    /** EVM storage slot of the {@code ClprEndpointManifest} commitment on the CLPR service contract (slot 18). */
    private static final int MANIFEST_COMMITMENT_SLOT_INDEX = 0x12;

    private static final byte[] ZERO_32 = new byte[32];

    /** The consensus presets the verifier supports. */
    public enum Preset {
        MAINNET(512, 8192),
        MINIMAL(32, 64);

        final int committeeSize;
        final long slotsPerPeriod;

        Preset(final int committeeSize, final long slotsPerPeriod) {
            this.committeeSize = committeeSize;
            this.slotsPerPeriod = slotsPerPeriod;
        }

        long firstSlotOf(final long period) {
            return period * slotsPerPeriod;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Config payload
    // ─────────────────────────────────────────────────────────────────────────────

    /** A valid mainnet config payload: the initial and next committees and a peer config with throttles. */
    public static byte[] configPayload() {
        return configPayload(Preset.MAINNET);
    }

    /** A valid config payload whose committees have the size of {@code preset}. */
    public static byte[] configPayload(final Preset preset) {
        return configPayload(preset, peerLedgerConfig(SERVICE_ADDRESS));
    }

    /**
     * A config payload whose peer {@code serviceAddress} is not 20 bytes. The Ethereum verifier needs a
     * 20-byte execution-layer address, so {@code verifyConfigPayload} rejects this inside the verifier (surfacing as
     * {@code CLPR_VERIFIER_CONFIG_FAILED}) — unlike endpoint/throttle checks, which the verifier ignores and the
     * completion handler enforces downstream.
     */
    public static byte[] configPayloadBadServiceAddress() {
        return configPayload(Preset.MAINNET, peerLedgerConfig(generateBytes(3, 0x66)));
    }

    /**
     * The config payload: the bootstrap opens the initial committee under the anchor header's state root, and an update
     * header of the same period, signed by the initial committee, opens the next committee under its state root.
     *
     * <p>Every member signs the update.
     */
    private static byte[] configPayload(final Preset preset, final ClprLedgerConfiguration ledgerConfig) {
        final Committee current = Committee.initial(preset);
        final Committee next = Committee.next(preset);
        final long anchorSlot = preset.firstSlotOf(CONFIG_PERIOD) + 7;
        final Map<Long, byte[]> anchorState = Map.of(CURRENT_SYNC_COMMITTEE_GINDEX, current.hashTreeRoot());
        final byte[] anchorParentRoot = generateBytes(32, 0x73);
        final byte[] anchorBodyRoot = generateBytes(32, 0x74);
        final long updateSlot = preset.firstSlotOf(CONFIG_PERIOD) + 20;
        final Map<Long, byte[]> updateState = Map.of(NEXT_SYNC_COMMITTEE_GINDEX, next.hashTreeRoot());
        final byte[] updateParentRoot = generateBytes(32, 0x71);
        final byte[] updateBodyRoot = generateBytes(32, 0x72);
        final byte[] updateRoot =
                beaconHeaderRoot(updateSlot, 9L, updateParentRoot, sparseRoot(updateState), updateBodyRoot);
        final byte[] bits = new byte[preset.committeeSize / 8];
        Arrays.fill(bits, (byte) 0xFF);
        final byte[] signature =
                current.sign(computeSigningRoot(updateRoot, FORK_VERSION, GENESIS_VALIDATORS_ROOT), NO_NON_SIGNERS);
        return EthereumPosLedgerConfigurationPayload.newBuilder()
                .setSyncCommittee(current.toProto())
                .setLedgerConfiguration(ledgerConfig)
                .setGenesisValidatorsRoot(ByteString.copyFrom(GENESIS_VALIDATORS_ROOT))
                .setForkVersion(ByteString.copyFrom(FORK_VERSION))
                .setSlot(anchorSlot)
                .setAnchorHeader(protoHeader(anchorSlot, 8L, anchorParentRoot, sparseRoot(anchorState), anchorBodyRoot))
                .addAllCurrentSyncCommitteeBranch(byteStrings(sparseBranch(anchorState, CURRENT_SYNC_COMMITTEE_GINDEX)))
                .setUpdateHeader(protoHeader(updateSlot, 9L, updateParentRoot, sparseRoot(updateState), updateBodyRoot))
                .setSyncAggregate(EthereumPosSyncAggregate.newBuilder()
                        .setBits(ByteString.copyFrom(bits))
                        .setSignature(ByteString.copyFrom(signature)))
                .setNextSyncCommittee(next.toProto())
                .addAllNextSyncCommitteeBranch(byteStrings(sparseBranch(updateState, NEXT_SYNC_COMMITTEE_GINDEX)))
                .setCodeHash(ByteString.copyFrom(CODE_HASH))
                .build()
                .toByteArray();
    }

    private static EthereumPosBeaconBlockHeader protoHeader(
            final long slot,
            final long proposerIndex,
            final byte[] parentRoot,
            final byte[] stateRoot,
            final byte[] bodyRoot) {
        return EthereumPosBeaconBlockHeader.newBuilder()
                .setSlot(slot)
                .setProposerIndex(proposerIndex)
                .setParentRoot(ByteString.copyFrom(parentRoot))
                .setStateRoot(ByteString.copyFrom(stateRoot))
                .setBodyRoot(ByteString.copyFrom(bodyRoot))
                .build();
    }

    private static List<ByteString> byteStrings(final byte[][] items) {
        final List<ByteString> out = new ArrayList<>();
        for (final byte[] item : items) {
            out.add(ByteString.copyFrom(item));
        }
        return out;
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
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Endpoint manifest (raw ClprEndpointManifest protobuf preimage)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Raw {@code ClprEndpointManifest} protobuf bytes bound to {@link #SERVICE_ADDRESS} — the value carried as the
     * config path's {@code endpoint_manifest_proof_bytes} (arg2) and as the bundle's manifest preimage.
     * {@code endpointCount} manifest endpoints are generated deterministically via
     * {@link #manifestEndpointIp}/{@link #manifestEndpointPort}.
     */
    public static byte[] manifestBytes(final long version, final int endpointCount) {
        return manifest(version, SERVICE_ADDRESS, endpointCount).toByteArray();
    }

    /**
     * A manifest whose {@code service_address} is a valid 20-byte address that nonetheless differs from the config's
     * {@link #SERVICE_ADDRESS}. The verifier enforces spec §4.8 (manifest service_address == config service_address),
     * so completeChannel reverts with {@code CLPR_VERIFIER_CONFIG_FAILED}.
     */
    public static byte[] manifestBytesBadServiceAddress() {
        return manifest(1L, generateBytes(20, 0x66), 1).toByteArray();
    }

    /** Deterministic IP address of manifest endpoint {@code i}, mirrored by the suite's assertions. */
    public static String manifestEndpointIp(final int i) {
        return "10.0.0." + (i + 1);
    }

    /** Deterministic port of manifest endpoint {@code i}, mirrored by the suite's assertions. */
    public static int manifestEndpointPort(final int i) {
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
     */
    public static ClprBundleContent singleMessageBundleContent(final ClprMessagePayload payload) {
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

    /**
     * Builds the {@link ClprBundleContent} for a second bundle that delivers two inbound messages. The peer
     * reports {@code nextMessageId = 3} (messages #1 and #2 sent) and a running hash chained over both
     * payloads.
     */
    public static ClprBundleContent twoMessageBundleContent(
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
     * A valid mainnet bundle with no rotation, signed by the initial committee, whose proven Channel slots of
     * {@code channelId} encode exactly {@code content}'s metadata.
     */
    public static byte[] bundlePayload(final byte[] channelId, final ClprBundleContent content) {
        return bundlePayload(Preset.MAINNET, channelId, content);
    }

    /** A valid bundle with no rotation, under a channel configured with {@code preset}. */
    public static byte[] bundlePayload(final Preset preset, final byte[] channelId, final ClprBundleContent content) {
        final Committee committee = Committee.initial(preset);
        return bundle(List.of(), new Delivery(preset, CONFIG_PERIOD, committee, channelId, content).toRlpItems());
    }

    /**
     * A bundle payload with one byte of the account proof flipped, so the account Merkle-Patricia proof
     * fails to verify against the proven execution state root and the verifier rejects the bundle with
     * {@code CLPR_BUNDLE_VERIFICATION_FAILED}.
     */
    public static byte[] tamperedBundlePayload(final byte[] channelId, final ClprBundleContent content) {
        final Delivery delivery =
                new Delivery(Preset.MAINNET, CONFIG_PERIOD, Committee.initial(Preset.MAINNET), channelId, content);
        delivery.tamperAccountProof = true;
        return bundle(List.of(), delivery.toRlpItems());
    }

    /**
     * A bundle that slides the channel's trust anchor by one period and delivers {@code content} under it: one rotation
     * of the period after the configured one, signed by the next committee and proving the committee after it, then a
     * delivery signed by that committee two periods after the configured one. The verifier returns a successor trust
     * anchor.
     */
    public static byte[] rotationBundlePayload(final byte[] channelId, final ClprBundleContent content) {
        final Committee next = Committee.next(Preset.MAINNET);
        final Committee afterNext = Committee.afterNext(Preset.MAINNET);
        final Object rotation = rotation(Preset.MAINNET, CONFIG_PERIOD + 1, next, afterNext);
        final Object delivery =
                new Delivery(Preset.MAINNET, CONFIG_PERIOD + 2, afterNext, channelId, content).toRlpItems();
        return bundle(List.of(rotation), delivery);
    }

    /**
     * A bundle to be submitted <em>after</em> {@link #rotationBundlePayload} has been accepted: no rotation, and a
     * delivery signed by the committee two periods after the configured one, which only the rotated trust anchor
     * accepts.
     */
    public static byte[] postRotationBundlePayload(final byte[] channelId, final ClprBundleContent content) {
        final Committee afterNext = Committee.afterNext(Preset.MAINNET);
        return bundle(
                List.of(), new Delivery(Preset.MAINNET, CONFIG_PERIOD + 2, afterNext, channelId, content).toRlpItems());
    }

    /**
     * A bundle that also carries an endpoint-manifest update (spec §4.9). The manifest commitment
     * ({@code keccak256(manifestBytes)}) is proven at slot 18 in the SAME storage trie — under the SAME account storage
     * root — as the Channel slots, so the single account proof commits to both.
     */
    public static byte[] bundlePayloadWithManifest(
            final byte[] channelId, final ClprBundleContent content, final byte[] manifestBytes) {
        final Delivery delivery =
                new Delivery(Preset.MAINNET, CONFIG_PERIOD, Committee.initial(Preset.MAINNET), channelId, content);
        delivery.manifestBytes = manifestBytes;
        return bundle(List.of(), delivery.toRlpItems());
    }

    /** The trust anchor id after {@link #rotationBundlePayload}: the period after the configured one, 8 bytes BE. */
    public static byte[] trustAnchorIdAfterRotation() {
        final byte[] id = new byte[Long.BYTES];
        putUint64BigEndian(id, 0, CONFIG_PERIOD + 1);
        return id;
    }

    /** The RLP bundle payload {@code [ [rotation...], delivery ]}. */
    private static byte[] bundle(final List<Object> rotations, final Object delivery) {
        return RLPEncoder.list(List.of(rotations, delivery));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Rotation + delivery
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * The RLP items of a rotation of {@code period}, signed by {@code signer} (the committee of that period): its
     * attested state commits a finalized header in the same period (finalized root, gindex 735) and {@code next} (the
     * committee of the period after, gindex 2946).
     */
    private static Object rotation(
            final Preset preset, final long period, final Committee signer, final Committee next) {
        final long finalizedSlot = preset.firstSlotOf(period) + 8;
        final byte[] finalizedParentRoot = generateBytes(32, 0x63);
        final byte[] finalizedStateRoot = generateBytes(32, 0x64);
        final byte[] finalizedBodyRoot = generateBytes(32, 0x65);
        final byte[] finalizedRoot =
                beaconHeaderRoot(finalizedSlot, 5L, finalizedParentRoot, finalizedStateRoot, finalizedBodyRoot);
        final Map<Long, byte[]> state =
                Map.of(FINALIZED_ROOT_GINDEX, finalizedRoot, NEXT_SYNC_COMMITTEE_GINDEX, next.hashTreeRoot());
        final long attestedSlot = preset.firstSlotOf(period) + 40;
        final byte[] stateRoot = sparseRoot(state);
        final byte[] parentRoot = generateBytes(32, 0x61);
        final byte[] bodyRoot = generateBytes(32, 0x62);
        return List.of(
                header(attestedSlot, 11L, parentRoot, stateRoot, bodyRoot),
                syncAggregate(signer, preset, beaconHeaderRoot(attestedSlot, 11L, parentRoot, stateRoot, bodyRoot)),
                signer.nonSignerEntries(),
                header(finalizedSlot, 5L, finalizedParentRoot, finalizedStateRoot, finalizedBodyRoot),
                byteList(sparseBranch(state, FINALIZED_ROOT_GINDEX)),
                List.of(next.pubkeysRoot, next.uncompressedAggregate),
                byteList(sparseBranch(state, NEXT_SYNC_COMMITTEE_GINDEX)));
    }

    /**
     * A delivery signed by {@code signer} in {@code period}, in the slot after the attested one. Its finalized header's
     * body commits the execution block
     * hash ({@code keccak256(blockHeaderRlp)}, gindex 2856); the block header carries the execution state root that
     * commits the CLPR service account; the account's storage trie holds the Channel slots of {@code channelId}
     * encoding {@code content}'s metadata, and the manifest commitment when one is set.
     */
    private static final class Delivery {
        private final Preset preset;
        private final long period;
        private final Committee signer;
        private final byte[] channelId;
        private final ClprBundleContent content;
        private boolean tamperAccountProof;
        private byte[] manifestBytes;

        private Delivery(
                final Preset preset,
                final long period,
                final Committee signer,
                final byte[] channelId,
                final ClprBundleContent content) {
            this.preset = preset;
            this.period = period;
            this.signer = signer;
            this.channelId = channelId;
            this.content = content;
        }

        /** The RLP items of the delivery. */
        private Object toRlpItems() {
            // 1) Storage trie over the Channel slots (and the manifest commitment), keyed by keccak(slot).
            final byte[][] channelSlots = channelSlots(channelId);
            final List<byte[]> slots = new ArrayList<>(Arrays.asList(channelSlots));
            final List<byte[]> values = new ArrayList<>(Arrays.asList(computeSlotValues(content.getMetadata())));
            if (manifestBytes != null) {
                slots.add(slotKey(MANIFEST_COMMITMENT_SLOT_INDEX));
                values.add(MiscCryptoUtils.keccak256DigestOf(manifestBytes));
            }
            final StorageTrie storageTrie = StorageTrie.of(slots, values);

            // 2) Account proof: single leaf for SERVICE_ADDRESS committing the storage root. When tampering, the
            //    served leaf no longer hashes to the execution state root.
            final byte[] accountLeaf = accountLeaf(SERVICE_ADDRESS, storageTrie.root);
            final byte[] executionStateRoot = MiscCryptoUtils.keccak256DigestOf(accountLeaf);
            final byte[] servedAccountLeaf = accountLeaf.clone();
            if (tamperAccountProof) {
                servedAccountLeaf[servedAccountLeaf.length - 1] ^= 0x01;
            }

            // 3) Execution block header committing the state root; the finalized body commits its hash.
            final byte[] blockHeaderRlp = RLPEncoder.list(List.of(
                    generateBytes(32, 0x31),
                    generateBytes(32, 0x32),
                    generateBytes(20, 0x33),
                    executionStateRoot,
                    generateBytes(32, 0x34),
                    generateBytes(32, 0x35)));
            final Map<Long, byte[]> finalizedBody =
                    Map.of(EXECUTION_BLOCK_HASH_GINDEX, MiscCryptoUtils.keccak256DigestOf(blockHeaderRlp));
            final byte[] finalizedBodyRoot = sparseRoot(finalizedBody);
            final long finalizedSlot = preset.firstSlotOf(period) + 16;
            final byte[] finalizedParentRoot = generateBytes(32, 0x23);
            final byte[] finalizedStateRoot = generateBytes(32, 0x24);
            final byte[] finalizedRoot =
                    beaconHeaderRoot(finalizedSlot, 3L, finalizedParentRoot, finalizedStateRoot, finalizedBodyRoot);

            // 4) Attested header whose state commits the finalized root.
            final Map<Long, byte[]> state = Map.of(FINALIZED_ROOT_GINDEX, finalizedRoot);
            final long attestedSlot = preset.firstSlotOf(period) + 50;
            final byte[] attestedParentRoot = generateBytes(32, 0x21);
            final byte[] attestedStateRoot = sparseRoot(state);
            final byte[] attestedBodyRoot = generateBytes(32, 0x22);

            // 5) Storage proof entries — every entry carries the full node set; only matching keys resolve.
            final List<Object> storageEntries = new ArrayList<>();
            for (final byte[] slot : channelSlots) {
                storageEntries.add(List.of(slot, storageTrie.nodes));
            }
            final Object manifestProof = manifestBytes == null
                    ? List.of()
                    : List.of(List.of(slotKey(MANIFEST_COMMITMENT_SLOT_INDEX), storageTrie.nodes));

            return List.of(
                    header(attestedSlot, 7L, attestedParentRoot, attestedStateRoot, attestedBodyRoot),
                    minimalBE(attestedSlot + 1), // signature slot
                    syncAggregate(
                            signer,
                            preset,
                            beaconHeaderRoot(
                                    attestedSlot, 7L, attestedParentRoot, attestedStateRoot, attestedBodyRoot)),
                    signer.nonSignerEntries(),
                    List.of(
                            header(finalizedSlot, 3L, finalizedParentRoot, finalizedStateRoot, finalizedBodyRoot),
                            byteList(sparseBranch(finalizedBody, EXECUTION_BLOCK_HASH_GINDEX)),
                            blockHeaderRlp),
                    byteList(sparseBranch(state, FINALIZED_ROOT_GINDEX)),
                    List.of((Object) servedAccountLeaf),
                    storageEntries,
                    content.toByteArray(),
                    manifestProof,
                    manifestBytes == null ? new byte[0] : manifestBytes);
        }
    }

    /**
     * {@code signer}'s sync aggregate over an attested header: every member signs except {@link #NON_SIGNERS}.
     */
    private static Object syncAggregate(final Committee signer, final Preset preset, final byte[] attestedRoot) {
        final byte[] bits = new byte[preset.committeeSize / 8];
        Arrays.fill(bits, (byte) 0xFF);
        for (final int index : NON_SIGNERS) {
            bits[index / 8] &= (byte) ~(1 << (index % 8));
        }
        final byte[] signingRoot = computeSigningRoot(attestedRoot, FORK_VERSION, GENESIS_VALIDATORS_ROOT);
        return List.of(bits, signer.sign(signingRoot, NON_SIGNERS));
    }

    // ── Storage-slot layout and packing (mirrors the ClprService Channel struct) ──

    /** The Channel slot keys of {@code channelId}: {@code keccak256(channelId ‖ uint256(15)) + {1, 2, 4, 5, 16}}. */
    private static byte[][] channelSlots(final byte[] channelId) {
        final byte[] mappingKey = new byte[64];
        System.arraycopy(channelId, 0, mappingKey, 0, 32);
        mappingKey[63] = CHANNELS_BASE_SLOT;
        final BigInteger base = new BigInteger(1, MiscCryptoUtils.keccak256DigestOf(mappingKey));
        final byte[][] slots = new byte[CHANNEL_SLOT_OFFSETS.length][];
        for (int i = 0; i < slots.length; i++) {
            final byte[] raw = base.add(BigInteger.valueOf(CHANNEL_SLOT_OFFSETS[i]))
                    .mod(BigInteger.ONE.shiftLeft(256))
                    .toByteArray();
            slots[i] = leftPad32(raw.length > 32 ? Arrays.copyOfRange(raw, 1, raw.length) : raw);
        }
        return slots;
    }

    /** The Channel slot values, in {@link #channelSlots} order, encoding {@code metadata}. */
    private static byte[][] computeSlotValues(final ClprQueueMetadata metadata) {
        final byte[][] slotValues = new byte[CHANNEL_SLOT_OFFSETS.length][];
        slotValues[0] = packedStatusSlot(
                metadata.getNextMessageId(), metadata.getStatus().getNumber());
        slotValues[1] = packedReceivedMessageIdSlot(metadata.getReceivedMessageId());
        slotValues[2] = leftPad32(metadata.getSentRunningHash().toByteArray());
        slotValues[3] = leftPad32(metadata.getReceivedRunningHash().toByteArray());
        slotValues[4] = ZERO_32; // endpointManifestVersion: the peer has not cached our manifest.
        return slotValues;
    }

    /** Slot +1: MSB→LSB = 3B pad | 8B nextMessageId | 1B status | 20B verifier(zero). */
    private static byte[] packedStatusSlot(final long nextMessageId, final int status) {
        final byte[] slot = new byte[32];
        putUint64BigEndian(slot, 3, nextMessageId);
        slot[11] = (byte) status;
        return slot;
    }

    /** Slot +2: MSB→LSB = 8B pad | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId. */
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

    /** A 32-byte EVM storage slot key whose last byte is {@code index}. */
    private static byte[] slotKey(final int index) {
        final byte[] key = new byte[32];
        key[31] = (byte) index;
        return key;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Committees
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A committee of test validators: secret keys {@code seed · 2³² + index + 1}, their public keys in the uncompressed
     * (EIP-2537) and compressed (ZCash) forms, and the aggregate of all of them.
     */
    private static final class Committee {
        /** The BLS12-381 base field modulus. */
        private static final BigInteger FIELD_MODULUS = new BigInteger(
                "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);

        /** {@code (p - 1) / 2}: a {@code y} above it is the larger root, flagged by the sign bit. */
        private static final BigInteger HALF_FIELD_MODULUS = FIELD_MODULUS.shiftRight(1);

        final byte[][] uncompressedKeys;
        final byte[][] compressedKeys;
        final byte[] uncompressedAggregate;
        final byte[] compressedAggregate;
        final byte[] pubkeysRoot;
        private final BigInteger[] secretKeys;
        private final byte[][] leaves;

        private Committee(final int size, final int seed) {
            secretKeys = new BigInteger[size];
            uncompressedKeys = new byte[size][];
            compressedKeys = new byte[size][];
            leaves = new byte[size][];
            for (int i = 0; i < size; i++) {
                secretKeys[i] = BigInteger.valueOf(seed).shiftLeft(Integer.SIZE).add(BigInteger.valueOf(i + 1L));
                uncompressedKeys[i] = BlsSigner.publicKey(secretKeys[i]);
                compressedKeys[i] = compress(uncompressedKeys[i]);
                leaves[i] = pubkeyChunk(compressedKeys[i]);
            }
            // The aggregate of the public keys is the public key of the sum of the secret keys.
            uncompressedAggregate = BlsSigner.publicKey(sumOfSecretKeys(NO_NON_SIGNERS));
            compressedAggregate = compress(uncompressedAggregate);
            pubkeysRoot = merkleize(leaves);
        }

        /** The committee of the configured period. */
        static Committee initial(final Preset preset) {
            return new Committee(preset.committeeSize, 1);
        }

        /** The committee of the period after the configured one. */
        static Committee next(final Preset preset) {
            return new Committee(preset.committeeSize, 3);
        }

        /** The committee two periods after the configured one. */
        static Committee afterNext(final Preset preset) {
            return new Committee(preset.committeeSize, 5);
        }

        /** The committee as the config payload carries it: its compressed keys and aggregate. */
        EthereumPosSyncCommittee toProto() {
            final var committee =
                    EthereumPosSyncCommittee.newBuilder().setAggregatePubkey(ByteString.copyFrom(compressedAggregate));
            for (final byte[] key : compressedKeys) {
                committee.addPubkeys(ByteString.copyFrom(key));
            }
            return committee.build();
        }

        /** {@code hash_tree_root(SyncCommittee)} — mirrors {@code SyncCommittee.hashTreeRoot()}. */
        byte[] hashTreeRoot() {
            return sha256(pubkeysRoot, pubkeyChunk(compressedAggregate));
        }

        /**
         * The aggregate signature over {@code signingRoot32} of every member but {@code nonSigners}: the signature of
         * the sum of their secret keys.
         */
        byte[] sign(final byte[] signingRoot32, final int[] nonSigners) {
            return BlsSigner.sign(sumOfSecretKeys(nonSigners), signingRoot32);
        }

        private BigInteger sumOfSecretKeys(final int[] excluded) {
            BigInteger sum = BigInteger.ZERO;
            for (int i = 0; i < secretKeys.length; i++) {
                final int member = i;
                if (Arrays.stream(excluded).noneMatch(index -> index == member)) {
                    sum = sum.add(secretKeys[i]);
                }
            }
            return sum.mod(BlsSigner.SUBGROUP_ORDER);
        }

        /** One {@code key128 ‖ siblings} entry per {@link #NON_SIGNERS} member, in ascending index order. */
        List<Object> nonSignerEntries() {
            final List<Object> entries = new ArrayList<>();
            for (final int index : NON_SIGNERS) {
                final List<byte[]> parts = new ArrayList<>(List.of(uncompressedKeys[index]));
                byte[][] level = leaves;
                int position = index;
                while (level.length > 1) {
                    parts.add(level[position ^ 1]);
                    final byte[][] next = new byte[level.length / 2][];
                    for (int i = 0; i < next.length; i++) {
                        next[i] = sha256(level[2 * i], level[2 * i + 1]);
                    }
                    level = next;
                    position >>= 1;
                }
                entries.add(concat(parts));
            }
            return entries;
        }

        /** ZCash compression: {@code x} with the compression flag and, when {@code y > (p-1)/2}, the sign flag. */
        private static byte[] compress(final byte[] uncompressed) {
            final byte[] x = Arrays.copyOfRange(uncompressed, 16, 64);
            final BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 80, 128));
            x[0] = (byte) (x[0] | 0x80 | (y.compareTo(HALF_FIELD_MODULUS) > 0 ? 0x20 : 0));
            return x;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Merkle-Patricia trie construction
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A storage trie holding the non-zero slot values. Holds the root hash and every node, ready to be
     * served as each storage entry's proof; absent keys resolve to all-zero against the same root.
     *
     * <p>A general recursive hexary Merkle-Patricia-Trie builder over an arbitrary set of 64-nibble keys, so a bundle
     * can prove the Channel slots and the endpoint-manifest commitment slot together under one root.
     */
    private static final class StorageTrie {
        final byte[] root;
        final List<Object> nodes;

        private StorageTrie(final byte[] root, final List<Object> nodes) {
            this.root = root;
            this.nodes = nodes;
        }

        /** Trie over the non-zero {@code values}, each keyed by {@code keccak256} of the slot at the same position. */
        static StorageTrie of(final List<byte[]> slots, final List<byte[]> values) {
            final List<int[]> keyHashes = new ArrayList<>();
            final List<byte[]> nonZeroValues = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                if (!isZero(values.get(i))) {
                    keyHashes.add(nibbles(MiscCryptoUtils.keccak256DigestOf(slots.get(i))));
                    nonZeroValues.add(values.get(i));
                }
            }
            return build(keyHashes, nonZeroValues);
        }

        private static StorageTrie build(final List<int[]> keyHashes, final List<byte[]> values) {
            if (keyHashes.isEmpty()) {
                // No non-zero slots: an empty trie. Empty proof nodes + empty-trie root resolve all to zero.
                return new StorageTrie(MiscCryptoUtils.keccak256DigestOf(RLPEncoder.sequence(new byte[0])), List.of());
            }
            final List<Object> nodes = new ArrayList<>();
            final List<Integer> all = new ArrayList<>(keyHashes.size());
            for (int i = 0; i < keyHashes.size(); i++) {
                all.add(i);
            }
            final byte[] rootNode = buildNode(keyHashes, values, all, 0, nodes);
            return new StorageTrie(MiscCryptoUtils.keccak256DigestOf(rootNode), nodes);
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

    /** A single-leaf account proof committing {@code storageRoot} and {@link #CODE_HASH}; returns the leaf node RLP. */
    private static byte[] accountLeaf(final byte[] address20, final byte[] storageRoot32) {
        final int[] path = nibbles(MiscCryptoUtils.keccak256DigestOf(address20));
        // account = [nonce=0, balance=0, storageRoot, codeHash]
        final byte[] account = RLPEncoder.list(List.of(new byte[0], new byte[0], storageRoot32, CODE_HASH));
        return RLPEncoder.list(List.of(hexPrefix(path, 0, 64, true), account));
    }

    /** A storage/leaf node {@code [hexPrefix(path[from..64], leaf), rlp(value32)]}. */
    private static byte[] leafNode(final int[] path, final int from, final byte[] value32) {
        return RLPEncoder.list(List.of(hexPrefix(path, from, 64, true), RLPEncoder.sequence(value32)));
    }

    /** A branch/extension child reference: the node inline if &lt;32 bytes, else its keccak hash. */
    private static byte[] childRef(final byte[] node) {
        return node.length < 32 ? node : MiscCryptoUtils.keccak256DigestOf(node);
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

    /** RLP-ready beacon header {@code [slot, proposerIndex, parentRoot, stateRoot, bodyRoot]}. */
    private static Object header(
            final long slot,
            final long proposerIndex,
            final byte[] parentRoot,
            final byte[] stateRoot,
            final byte[] bodyRoot) {
        return List.of(minimalBE(slot), minimalBE(proposerIndex), parentRoot, stateRoot, bodyRoot);
    }

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

    /** SHA-256 of the 48-byte compressed pubkey zero-padded to 64 bytes — mirrors {@code Ssz.pubkeyHash64}. */
    private static byte[] pubkeyChunk(final byte[] pubkey48) {
        final byte[] padded = new byte[64];
        System.arraycopy(pubkey48, 0, padded, 0, 48);
        return sha256(padded);
    }

    /**
     * Root of a sparse SSZ tree holding {@code leavesByGindex} at their generalized indices; every other subtree is a
     * zero chunk.
     */
    private static byte[] sparseRoot(final Map<Long, byte[]> leavesByGindex) {
        return sparseNode(leavesByGindex, 1L);
    }

    /** Sibling path (bottom-up) of {@code gindex} in the sparse tree of {@link #sparseRoot}. */
    private static byte[][] sparseBranch(final Map<Long, byte[]> leavesByGindex, final long gindex) {
        final int depth = 63 - Long.numberOfLeadingZeros(gindex);
        final byte[][] branch = new byte[depth][];
        long node = gindex;
        for (int level = 0; level < depth; level++) {
            branch[level] = sparseNode(leavesByGindex, node ^ 1);
            node >>>= 1;
        }
        return branch;
    }

    private static byte[] sparseNode(final Map<Long, byte[]> leavesByGindex, final long gindex) {
        final byte[] leaf = leavesByGindex.get(gindex);
        if (leaf != null) {
            return leaf;
        }
        final boolean hasLeafBelow = leavesByGindex.keySet().stream().anyMatch(candidate -> {
            final int levelsBelow = Long.numberOfLeadingZeros(gindex) - Long.numberOfLeadingZeros(candidate);
            return levelsBelow > 0 && (candidate >>> levelsBelow) == gindex;
        });
        return hasLeafBelow
                ? sha256(sparseNode(leavesByGindex, 2 * gindex), sparseNode(leavesByGindex, 2 * gindex + 1))
                : ZERO_32;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Primitives
    // ─────────────────────────────────────────────────────────────────────────────

    private static List<Object> byteList(final byte[][] items) {
        return new ArrayList<>(Arrays.asList((Object[]) items));
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

    private static byte[] concat(final List<byte[]> parts) {
        int length = 0;
        for (final byte[] part : parts) {
            length += part.length;
        }
        final byte[] out = new byte[length];
        int offset = 0;
        for (final byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
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
