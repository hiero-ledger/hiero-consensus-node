// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.hapi.utils.blocks.HashUtils;
import com.hedera.node.app.hapi.utils.blocks.MerklePathBuilder;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.BinaryState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Builds real {@code StateProof} payloads from the VirtualMap Merkle tree for CLPR bundle sync.
 *
 * <p>Proofs include the channel leaf and each queued message leaf, extended from the state
 * root to the block root using the sibling data from the latest {@link BlockProvenSnapshot}.
 * In bring-up mode the block root hash is stored in the TSS signature slot; real TSS is Phase 2.</p>
 */
@Singleton
public class ClprStateProofManager {
    private static final Logger log = LogManager.getLogger(ClprStateProofManager.class);

    private static final int MAX_DER_ECDSA_SIGNATURE_LENGTH_BYTES = 72;

    private static final int CHANNEL_ID_LENGTH_BYTES = 32;

    /**
     * Conservative size of the {@code ClprSyncPayload} fields wrapping the bundle payload:
     * {@code channel_id} (fixed 32 bytes per spec §6.2), {@code endpoint_signature} (ECDSA
     * secp256k1, max 72-byte DER), {@code bundle_payload} (tag + 5-byte varint length prefix —
     * no value bytes since that's the {@code StateProof} content we size against). Subtracted
     * from {@code peerThrottles.maxSyncBytes()} to get the byte budget available to the
     * {@code StateProof} itself, so the wire-level envelope stays under the cap.
     *
     * <p>Each {@code bytes} field on the wire is encoded as {@code [tag][length][raw bytes]}:
     * <ul>
     *   <li><b>tag</b> — a varint packing the field number ({@code .proto} ID) with the wire
     *       type. All {@code ClprSyncPayload} fields are numbered 1–3, so each tag fits in 1
     *       byte. (Field numbers ≥ 16 would need 2 bytes.)</li>
     *   <li><b>varint(len)</b> — the data length, varint-encoded: 1 byte for values ≤ 127,
     *       2 bytes for ≤ 16,383, up to 5 bytes for any uint32. {@code channel_id} (32 B) and
     *       {@code endpoint_signature} (≤ 72 B) both fit in 1; {@code bundle_payload} can be
     *       megabytes, so we budget the full 5.</li>
     * </ul>
     */
    private static final long CLPR_SYNC_ENVELOPE_OVERHEAD_BYTES =
            (1 + 1 + CHANNEL_ID_LENGTH_BYTES) + (1 + 1 + MAX_DER_ECDSA_SIGNATURE_LENGTH_BYTES) + (1 + 5);

    private final BlockProvenSnapshotProvider snapshotProvider;
    private final TssVerifier tssVerifier;
    private final ConfigProvider configProvider;

    @Inject
    public ClprStateProofManager(
            @NonNull final BlockProvenSnapshotProvider snapshotProvider,
            @NonNull final TssVerifier tssVerifier,
            @NonNull final ConfigProvider configProvider) {
        this.snapshotProvider = requireNonNull(snapshotProvider);
        this.tssVerifier = requireNonNull(tssVerifier);
        this.configProvider = requireNonNull(configProvider);
    }

    /**
     * A built bundle together with the outbound message range it actually covers.
     *
     * <p>{@code messageCount} is what the builder managed to pack, which is not simply
     * {@code max_messages_per_bundle}: the range stops early at the end of the queue and is trimmed further to stay
     * inside {@code max_sync_bytes}. A caller that wants to build a <em>second</em> bundle continuing where this one
     * stopped therefore cannot compute the next range start on its own — it needs {@link #lastMessageId} from here.
     *
     * @param payload       the serialised {@code StateProof} bytes
     * @param messageCount  how many message leaves the bundle carries; zero for a pure-ACK bundle
     * @param lastMessageId the highest message ID covered, or {@code firstMessageId - 1} when the bundle carries no
     *                      messages and so consumes nothing from the queue
     */
    public record BundleProof(@NonNull Bytes payload, int messageCount, long lastMessageId) {}

    /**
     * Returns this ledger's id as carried on the latest signed snapshot. Empty bytes when no
     * snapshot is available yet. The value comes from
     * {@code ReadableHistoryStore.getLedgerId()} captured at snapshot construction time —
     * callers should use this rather than reading {@code LedgerConfig.id()} so the verifier
     * always sees the genesis-rooted ledger id.
     */
    @NonNull
    public Bytes latestLedgerId() {
        // try-with-resources releases the snapshot's state reservation (a null resource is skipped)
        try (final var snapshot = snapshotProvider.latestSnapshot().orElse(null)) {
            return snapshot == null ? Bytes.EMPTY : snapshot.ledgerId();
        }
    }

    /**
     * Builds a serialized {@link com.hedera.hapi.block.stream.StateProof} proving the current
     * {@code ClprLedgerConfiguration} singleton in the latest sealed state.
     *
     * <p>Returned bytes are suitable as input to the peer's
     * {@code ClprCompleteChannel} {@code config_proof_bytes} whenever the verifier contract
     * on that channel knows how to parse a {@code StateProof} (e.g. one that delegates to
     * the CLPR system contract precompile's {@code verifyConfig(bytes)} operation).
     *
     * @return serialised {@code StateProof} bytes, or {@code null} when no signed block
     *         snapshot is available yet (e.g. during node bring-up)
     */
    @Nullable
    public Bytes buildConfigStateProof() {
        return buildSingletonStateProof(LEDGER_CONFIGURATION_STATE_ID, "config");
    }

    /**
     * Builds a serialized {@link com.hedera.hapi.block.stream.StateProof} proving the current
     * {@code ClprEndpointManifest} singleton in the latest sealed state.
     *
     * <p>Returned bytes are suitable as input to a peer's {@code ClprCompleteChannel}
     * {@code endpoint_manifest_proof_bytes} (spec PR #332) and to
     * {@code ClprSubmitBundle} manifest-recovery flows (spec PR #336) whenever the verifier
     * contract on that channel knows how to parse a {@code StateProof}.
     *
     * @return serialised {@code StateProof} bytes, or {@code null} when no signed block
     *         snapshot is available yet (e.g. during node bring-up)
     */
    @Nullable
    public Bytes buildManifestStateProof() {
        return buildSingletonStateProof(ENDPOINT_MANIFEST_STATE_ID, "manifest");
    }

    /**
     * Shared helper for {@link #buildConfigStateProof()} and {@link #buildManifestStateProof()}.
     * Extracts the latest signed snapshot, verifies its state is {@link BinaryState}, and
     * delegates to {@link #buildSingletonProof(BinaryState, BlockProvenSnapshot, int, String)}
     * to build a proof for the CLPR singleton at {@code stateId}.
     *
     * @param stateId the CLPR service state ID whose singleton leaf should be proved
     * @param label short human-readable tag used in log lines (e.g. "config", "manifest")
     */
    @Nullable
    private Bytes buildSingletonStateProof(final int stateId, @NonNull final String label) {
        try (final var snapshot = snapshotProvider.latestSnapshot().orElse(null)) {
            if (snapshot == null) {
                log.info("No BlockProvenSnapshot available yet; skipping {} state proof", label);
                return null;
            }
            if (!(snapshot.state() instanceof BinaryState binaryState)) {
                log.warn(
                        "CLPR {} state proof requires BinaryState; got {}",
                        label,
                        snapshot.state().getClass().getSimpleName());
                return null;
            }
            try {
                return buildSingletonProof(binaryState, snapshot, stateId, label);
            } catch (final Exception e) {
                log.warn("Failed to build CLPR {} state proof: {}", label, e.getMessage(), e);
                return null;
            }
        }
    }

    private Bytes buildSingletonProof(
            @NonNull final BinaryState binaryState,
            @NonNull final BlockProvenSnapshot snapshot,
            final int stateId,
            @NonNull final String label) {

        // Precompute the block-root extension siblings appended to every leaf path
        // (same shape used by buildBundleStateProof: state root → block subtree root → hashed timestamp).
        final var tsBytes = Timestamp.PROTOBUF.toBytes(snapshot.blockTimestamp());
        final var hashedTs = Bytes.wrap(HashUtils.computeRawLeafHash(sha384DigestOrThrow(), tsBytes));
        final var baseSibs = snapshot.path().siblings();
        final var extendedSibs = new ArrayList<SiblingNode>(baseSibs.size() + 1);
        extendedSibs.addAll(baseSibs);
        extendedSibs.add(SiblingNode.newBuilder().hash(hashedTs).isLeft(true).build());

        // Resolve the singleton leaf path for the target CLPR singleton.
        final long singletonPath = binaryState.getSingletonPath(stateId);
        if (singletonPath < 0) {
            log.warn("CLPR {} singleton not found in state; cannot build {} proof", label, label);
            return null;
        }
        final var singletonMerkleProof = binaryState.getMerkleProof(singletonPath);
        if (singletonMerkleProof == null) {
            log.warn(
                    "[CLPR-{}-PROOF] merkle proof missing singletonPath={} snapshotTimestamp={}",
                    label.toUpperCase(Locale.ROOT),
                    singletonPath,
                    snapshot.blockTimestamp());
            return null;
        }

        final var allPaths = new ArrayList<MerklePath>();
        allPaths.add(MerklePathBuilder.fromStateApi(singletonMerkleProof)
                .appendSiblingNodes(extendedSibs)
                .build());

        final var tssProof = TssSignedBlockProof.newBuilder()
                .blockSignature(snapshot.tssSignature())
                .build();
        final var proof = StateProof.newBuilder()
                .paths(allPaths)
                .signedBlockProof(tssProof)
                .build();
        return StateProof.PROTOBUF.toBytes(proof);
    }

    /**
     * Builds a serialized {@link com.hedera.hapi.block.stream.StateProof} proving the given
     * channel and up to {@code peerThrottles.maxMessagesPerBundle()} queued messages starting
     * from {@code firstMessageId} exist in the latest sealed state.
     *
     * <p>Trims the message tail so the resulting {@code ClprSyncPayload} envelope never exceeds
     * {@code peerThrottles.maxSyncBytes()} (spec §1.1 sender-side cap). Messages that don't fit
     * stay in the outbound queue and are picked up by the next sync tick — no bookkeeping needed
     * since the queue is keyed by {@code messageId}.
     *
     * <p>When the local outbound queue has no message at {@code firstMessageId} the behaviour
     * depends on {@code allowPureAck}: responder paths pass {@code true} so the channel-only
     * proof is still emitted (a pure-ACK bundle carrying just the channel leaf — the peer
     * learns the current {@code receivedMessageId} / {@code receivedRunningHash} ack frontier).
     * Initiator paths pass {@code false} so they skip the sync entirely when they have nothing
     * meaningful to push.
     *
     * @param channelId   raw bytes of the channel identifier
     * @param firstMessageId first outbound message ID to include (= ackedMessageId + 1)
     * @param peerThrottles  peer-published throttles governing this bundle (max messages, max bytes)
     * @param allowPureAck   when true, emit a channel-only (pure-ACK) bundle if no message
     *                       leaves are available; when false, return null in that case
     * @param includeEndpointManifest  when true, embed a Merkle path for this ledger's
     *                       {@code ClprEndpointManifest} singleton so the peer's verifyBundle can
     *                       return {@code new_endpoint_manifest} and Step 1b applies the update
     *                       (spec §4.5, ADR "Propagating updates"). Callers pass {@code true} only
     *                       when the peer's cached {@code endpoint_manifest_version} is stale
     *                       (see #335). Additionally gated by {@code clpr.endpointManifestEnabled}
     *                       inside — flag OFF suppresses the manifest leaf regardless.
     * @return serialised {@code StateProof} bytes, or {@code null} when no snapshot is available
     *         or when there are no message leaves and {@code allowPureAck} is false
     */
    @Nullable
    public Bytes buildSerializedBundleProof(
            @NonNull final Bytes channelId,
            final long firstMessageId,
            @NonNull final ClprThrottles peerThrottles,
            final boolean allowPureAck,
            final boolean includeEndpointManifest) {
        final var proof =
                buildBundleProof(channelId, firstMessageId, peerThrottles, allowPureAck, includeEndpointManifest);
        return proof == null ? null : proof.payload();
    }

    /**
     * Builds a bundle proof, containing the range of messages contained in it.
     *
     * @return the bundle and its range, or {@code null} under exactly the conditions
     *         {@link #buildSerializedBundleProof} returns {@code null}
     */
    @Nullable
    public BundleProof buildBundleProof(
            @NonNull final Bytes channelId,
            final long firstMessageId,
            @NonNull final ClprThrottles peerThrottles,
            final boolean allowPureAck,
            final boolean includeEndpointManifest) {
        requireNonNull(channelId);
        requireNonNull(peerThrottles);

        try (final var snapshot = snapshotProvider.latestSnapshot().orElse(null)) {
            if (snapshot == null) {
                log.debug(
                        "[CLPR-BUNDLE-BUILD] no snapshot conn={} firstMessageId={} allowPureAck={}",
                        channelId,
                        firstMessageId,
                        allowPureAck);
                return null;
            }
            if (!(snapshot.state() instanceof BinaryState binaryState)) {
                log.warn(
                        "CLPR state proof requires BinaryState; got {}",
                        snapshot.state().getClass().getSimpleName());
                return null;
            }
            try {
                final var built = buildProof(
                        binaryState,
                        snapshot,
                        channelId,
                        firstMessageId,
                        peerThrottles,
                        allowPureAck,
                        includeEndpointManifest);
                if (built == null) {
                    log.warn(
                            "[CLPR-BUNDLE-BUILD] buildProof returned null conn={} firstMessageId={} allowPureAck={} "
                                    + "maxMessages={} maxSyncBytes={}",
                            channelId,
                            firstMessageId,
                            allowPureAck,
                            peerThrottles.maxMessagesPerBundle(),
                            peerThrottles.maxSyncBytes());
                    return null;
                }
                final var validated = assertValidBundleOrNull(built.payload(), snapshot.ledgerId(), channelId);
                return validated == null
                        ? null
                        : new BundleProof(validated, built.messageCount(), built.lastMessageId());
            } catch (final Exception e) {
                log.warn("Failed to build CLPR state proof for channel {}: {}", channelId, e.getMessage(), e);
                return null;
            }
        }
    }

    /**
     * TEMP (bring-up sanity check): re-parses the freshly built bundle state proof,
     * computes the block root hash from the first state-item-leaf path (each bundle path
     * is an independent leaf-to-block-root path, so any single valid path yields the block
     * root the peer signed), and verifies the TSS signature against that root hash under
     * this ledger's id. If anything fails — parse, root computation, or TSS verification —
     * we refuse to submit the bytes (return null) and log a warning, so the bundle is
     * skipped this tick rather than producing a confusing failure on the peer side.
     *
     * <p>Remove once the bundle proof builder is exercised by enough downstream tests
     * that we trust it implicitly.
     */
    @Nullable
    private Bytes assertValidBundleOrNull(
            @NonNull final Bytes proofBytes, @NonNull final Bytes ledgerId, @NonNull final Bytes channelId) {
        if (!configProvider.getConfiguration().getConfigData(ClprConfig.class).verifyProofsAtSender()) {
            return proofBytes;
        }
        try {
            final var stateProof = StateProof.PROTOBUF.parse(proofBytes.toReadableSequentialData());
            if (!stateProof.hasSignedBlockProof()) {
                log.warn(
                        "buildBundleStateProof (sender): proof has no signedBlockProof for channel {}; refusing",
                        channelId);
                return null;
            }
            final var signature = stateProof.signedBlockProof().blockSignature();
            if (signature == null || signature.length() == 0) {
                log.warn(
                        "buildBundleStateProof (sender): proof carries no block signature for channel {}; refusing",
                        channelId);
                return null;
            }
            byte[] rootHash = null;
            for (final var path : stateProof.paths()) {
                if (path.hasStateItemLeaf()) {
                    rootHash = StateProofVerifier.computeBlockRootHashFromPath(path);
                    break;
                }
            }
            if (rootHash == null) {
                log.warn(
                        "buildBundleStateProof (sender): proof has no state-item-leaf path for channel {}; refusing",
                        channelId);
                return null;
            }
            if (!tssVerifier.verifyTss(ledgerId, signature, Bytes.wrap(rootHash))) {
                log.error(
                        "buildBundleStateProof (sender): TSS verification FAILED for ledgerId {} (channel {}); refusing",
                        ledgerId,
                        channelId);
                return null;
            }
            return proofBytes;
        } catch (final Exception e) {
            log.error(
                    "buildBundleStateProof (sender): refusing to submit invalid bundle state proof for channel {} "
                            + "({} bytes): {}",
                    channelId,
                    proofBytes.length(),
                    e.getMessage(),
                    e);
            return null;
        }
    }

    /**
     * Appends this ledger's {@code ClprEndpointManifest} singleton leaf to {@code allPaths} so the
     * peer's verifyBundle can return {@code new_endpoint_manifest} for Step 1b (spec §4.9),
     * but only when <b>both</b> gates are set:
     * <ol>
     *   <li>the caller's staleness check — {@code includeEndpointManifest} is true if the peer's
     *       cached {@code endpoint_manifest_version} is strictly less than our local
     *       {@code ClprEndpointManifest.version()}; suppressing the leaf when the peer is
     *       already current</li>
     *   <li>the master {@code clpr.endpointManifestEnabled} flag</li>
     * </ol>
     * A missing singleton or merkle proof is logged and skipped (not fatal) — the peer simply sees
     * no manifest update this tick.
     */
    private void addEndpointManifestIfEnabled(
            @NonNull final List<MerklePath> allPaths,
            @NonNull final BinaryState binaryState,
            @NonNull final Bytes channelId,
            @NonNull final List<SiblingNode> extendedSibs,
            final boolean includeEndpointManifest) {
        final boolean manifestFeatureEnabled = configProvider
                .getConfiguration()
                .getConfigData(ClprConfig.class)
                .endpointManifestEnabled();
        if (!manifestFeatureEnabled || !includeEndpointManifest) {
            return;
        }
        final long manifestPath = binaryState.getSingletonPath(ENDPOINT_MANIFEST_STATE_ID);
        if (manifestPath < 0) {
            // Invariant violation, not a normal operating condition: the V0650 schema seeds the
            // ClprEndpointManifest singleton at genesis, so with the feature enabled it MUST exist.
            // Its absence means corrupt or unmigrated state — fail loudly rather than silently
            // producing a manifest-less bundle.
            throw new IllegalStateException("CLPR endpoint manifest singleton missing from state (conn="
                    + channelId
                    + ") while clpr.endpointManifestEnabled=true; the V0650 schema seeds this singleton at "
                    + "genesis, so its absence indicates corrupt or unmigrated state");
        }
        final var manifestMerkleProof = binaryState.getMerkleProof(manifestPath);
        if (manifestMerkleProof == null) {
            log.warn(
                    "[CLPR-BUNDLE-BUILD] endpoint manifest merkle proof missing conn={} manifestPath={} - "
                            + "peer will not see a manifest update this tick",
                    channelId,
                    manifestPath);
            return;
        }
        allPaths.add(MerklePathBuilder.fromStateApi(manifestMerkleProof)
                .appendSiblingNodes(extendedSibs)
                .build());
        log.debug(
                "[CLPR-BUNDLE-BUILD] endpoint manifest leaf included conn={} manifestPath={}", channelId, manifestPath);
    }

    private BundleProof buildProof(
            @NonNull final BinaryState binaryState,
            @NonNull final BlockProvenSnapshot snapshot,
            @NonNull final Bytes channelId,
            final long firstMessageId,
            @NonNull final ClprThrottles peerThrottles,
            final boolean allowPureAck,
            final boolean includeEndpointManifest) {

        final int maxMessages = peerThrottles.maxMessagesPerBundle();
        final long maxSyncBytes = peerThrottles.maxSyncBytes();
        final long maxBundlePayloadBytes = maxSyncBytes - CLPR_SYNC_ENVELOPE_OVERHEAD_BYTES;
        log.debug(
                "[CLPR-BUNDLE-BUILD] start conn={} firstMessageId={} maxMessages={} maxSyncBytes={} "
                        + "maxBundlePayloadBytes={} allowPureAck={} snapshotTimestamp={}",
                channelId,
                firstMessageId,
                maxMessages,
                maxSyncBytes,
                maxBundlePayloadBytes,
                allowPureAck,
                snapshot.blockTimestamp());

        // Precompute the block-root extension siblings appended to every leaf path.
        final var tsBytes = Timestamp.PROTOBUF.toBytes(snapshot.blockTimestamp());
        final var hashedTs = Bytes.wrap(HashUtils.computeRawLeafHash(sha384DigestOrThrow(), tsBytes));
        final var baseSibs = snapshot.path().siblings();
        final var extendedSibs = new ArrayList<SiblingNode>(baseSibs.size() + 1);
        extendedSibs.addAll(baseSibs);
        extendedSibs.add(SiblingNode.newBuilder().hash(hashedTs).isLeft(true).build());

        // ── Channel leaf ────────────────────────────────────────────────────────
        final var connKeyBytes = ProtoBytes.PROTOBUF.toBytes(new ProtoBytes(channelId));
        final long connPath = binaryState.getKvPath(CHANNELS_STATE_ID, connKeyBytes);
        if (connPath < 0) {
            log.warn("[CLPR-BUNDLE-BUILD] channel leaf missing conn={}; cannot build state proof", channelId);
            return null;
        }
        final var connMerkleProof = binaryState.getMerkleProof(connPath);
        if (connMerkleProof == null) {
            log.warn("[CLPR-BUNDLE-BUILD] channel merkle proof missing conn={} connPath={}", channelId, connPath);
            return null;
        }
        log.debug("[CLPR-BUNDLE-BUILD] channel leaf included conn={} connPath={}", channelId, connPath);

        // Build each leaf path independently (no merging). Each path is a complete
        // leaf-to-block-root proof verified independently against the block root.
        final var allPaths = new ArrayList<MerklePath>();
        final var connPathBuilder = MerklePathBuilder.fromStateApi(connMerkleProof);
        connPathBuilder.appendSiblingNodes(extendedSibs);
        allPaths.add(connPathBuilder.build());
        // Track how many message-queue leaves we've added, so the pure-ACK guard below
        // can distinguish "no messages" from "channel + manifest but no messages".
        int messageLeafCount = 0;

        // ── Endpoint manifest singleton leaf (spec §4.9) ──────────────────────────
        addEndpointManifestIfEnabled(allPaths, binaryState, channelId, extendedSibs, includeEndpointManifest);

        final var tssProof = TssSignedBlockProof.newBuilder()
                .blockSignature(snapshot.tssSignature())
                .build();

        // Spec §1.1 sender-side cap: the serialized ClprSyncPayload envelope MUST NOT exceed
        // the peer's max_sync_bytes. We trim the message tail as we append paths so we never
        // emit an oversize bundle. Unfit messages stay in the outbound queue keyed by
        // messageId — the next sync tick picks up the same firstMessageId and tries again.
        // ── Message queue leaves ───────────────────────────────────────────────────
        final long batchNextId = firstMessageId + maxMessages;
        for (long id = firstMessageId; id < batchNextId; id++) {
            final var msgKey = ClprMessageKey.newBuilder()
                    .channelId(channelId)
                    .messageId(id)
                    .build();
            final var msgKeyBytes = ClprMessageKey.PROTOBUF.toBytes(msgKey);
            final long msgPath = binaryState.getKvPath(MESSAGE_QUEUE_STATE_ID, msgKeyBytes);
            if (msgPath < 0) {
                // Past the end of the real queue — stop here.
                log.debug(
                        "[CLPR-QUEUE-READ] bundle builder queue miss conn={} messageId={} firstMessageId={} "
                                + "includedMessages={} totalPaths={} allowPureAck={}",
                        channelId,
                        id,
                        firstMessageId,
                        messageLeafCount,
                        allPaths.size(),
                        allowPureAck);
                break;
            }
            final var msgMerkleProof = binaryState.getMerkleProof(msgPath);
            if (msgMerkleProof == null) {
                log.warn(
                        "[CLPR-QUEUE-READ] bundle builder merkle proof missing conn={} messageId={} msgPath={}",
                        channelId,
                        id,
                        msgPath);
                break;
            }
            final var candidatePath = MerklePathBuilder.fromStateApi(msgMerkleProof)
                    .appendSiblingNodes(extendedSibs)
                    .build();
            allPaths.add(candidatePath);
            messageLeafCount++;
            final int candidateSize = StateProof.newBuilder()
                    .paths(allPaths)
                    .signedBlockProof(tssProof)
                    .build()
                    .protobufSize();
            if (candidateSize > maxBundlePayloadBytes) {
                // Drop the last path and stop appending — it will be re-attempted next tick.
                allPaths.removeLast();
                messageLeafCount--;
                if (id == firstMessageId) {
                    // Head-of-queue message can't fit even alone: the channel is wedged. It will be
                    // re-attempted (and fail) every sync tick, the peer never acks, the queue never
                    // drains.
                    log.warn(
                            "buildBundleStateProof: head message {} alone exceeds peer max_sync_bytes "
                                    + "(channel={} candidateBytes={} maxSyncBytes={}). Channel will "
                                    + "deadlock — review peer max_sync_bytes vs. message sizing.",
                            id,
                            channelId,
                            candidateSize,
                            maxSyncBytes);
                } else {
                    log.debug(
                            "buildBundleStateProof: trimmed at message {} to stay within maxSyncBytes "
                                    + "(channel={} included={} maxSyncBytes={})",
                            id,
                            channelId,
                            messageLeafCount,
                            maxSyncBytes);
                }
                break;
            }
            log.debug(
                    "[CLPR-QUEUE-READ] bundle builder included queue leaf conn={} messageId={} msgPath={} "
                            + "candidateBytes={} includedMessages={} firstMessageId={}",
                    channelId,
                    id,
                    msgPath,
                    candidateSize,
                    messageLeafCount,
                    firstMessageId);
        }

        // A proof with no message leaves carries only the current ack frontier
        // (channel.receivedMessageId / receivedRunningHash) plus, since #335, the endpoint
        // manifest. Responder paths (`allowPureAck=true`) need this so they can acknowledge
        // an inbound message even when the local outbound queue is empty — without it, the
        // peer never learns its message was received and replays forever.
        //
        // An initiator (`allowPureAck=false`) with an empty queue still has meaningful work when
        // it must push a manifest advance the peer has not observed — a moved endpoint has to
        // propagate or peers stay stuck dialing the stale address. So permit a manifest-only
        // bundle when the manifest will actually be carried (feature on AND includeEndpointManifest).
        // Only when there is truly nothing — no messages, not a responder ack, and no manifest to
        // send — do we skip. (Neeha review.)
        final boolean manifestOnlyPayload = includeEndpointManifest
                && configProvider
                        .getConfiguration()
                        .getConfigData(ClprConfig.class)
                        .endpointManifestEnabled();
        if (messageLeafCount == 0) {
            if (!allowPureAck && !manifestOnlyPayload) {
                log.debug(
                        "[CLPR-BUNDLE-BUILD] no queued messages and no manifest to push; skipping initiator "
                                + "bundle conn={} firstMessageId={}",
                        channelId,
                        firstMessageId);
                return null;
            }
            log.debug(
                    "[CLPR-BUNDLE-BUILD] building {} bundle conn={} firstMessageId={}",
                    allowPureAck ? "pure-ack" : "manifest-only",
                    channelId,
                    firstMessageId);
        }

        // ── Assemble final StateProof ──────────────────────────────────────────────

        final var proof = StateProof.newBuilder()
                .paths(allPaths)
                .signedBlockProof(tssProof)
                .build();
        final var proofBytes = StateProof.PROTOBUF.toBytes(proof);

        // Final safety check: if even the channel-only (pure-ACK) proof exceeds the cap, the
        // Channel is stuck — the peer will reject every bundle we send. Surface loudly so
        // operators can fix the misconfigured max_sync_bytes (spec §1.1 deadlock warning).
        if (proofBytes.length() > maxBundlePayloadBytes) {
            log.warn(
                    "buildBundleStateProof: bundle exceeds peer max_sync_bytes even after trimming "
                            + "(channel={} firstMessageId={} bundleBytes={} maxSyncBytes={} envelopeReserve={}). "
                            + "Channel may deadlock — review peer's max_sync_bytes vs. message+proof sizing.",
                    channelId,
                    firstMessageId,
                    proofBytes.length(),
                    maxSyncBytes,
                    maxSyncBytes - maxBundlePayloadBytes);
        }
        log.debug(
                "[CLPR-BUNDLE-BUILD] complete conn={} firstMessageId={} includedMessages={} pathCount={} "
                        + "proofBytes={} maxSyncBytes={} allowPureAck={}",
                channelId,
                firstMessageId,
                messageLeafCount,
                allPaths.size(),
                proofBytes.length(),
                maxSyncBytes,
                allowPureAck);
        return new BundleProof(proofBytes, messageLeafCount, firstMessageId + messageLeafCount - 1);
    }
}
