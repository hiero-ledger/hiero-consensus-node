// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.endpointManifestCommitmentSlot;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprQbftLedgerConfigurationPayload;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.sun.jna.ptr.LongByReference;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

/**
 * Verifies Besu QBFT bundle proofs against a peer ledger's execution state.
 *
 * <p>Accepts the RLP-encoded payload produced by
 * {@code org.hiero.clpr.relay.evm.QbftBundleConstructor.PayloadRlpEncoder} in the
 * {@code clpr-evm-endpoint} repo. Top-level layout (5 items, or 7 with the endpoint-manifest
 * extension at indices 5-6, currently accepted-and-ignored):
 * <pre>
 *   index 0: currentBlockHeader    // RLP list of 15..23 Ethereum header fields
 *   index 1: epochBlockHeaders[]   // RLP list-of-lists; may be empty when trust anchor is current
 *   index 2: accountProof          // RLP list of MPT trie nodes
 *   index 3: storageProof          // RLP list of [key, proof[]] pairs; 4 or 5 entries
 *   index 4: bundleContent         // RLP bytes: protobuf ClprBundleContent
 *   index 5: manifestStorageProof  // (optional) peer endpoint-manifest storage proof (Step 1b)
 *   index 6: manifestPreimage      // (optional) protobuf ClprEndpointManifest preimage
 * </pre>
 *
 * <p>Storage-proof slot layout (SC-189 Channel struct, 5 or 6 entries). The relay proves these
 * Channel-struct slots at {@code keccak(connId,15)+offset}:
 * <pre>
 *   offset  1: verifier(20) | status(1) | nextMessageId(8)
 *   offset  2: ackedMessageId(8) | receivedMessageId(8) | nextExpectedReplyId(8)
 *   offset  4: sentRunningHash (bytes32)
 *   offset  5: receivedRunningHash (bytes32)
 *   offset 16: endpointManifestVersion (uint64) — not part of the queue metadata; ignored here
 *   + (with messages) the last message's running-hash slot (a separate keccak, message-queue mapping)
 * </pre>
 * The five Channel slots cluster within a 16-wide window; {@link #reorderQueueSlots} isolates them
 * from the message running-hash outlier before decoding.
 *
 * <p>Verification steps:
 * <ol>
 *   <li>Walk the epoch-header chain (field 1): for each header, verify its QBFT committed seal
 *       against the current trust anchor and advance the anchor to the new validator declared in
 *       that header's extra-data validators list.</li>
 *   <li>Verify {@code currentBlockHeader}'s committed seal against the final (post-epoch) trust
 *       anchor.</li>
 *   <li>Verify the account proof against {@code currentBlockHeader.stateRoot}; recover the contract
 *       account.</li>
 *   <li>For each storage-proof entry, verify against the proven account's {@code storageRoot}.</li>
 *   <li>Return the proven block hash, bundle-content bytes, queue metadata, and — when epoch
 *       headers were present — the new 20-byte validator address and epoch number.</li>
 * </ol>
 */
public final class BesuQbftVerifier {

    private static final Logger log = LogManager.getLogger(BesuQbftVerifier.class);
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Block-header RLP field counts permitted by Ethereum / Besu hard forks.
     * 15 = pre-EIP-1559 base; each fork after appends optional fields:
     *   +baseFeePerGas (London)
     *   +withdrawalsRoot (Shanghai)
     *   +blobGasUsed, +excessBlobGas (Cancun / EIP-4844)
     *   +parentBeaconBlockRoot (Cancun / EIP-4788)
     *   +requestsHash (Prague / EIP-7685)
     *   +blockAccessListHash (Osaka)
     *   +slotNumber (post-Osaka)
     * Total = 23 after all currently-known forks. Reference:
     *   besu/ethereum/core/.../BlockHeader.java#writeTo @ tag 26.5.0.
     */
    private static final int MIN_HEADER_FIELDS = 15;

    private static final int MAX_HEADER_FIELDS = 23;

    /** Field index of {@code stateRoot} inside the canonical Ethereum block-header RLP. */
    private static final int HEADER_STATE_ROOT_INDEX = 3;

    /** Field index of {@code extraData} inside the canonical Ethereum block-header RLP. */
    private static final int HEADER_EXTRA_DATA_INDEX = 12;

    /** Field index of {@code number} (block number) inside the canonical Ethereum block-header RLP. */
    private static final int HEADER_BLOCK_NUMBER_INDEX = 8;

    /** {@code keccak256(RLP(empty string)) = keccak256(0x80)} — the empty MPT root. */
    private static final byte[] EMPTY_TRIE_ROOT =
            hexToBytes32("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421");

    // ── QBFT extra-data layout: RLP[ vanity, validators[], vote, round, committedSeals[] ] ──
    private static final int QBFT_EXTRA_FIELDS = 5;
    private static final int QBFT_EXTRA_INDEX_COMMITTED_SEALS = 4;

    /** Bytes per committed seal: r(32) || s(32) || v(1). */
    private static final int QBFT_SEAL_LENGTH = 65;

    /** Index of the validators list inside QBFT extra-data. */
    private static final int QBFT_EXTRA_INDEX_VALIDATORS = 1;

    /** QBFT commit-message code (Besu's {@code MessageType.COMMIT.getCode()}). */
    private static final int QBFT_COMMIT_MSG_CODE = 2;

    /** Length in bytes of an Ethereum address. */
    private static final int ADDRESS_LENGTH = 20;

    // ── Storage-proof layout produced by QbftBundleConstructor (SC-189 Channel struct) ──
    // The relay proves 5 Channel-struct slots — keccak(connId,15)+{1,2,4,5,16} = status|nextMsgId,
    // acked|received|reply, sentRunningHash, receivedRunningHash, endpointManifestVersion — plus, when
    // the bundle carries messages, the running-hash slot of the last message. So a bundle proves 5
    // (ACK-only) or 6 (with messages) storage slots. The five Channel slots cluster within a
    // 16-wide window (offsets 1..16); the message running-hash slot is a far keccak outlier.
    private static final int CHANNEL_STORAGE_SLOTS = 5;
    // max(offset)-min(offset) across the proven Channel slots = 16 - 1 = 15.
    private static final int CHANNEL_SLOT_CLUSTER_SPAN = 15;

    // SC-189 storage layout of the `_channels` mapping and the proven Channel-struct field
    // offsets, used for EXACT slot-key matching when the channelId is threaded into verifyBundle
    // (base = keccak256(channelId(32) || uint256(CHANNELS_MAPPING_SLOT))).
    private static final int CHANNELS_MAPPING_SLOT = 15;
    private static final int CHANNEL_OFFSET_STATUS_NEXTMSGID = 1;
    private static final int CHANNEL_OFFSET_ACKED_RECEIVED = 2;
    private static final int CHANNEL_OFFSET_SENT_RUNNING_HASH = 4;
    private static final int CHANNEL_OFFSET_RECEIVED_RUNNING_HASH = 5;
    private static final int CHANNEL_OFFSET_ENDPOINT_MANIFEST_VERSION = 16;
    // After reordering into the queue-metadata positional layout, decodeQueueMetadata sees 4 slots
    // (ACK-only) or 5 (with the message running-hash appended).
    private static final int STORAGE_PROOF_MIN_ENTRIES = 4;
    private static final int STORAGE_PROOF_MAX_ENTRIES = 5;
    private static final int SP_INDEX_CHANNEL_STATUS_NEXTMSGID = 0;

    /**
     * EVM storage slot for {@code _config.serviceAddress} in the deployed ClprService contract.
     * Slot 25 = _config struct base (23) + serviceAddress field offset (2). SC-189 shifted the
     * _config base from 21 to 23 by inserting _endpointManifest and _peerEndpointManifests before it.
     */
    static final byte[] SERVICE_ADDR_STORAGE_SLOT = new byte[32];

    static {
        SERVICE_ADDR_STORAGE_SLOT[31] = (byte) 25;
    }

    /**
     * RLP field count of the config-path endpoint-manifest proof supplied as the third
     * {@code verifyConfig} argument: {@code RLP([blockHeader, accountProof, manifestStorageProof,
     * manifestPreimage])}. Mirrors SC-189 {@code QBFTVerifier.CONFIG_MANIFEST_PROOF_FIELDS} so the
     * Hiero native verifier accepts exactly the wire form the peer QBFT verifier contract does.
     */
    private static final int CONFIG_MANIFEST_PROOF_FIELDS = 4;

    /** RLP index of the block header inside the config-path endpoint-manifest proof. */
    private static final int CFG_MANIFEST_HEADER_INDEX = 0;
    /** RLP index of the CLPR-service account proof inside the config-path endpoint-manifest proof. */
    private static final int CFG_MANIFEST_ACCOUNT_PROOF_INDEX = 1;
    /** RLP index of the commitment-slot storage proof inside the config-path endpoint-manifest proof. */
    private static final int CFG_MANIFEST_STORAGE_PROOF_INDEX = 2;
    /** RLP index of the protobuf manifest preimage inside the config-path endpoint-manifest proof. */
    private static final int CFG_MANIFEST_PREIMAGE_INDEX = 3;

    private static final int SP_INDEX_CHANNEL_RECEIVED_MSG_ID = 1;
    private static final int SP_INDEX_CHANNEL_SENT_RUNNING_HASH = 2;
    private static final int SP_INDEX_CHANNEL_RECEIVED_RUNNING_HASH = 3;
    private static final int SP_INDEX_LAST_MSG_RUNNING_HASH = 4; // optional

    private final Config config;

    public BesuQbftVerifier(@NonNull final Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Decode and structurally verify a QBFT bundle payload, returning the proven block hash and
     * the (verbatim) protobuf-serialized {@code ClprBundleContent} bytes pulled from the proof's
     * final RLP item.
     *
     * @param bundlePayload the RLP-encoded payload as produced by
     *     {@code QbftBundleConstructor.PayloadRlpEncoder.encodeQbftBundlePayload}
     * @param trustAnchor opaque trust-anchor material; reserved for future block-header
     *     authentication, currently unused
     * @return the verified block hash plus the protobuf-serialized {@code ClprBundleContent}
     * @throws ProofException if RLP decoding fails or any structural check is violated
     */
    @NonNull
    public VerifiedBundle verifyBundle(@NonNull final byte[] bundlePayload, @NonNull final byte[] trustAnchor) {
        return verifyBundle(bundlePayload, trustAnchor, new byte[0]);
    }

    /**
     * As {@link #verifyBundle(byte[], byte[])}, additionally binding the proven Channel storage
     * slots to {@code channelId} for <em>exact</em> slot-key matching. When {@code channelId} is
     * a 32-byte id, the four queue-metadata slots and the (optional) message running-hash slot are
     * identified by recomputing their canonical storage keys — {@code keccak256(channelId || 15)}
     * (the {@code _channels} mapping base) plus field offsets {@code {1,2,4,5}} and {@code 16} for
     * {@code endpointManifestVersion} — rather than by the connId-less span heuristic
     * ({@link #reorderQueueSlots}). Any proven slot that matches none of the expected Channel keys
     * is taken to be the last message's running-hash slot; more than one such leftover is a hard error.
     * An empty {@code channelId} (legacy, non-manifest callers) falls back to the span heuristic.
     */
    @NonNull
    public VerifiedBundle verifyBundle(
            @NonNull final byte[] bundlePayload, @NonNull final byte[] trustAnchor, @NonNull final byte[] channelId) {
        Objects.requireNonNull(bundlePayload, "bundlePayload");
        Objects.requireNonNull(trustAnchor, "trustAnchor");
        Objects.requireNonNull(channelId, "channelId");
        if (channelId.length != 0 && channelId.length != 32) {
            throw ProofException.besuQbft("channelId must be empty or 32 bytes, got " + channelId.length);
        }

        final List<byte[]> trustAnchorValidators = decodeValidatorSet(trustAnchor, "trustAnchor");
        log.debug(
                "BesuQbftProofVerifier.verifyBundle ENTER: bundlePayload={} bytes, trustAnchor(validators={})=0x{}",
                bundlePayload.length,
                trustAnchorValidators.size(),
                HEX.formatHex(trustAnchor));
        log.debug(
                "BesuQbftProofVerifier.verifyBundle config: expectedContractAddress=0x{}, expectedContractCodeHash=0x{}",
                config.expectedContractAddress20() == null
                        ? "<null>"
                        : HEX.formatHex(config.expectedContractAddress20()),
                config.expectedContractCodeHash32() == null
                        ? "<null>"
                        : HEX.formatHex(config.expectedContractCodeHash32()));

        final Rlp.Item top;
        try {
            top = Rlp.decodeOne(bundlePayload);
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("payload is not a valid RLP item: " + e.getMessage());
        }
        // A bundle is 5 items, or 7 when the peer attaches its endpoint-manifest advance (spec §4.9):
        // index 5 = manifest storage proof, index 6 = manifest preimage (verified in step 5 below). The
        // message content and queue metadata live in indices 0-4. A manifest-only advance is simply a 7-item
        // bundle with empty content — the exact wire form the QBFT verifier contract and the clpr-evm-endpoint
        // relay produce; there is no distinct "manifest-only" bundle shape. (The 4-item
        // RLP([blockHeader, accountProof, manifestStorageProof, manifestPreimage]) form is the config-path
        // proof consumed by verifyConfig, never a bundle.)
        final int itemCount = top.isList() ? top.children().size() : -1;
        if (itemCount != 5 && itemCount != 7) {
            throw ProofException.besuQbft("expected top-level RLP list of 5 or 7 items, got "
                    + (top.isList() ? itemCount + " items" : "non-list"));
        }
        final List<Rlp.Item> fields = top.children();

        final Rlp.Item epochBlockHeadersItem = fields.get(1);
        if (!epochBlockHeadersItem.isList()) {
            throw ProofException.besuQbft("epochBlockHeaders (field 1) must be an RLP list");
        }

        // --- 0. Walk epoch headers (if any), advancing the trust anchor -----------------
        final byte[] effectiveTrustAnchorEncoded =
                epochBlockHeadersItem.children().isEmpty()
                        ? trustAnchor
                        : processEpochHeaders(epochBlockHeadersItem, trustAnchor);
        final List<byte[]> effectiveTrustAnchor =
                decodeValidatorSet(effectiveTrustAnchorEncoded, "effectiveTrustAnchor");
        final byte[] newTrustAnchor;
        final byte[] newTrustAnchorId;
        if (!epochBlockHeadersItem.children().isEmpty()) {
            newTrustAnchor = effectiveTrustAnchorEncoded;
            final Rlp.Item lastEpochHeader = epochBlockHeadersItem
                    .children()
                    .get(epochBlockHeadersItem.children().size() - 1);
            newTrustAnchorId = epochNumberBytesFromHeader(lastEpochHeader, config.epochLength());
            log.debug(
                    "BesuQbftProofVerifier.verifyBundle epochHeaders={} newValidators={} epochNumber(bytes)=0x{}",
                    epochBlockHeadersItem.children().size(),
                    effectiveTrustAnchor.size(),
                    HEX.formatHex(newTrustAnchorId));
        } else {
            newTrustAnchor = new byte[0];
            newTrustAnchorId = new byte[0];
        }

        // --- 1. Parse the block header and extract the state root --------------------------
        final Rlp.Item headerItem = fields.get(0);
        if (!headerItem.isList()
                || headerItem.children().size() < MIN_HEADER_FIELDS
                || headerItem.children().size() > MAX_HEADER_FIELDS) {
            throw ProofException.besuQbft(
                    "block header is not an RLP list of " + MIN_HEADER_FIELDS + ".." + MAX_HEADER_FIELDS + " fields");
        }
        final byte[] stateRoot32 =
                checkedCopy(headerItem.children().get(HEADER_STATE_ROOT_INDEX).asBytes(), 32, "blockHeader.stateRoot");
        final byte[] blockHash32 = keccak256(headerItem.rawBytes());
        log.debug(
                "BesuQbftProofVerifier.verifyBundle header: blockHash=0x{}, stateRoot=0x{}, headerFields={}",
                HEX.formatHex(blockHash32),
                HEX.formatHex(stateRoot32),
                headerItem.children().size());

        // Authenticate the header against the effective validator set; requires a quorum of valid seals.
        verifyQbftSealAgainstValidatorSet(headerItem, effectiveTrustAnchor);
        log.debug("BesuQbftProofVerifier.verifyBundle QBFT committed seal verified against trustAnchor");

        // --- 2. Verify the account proof against the block's state root --------------------
        if (config.expectedContractAddress20() == null) {
            throw ProofException.besuQbft("config.expectedContractAddress20 is required to verify the account proof");
        }
        final byte[] contractAddress20 = config.expectedContractAddress20();
        final byte[][] accountProof = decodeNodeList(fields.get(2), "accountProof");
        final byte[] accountKey = keccak256(contractAddress20);
        final byte[] accountRlp = Mpt.get(stateRoot32, accountKey, accountProof)
                .orElseThrow(() -> ProofException.besuQbft("contract account is absent from state trie"));
        final Account provenAccount = Account.decode(accountRlp);
        log.debug(
                "BesuQbftProofVerifier.verifyBundle account proof verified: contractAddress=0x{}, accountProofNodes={}, provenStorageRoot=0x{}, provenCodeHash=0x{}",
                HEX.formatHex(contractAddress20),
                accountProof.length,
                HEX.formatHex(provenAccount.storageRoot32()),
                HEX.formatHex(provenAccount.codeHash32()));

        if (config.expectedContractCodeHash32() != null
                && !Arrays.equals(config.expectedContractCodeHash32(), provenAccount.codeHash32())) {
            throw ProofException.besuQbft("proven contract codeHash 0x"
                    + HEX.formatHex(provenAccount.codeHash32())
                    + " does not match expected 0x"
                    + HEX.formatHex(config.expectedContractCodeHash32()));
        }

        // --- 3+4. Verify the queue storage proof and decode the queue metadata -------------
        // A bundle that advances only the endpoint manifest carries no queue storage proof (an empty list at
        // index 3); its queue metadata is absent (all-zero sentinel, nextMessageId == 0). Otherwise prove the
        // five Channel slots (+ an optional message running-hash slot) and decode. The §8.1.4 invariant
        // below rejects a bundle that carries neither queue metadata, content, nor a manifest advance.
        final List<StorageProofEntry> storageProof = decodeStorageProofList(fields.get(3));
        final QueueMetadata queueMetadata;
        if (storageProof.isEmpty()) {
            // Absent queue metadata: the all-zero sentinel (nextMessageId == 0).
            queueMetadata = new QueueMetadata(0L, new byte[32], 0L, new byte[32], 0, new byte[32]);
        } else {
            if (storageProof.size() != CHANNEL_STORAGE_SLOTS && storageProof.size() != CHANNEL_STORAGE_SLOTS + 1) {
                throw ProofException.besuQbft("storageProof has " + storageProof.size() + " entries; expected "
                        + CHANNEL_STORAGE_SLOTS + " (ACK-only), " + (CHANNEL_STORAGE_SLOTS + 1)
                        + " (with messages), or 0 (manifest-only)");
            }
            // Sort entries by slot key (big-endian unsigned) for a canonical order independent of the
            // EVM's storageProof delivery order.
            final List<StorageProofEntry> orderedStorageProof = new ArrayList<>(storageProof);
            orderedStorageProof.sort((a, b) -> Arrays.compareUnsigned(
                    leftPad32(a.key(), "storageProof.key"), leftPad32(b.key(), "storageProof.key")));
            final int entryCount = orderedStorageProof.size();
            final byte[][] slotKeys = new byte[entryCount][];
            final byte[][] provenSlotValues = new byte[entryCount][];
            for (int i = 0; i < entryCount; i++) {
                final StorageProofEntry entry = orderedStorageProof.get(i);
                final byte[] slot32 = leftPad32(entry.key(), "storageProof[" + i + "].key");
                final byte[] storageKey = keccak256(slot32);
                final byte[] provenValue32 = Mpt.get(provenAccount.storageRoot32(), storageKey, entry.proofNodes())
                        .map(Rlp::decodeTrieStorageValueAsBytes32)
                        .orElseGet(() -> new byte[32]);
                slotKeys[i] = slot32;
                provenSlotValues[i] = provenValue32;
                log.debug(
                        "BesuQbftProofVerifier.verifyBundle storageProof[{}] verified: slotKey=0x{}, provenValue=0x{}, proofNodes={}",
                        i,
                        HEX.formatHex(slot32),
                        HEX.formatHex(provenValue32),
                        entry.proofNodes().length);
            }
            // Reorder the sorted slots into the positional layout decodeQueueMetadata expects:
            // [status|nextMsgId, acked|received|reply, sentRunningHash, receivedRunningHash] and — when a
            // message is present — the last-message running hash. The 5 Channel slots cluster (min..max
            // span == 15); the message running-hash slot is a far keccak outlier, and the cluster's 5th
            // slot (endpointManifestVersion) is not part of the queue metadata and is dropped.
            final byte[][] orderedQueueSlots = channelId.length == 32
                    ? matchQueueSlotsByConnId(slotKeys, provenSlotValues, channelId)
                    : reorderQueueSlots(slotKeys, provenSlotValues);
            queueMetadata = decodeQueueMetadata(orderedQueueSlots);
            log.debug(
                    "BesuQbftProofVerifier.verifyBundle queueMetadata: nextMessageId={}, receivedMessageId={}, status={}, sentRunningHash=0x{}, receivedRunningHash=0x{}, lastMessageRunningHash=0x{}",
                    queueMetadata.nextMessageId(),
                    queueMetadata.receivedMessageId(),
                    queueMetadata.status(),
                    HEX.formatHex(queueMetadata.sentRunningHash()),
                    HEX.formatHex(queueMetadata.receivedRunningHash()),
                    HEX.formatHex(queueMetadata.lastMessageRunningHash()));
        }

        // --- 5. Bundle content (last non-manifest RLP item) -------------------
        final byte[] bundleContentBytes = fields.get(4).asBytes();
        log.debug(
                "BesuQbftProofVerifier.verifyBundle EXIT: SUCCESS blockHash=0x{}, bundleContent={} bytes",
                HEX.formatHex(blockHash32),
                bundleContentBytes.length);
        // --- 5. Verify the endpoint-manifest advance (Step 1b), if the bundle carries one --------
        // A 7-item bundle appends the peer's endpoint-manifest proof: index 5 = the commitment-slot
        // storage proof (entry-wrapped [slot,[nodes]]), index 6 = the protobuf manifest preimage. Verify
        // it against the same authenticated account storage root and bind service_address to the trusted
        // peer service; return the verified preimage bytes (empty when the bundle carries no manifest).
        final byte[] newEndpointManifestBytes = fields.size() == 7
                ? verifyEndpointManifestProof(
                        fields.get(5), fields.get(6).asBytes(), provenAccount.storageRoot32(), contractAddress20)
                : new byte[0];

        // A bundle must carry SOMETHING: queue metadata, message content, or a manifest advance. When both the
        // queue metadata and the content are absent, the bundle MUST carry a manifest advance (spec §8.1.4);
        // otherwise it is empty/meaningless and verification fails. (Mirrors the Ethereum verifier.)
        if (storageProof.isEmpty() && bundleContentBytes.length == 0 && newEndpointManifestBytes.length == 0) {
            throw ProofException.besuQbft("bundle has no queue metadata, no content, and no endpoint-manifest advance");
        }

        return new VerifiedBundle(
                blockHash32,
                bundleContentBytes,
                queueMetadata,
                newTrustAnchor,
                newTrustAnchorId,
                newEndpointManifestBytes);
    }

    /**
     * Decode and verify a QBFT config payload, returning the proven
     * {@link ClprLedgerConfiguration} bytes.
     *
     * <p>Top-level RLP layout (5 items):
     * <pre>
     *   [ genesisBlockHeader,  // RLP list — extraData holds the initial QBFT validator set
     *     currentBlockHeader,  // RLP list — QBFT committed seal must recover to genesis validator
     *     configBytes,         // RLP bytes — protobuf-serialized ClprLedgerConfiguration
     *     accountProof,        // RLP list of MPT trie nodes
     *     storageProof ]       // RLP list of [key, value, proofNodes[]] entries (at least 1)
     * </pre>
     *
     * <p>Verification steps:
     * <ol>
     *   <li>Extract the single validator address from genesis extraData validators list.</li>
     *   <li>Verify the QBFT committed seal on {@code currentBlockHeader} against that address.</li>
     *   <li>Preliminary-parse {@code configBytes} to obtain {@code service_address} (the CLPR
     *       contract address on the peer ledger); use it as the MPT account-proof lookup key.</li>
     *   <li>Verify the account proof against {@code currentBlockHeader.stateRoot}.</li>
     *   <li>Verify each storage-proof entry against the proven account's storage root.</li>
     * </ol>
     */
    @NonNull
    public VerifiedConfig verifyConfigPayload(@NonNull final byte[] configPayload) {
        return verifyConfigPayload(configPayload, new byte[0]);
    }

    /**
     * As {@link #verifyConfigPayload(byte[])}, additionally verifying the peer's endpoint-manifest
     * state against the same config block when {@code endpointManifestProofBytes} is non-empty
     * (spec §4.4 / §2.4.2). The proof is {@code RLP([blockHeader, accountProof, manifestStorageProof,
     * manifestPreimage])} — mirroring SC-189 {@code QBFTVerifier._verifyConfigEndpointManifest}: the
     * header seal is authenticated against the config's genesis validator set, the CLPR-service account
     * is authenticated against the header's state root (codeHash unpinned here — the config trust anchor
     * establishes the account, and bundle-time proofs pin the codeHash), then the manifest commitment is
     * bound and {@code service_address} checked against the proven config service address.
     *
     * <p>An empty proof yields empty {@link VerifiedConfig#endpointManifestBytes()}; the caller decides
     * the bring-up fallback (the real manifest then arrives via the first bundle's Step 1b).
     */
    @NonNull
    public VerifiedConfig verifyConfigPayload(
            @NonNull final byte[] configPayload, @NonNull final byte[] endpointManifestProofBytes) {
        Objects.requireNonNull(configPayload, "configPayload");
        Objects.requireNonNull(endpointManifestProofBytes, "endpointManifestProofBytes");
        log.info(
                "BesuQbftProofVerifier.verifyConfigPayload ENTER: {} bytes, endpointManifestProof={} bytes",
                configPayload.length,
                endpointManifestProofBytes.length);

        final ClprQbftLedgerConfigurationPayload payload;
        try {
            // Spec §1: "Implementations MUST reject messages containing unrecognized fields."
            // Strict parse propagates to the nested ClprLedgerConfiguration.
            payload = ClprQbftLedgerConfigurationPayload.PROTOBUF.parseStrict(
                    Bytes.wrap(configPayload).toReadableSequentialData());
        } catch (final Exception e) {
            throw ProofException.besuQbft(
                    "configPayload is not a valid ClprQbftLedgerConfigurationPayload: " + e.getMessage());
        }

        if (payload.genesisBlockHeader() == null
                || payload.genesisBlockHeader().rlp().length() == 0) {
            throw ProofException.besuQbft("ClprQbftLedgerConfigurationPayload.genesisBlockHeader is missing or empty");
        }
        if (payload.currentBlockHeader() == null
                || payload.currentBlockHeader().rlp().length() == 0) {
            throw ProofException.besuQbft("ClprQbftLedgerConfigurationPayload.currentBlockHeader is missing or empty");
        }
        if (payload.ledgerConfiguration() == null) {
            throw ProofException.besuQbft("ClprQbftLedgerConfigurationPayload.ledgerConfiguration is missing");
        }

        // --- 1. RLP-decode both block headers (rlp field contains raw Ethereum header bytes) ---
        final byte[] genesisHeaderBytes = payload.genesisBlockHeader().rlp().toByteArray();
        final byte[] currentHeaderBytes = payload.currentBlockHeader().rlp().toByteArray();

        final Rlp.Item genesisHeaderItem;
        final Rlp.Item currentHeaderItem;
        try {
            genesisHeaderItem = Rlp.decodeOne(genesisHeaderBytes);
            currentHeaderItem = Rlp.decodeOne(currentHeaderBytes);
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("block header RLP decoding failed: " + e.getMessage());
        }
        if (!genesisHeaderItem.isList()
                || genesisHeaderItem.children().size() < MIN_HEADER_FIELDS
                || genesisHeaderItem.children().size() > MAX_HEADER_FIELDS) {
            throw ProofException.besuQbft("genesisBlockHeader is not an RLP list of " + MIN_HEADER_FIELDS + ".."
                    + MAX_HEADER_FIELDS + " fields");
        }
        if (!currentHeaderItem.isList()
                || currentHeaderItem.children().size() < MIN_HEADER_FIELDS
                || currentHeaderItem.children().size() > MAX_HEADER_FIELDS) {
            throw ProofException.besuQbft("currentBlockHeader is not an RLP list of " + MIN_HEADER_FIELDS + ".."
                    + MAX_HEADER_FIELDS + " fields");
        }
        final byte[] stateRoot32 = checkedCopy(
                currentHeaderItem.children().get(HEADER_STATE_ROOT_INDEX).asBytes(),
                32,
                "currentBlockHeader.stateRoot");
        final byte[] blockHash32 = keccak256(currentHeaderItem.rawBytes());

        // --- 2. Extract all validators from genesis extra-data ----------------------------
        final List<byte[]> genesisValidators = extractValidators(genesisHeaderItem);
        log.info("BesuQbftProofVerifier.verifyConfigPayload: genesisValidators={}", genesisValidators.size());

        // --- 3. Verify QBFT committed seal on current header against the genesis validator set ---
        verifyQbftSealAgainstValidatorSet(currentHeaderItem, genesisValidators);
        log.info("BesuQbftProofVerifier.verifyConfigPayload: QBFT committed seal verified");

        // --- 4. Read service_address directly from the ledger configuration -
        final byte[] contractAddress20 = extractServiceAddress(payload.ledgerConfiguration());
        log.info("BesuQbftProofVerifier.verifyConfigPayload: service_address=0x{}", HEX.formatHex(contractAddress20));

        // --- 5. Verify account proof against current block's state root -------------------
        final byte[][] accountProof = payload.clprServiceAccountProof().stream()
                .map(Bytes::toByteArray)
                .toArray(byte[][]::new);
        final byte[] accountKey = keccak256(contractAddress20);
        final byte[] accountRlp = Mpt.get(stateRoot32, accountKey, accountProof)
                .orElseThrow(() -> ProofException.besuQbft("CLPR service account absent from state trie"));
        final Account provenAccount = Account.decode(accountRlp);
        log.info(
                "BesuQbftProofVerifier.verifyConfigPayload: account proof verified: storageRoot=0x{}",
                HEX.formatHex(provenAccount.storageRoot32()));

        if (config.expectedContractCodeHash32() != null
                && !Arrays.equals(config.expectedContractCodeHash32(), provenAccount.codeHash32())) {
            throw ProofException.besuQbft("proven contract codeHash does not match config.expectedContractCodeHash");
        }

        // --- 6. Verify the single service_address storage slot ----------------------------
        final var storageProofs = payload.clprServiceStorageProofs();
        if (storageProofs.isEmpty()) {
            throw ProofException.besuQbft("clprServiceStorageProofs must contain at least 1 entry");
        }
        final var serviceAddrEntry = storageProofs.stream()
                .filter(e ->
                        Arrays.equals(leftPad32(e.key().toByteArray(), "storageProof.key"), SERVICE_ADDR_STORAGE_SLOT))
                .findFirst()
                .orElseThrow(() -> ProofException.besuQbft(
                        "clprServiceStorageProofs does not contain an entry for the serviceAddress slot (0x"
                                + HEX.formatHex(SERVICE_ADDR_STORAGE_SLOT) + ")"));
        final byte[] storageKey = keccak256(SERVICE_ADDR_STORAGE_SLOT);
        final byte[][] storageProofNodes =
                serviceAddrEntry.proof().stream().map(Bytes::toByteArray).toArray(byte[][]::new);
        final byte[] provenValue32 = Mpt.get(provenAccount.storageRoot32(), storageKey, storageProofNodes)
                .map(Rlp::decodeTrieStorageValueAsBytes32)
                .orElseGet(() -> new byte[32]);
        // _config.serviceAddress is Solidity `bytes` (not `address`). Short-bytes(20) slot layout:
        //   [addr 20B left-aligned] [zeros 11B] [length*2 = 0x28]
        final byte[] expectedValue32 = new byte[32];
        System.arraycopy(contractAddress20, 0, expectedValue32, 0, ADDRESS_LENGTH);
        expectedValue32[31] = (byte) (ADDRESS_LENGTH * 2);
        if (!Arrays.equals(expectedValue32, provenValue32)) {
            throw ProofException.besuQbft("service_address storage slot does not match proven value");
        }
        log.info(
                "BesuQbftProofVerifier.verifyConfigPayload: service_address slot verified: key=0x{} value=0x{}",
                HEX.formatHex(SERVICE_ADDR_STORAGE_SLOT),
                HEX.formatHex(provenValue32));

        final var initialTrustAnchor = Bytes.wrap(Rlp.encodeList(List.of(
                Rlp.encodeBytes(encodeValidatorSet(genesisValidators)),
                Rlp.encodeBytes(checkedCopy(contractAddress20, ADDRESS_LENGTH, "clprServiceAddress")),
                Rlp.encodeBytes(checkedCopy(provenAccount.codeHash32(), 32, "clprServiceCodeHash")))));
        final var ledgerCfg = payload.ledgerConfiguration()
                .copyBuilder()
                .initialTrustAnchor(initialTrustAnchor)
                .initialTrustAnchorId(Bytes.wrap(BigInteger.ZERO.toByteArray()))
                .build();

        // --- 7. Verify the endpoint-manifest state proof (Step 1b bootstrap), if one was supplied ----
        // A non-empty third verifyConfig argument carries the peer's endpoint-manifest state proof,
        // authenticated against the same config block. Empty → byte[0]; the caller applies its bring-up
        // fallback (the real manifest then arrives via the first bundle's Step 1b).
        final byte[] endpointManifestBytes = endpointManifestProofBytes.length == 0
                ? new byte[0]
                : verifyConfigEndpointManifestProof(endpointManifestProofBytes, genesisValidators, contractAddress20);

        log.info(
                "BesuQbftProofVerifier.verifyConfigPayload EXIT: SUCCESS blockHash=0x{} chainId={} initialTrustAnchor({} bytes)=0x{} initialTrustAnchorId({} bytes)=0x{} endpoints={} endpointManifest={} bytes",
                HEX.formatHex(blockHash32),
                ledgerCfg.chainId(),
                ledgerCfg.initialTrustAnchor().length(),
                ledgerCfg.initialTrustAnchor().toHex(),
                ledgerCfg.initialTrustAnchorId().length(),
                ledgerCfg.initialTrustAnchorId().toHex(),
                ledgerCfg.endpoints().size(),
                endpointManifestBytes.length);
        return new VerifiedConfig(blockHash32, ledgerCfg, endpointManifestBytes);
    }

    /**
     * Verify the config-path endpoint-manifest proof {@code RLP([blockHeader, accountProof,
     * manifestStorageProof, manifestPreimage])} against the config block, mirroring SC-189
     * {@code QBFTVerifier._verifyConfigEndpointManifest}. The header seal is authenticated against the
     * config's {@code configValidators}; the CLPR-service account is authenticated against the header
     * state root (codeHash unpinned — the config trust anchor establishes the account); then the manifest
     * commitment slot is bound to the preimage and {@code service_address} checked against
     * {@code serviceAddress20}. Returns the verified preimage bytes (never empty on success).
     */
    private static byte[] verifyConfigEndpointManifestProof(
            @NonNull final byte[] endpointManifestProofBytes,
            @NonNull final List<byte[]> configValidators,
            @NonNull final byte[] serviceAddress20) {
        final Rlp.Item top;
        try {
            top = Rlp.decodeOne(endpointManifestProofBytes);
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("endpoint-manifest config proof is not valid RLP: " + e.getMessage());
        }
        if (!top.isList() || top.children().size() != CONFIG_MANIFEST_PROOF_FIELDS) {
            throw ProofException.besuQbft("endpoint-manifest config proof must be an RLP list of "
                    + CONFIG_MANIFEST_PROOF_FIELDS + " items, got "
                    + (top.isList() ? top.children().size() + " items" : "non-list"));
        }
        final List<Rlp.Item> p = top.children();

        // 1. Authenticate the manifest proof's block header against the config validator set.
        final Rlp.Item headerItem = p.get(CFG_MANIFEST_HEADER_INDEX);
        if (!headerItem.isList()
                || headerItem.children().size() < MIN_HEADER_FIELDS
                || headerItem.children().size() > MAX_HEADER_FIELDS) {
            throw ProofException.besuQbft("endpoint-manifest config proof header is not an RLP list of "
                    + MIN_HEADER_FIELDS + ".." + MAX_HEADER_FIELDS + " fields");
        }
        verifyQbftSealAgainstValidatorSet(headerItem, configValidators);
        final byte[] stateRoot32 = checkedCopy(
                headerItem.children().get(HEADER_STATE_ROOT_INDEX).asBytes(),
                32,
                "endpointManifestConfigProof.stateRoot");

        // 2. Authenticate the CLPR-service account against the header's state root (codeHash unpinned).
        final byte[][] accountProof =
                decodeNodeList(p.get(CFG_MANIFEST_ACCOUNT_PROOF_INDEX), "endpointManifestConfigProof.accountProof");
        final byte[] accountRlp = Mpt.get(stateRoot32, keccak256(serviceAddress20), accountProof)
                .orElseThrow(() -> ProofException.besuQbft(
                        "CLPR service account absent from state trie in endpoint-manifest config proof"));
        final Account provenAccount = Account.decode(accountRlp);

        // 3. Bind the manifest commitment and return the verified preimage (reuses the bundle-path check).
        return verifyEndpointManifestProof(
                p.get(CFG_MANIFEST_STORAGE_PROOF_INDEX),
                p.get(CFG_MANIFEST_PREIMAGE_INDEX).asBytes(),
                provenAccount.storageRoot32(),
                serviceAddress20);
    }

    // -----------------------------------------------------------------------------------
    // Top-level decoders
    // -----------------------------------------------------------------------------------

    /**
     * Decodes an RLP list of opaque byte strings into a {@code byte[][]} for MPT consumption.
     */
    @NonNull
    private static byte[][] decodeNodeList(@NonNull final Rlp.Item item, @NonNull final String name) {
        if (!item.isList()) {
            throw ProofException.besuQbft(name + " is not an RLP list");
        }
        final List<Rlp.Item> children = item.children();
        final byte[][] out = new byte[children.size()][];
        for (int i = 0; i < children.size(); i++) {
            out[i] = children.get(i).asBytes();
        }
        return out;
    }

    /**
     * Verify a bundle's endpoint-manifest advance (spec §4.9 / §2.4.2). Mirrors the peer ClprService's
     * on-chain commitment: prove the single commitment slot (18)
     * against the authenticated account {@code storageRoot32}, bind the supplied protobuf preimage to it
     * ({@code keccak256(preimage) == provenCommitment}), then require {@code version >= 1} and (when the
     * trusted peer service address is known) {@code service_address} equality. An empty endpoint list is
     * permitted. Returns the verified preimage bytes to thread back to the CLPR Service for Step 1b.
     */
    private static byte[] verifyEndpointManifestProof(
            @NonNull final Rlp.Item manifestStorageProofItem,
            @NonNull final byte[] manifestPreimage,
            @NonNull final byte[] storageRoot32,
            @NonNull final byte[] expectedServiceAddress20) {
        final List<StorageProofEntry> entries = decodeStorageProofList(manifestStorageProofItem);
        if (entries.size() != 1) {
            throw ProofException.besuQbft(
                    "endpoint-manifest storage proof must contain exactly 1 entry, got " + entries.size());
        }
        final StorageProofEntry entry = entries.get(0);
        final byte[] slot32 = leftPad32(entry.key(), "endpointManifestProof.key");
        if (!Arrays.equals(slot32, endpointManifestCommitmentSlot())) {
            throw ProofException.besuQbft("endpoint-manifest storage proof is not for the commitment slot (18)");
        }
        final byte[] provenCommitment = Mpt.get(storageRoot32, keccak256(slot32), entry.proofNodes())
                .map(Rlp::decodeTrieStorageValueAsBytes32)
                .orElseThrow(() ->
                        ProofException.besuQbft("endpoint-manifest commitment slot absent from the storage trie"));
        if (!Arrays.equals(keccak256(manifestPreimage), provenCommitment)) {
            throw ProofException.besuQbft("endpoint-manifest preimage does not match the proven commitment");
        }
        final ClprEndpointManifest manifest;
        try {
            manifest = ClprEndpointManifest.PROTOBUF.parseStrict(Bytes.wrap(manifestPreimage));
        } catch (final Exception e) {
            throw ProofException.besuQbft(
                    "endpoint-manifest preimage is not a valid ClprEndpointManifest: " + e.getMessage());
        }
        if (manifest.version() == 0) {
            throw ProofException.besuQbft("endpoint-manifest version is 0");
        }
        if (expectedServiceAddress20.length > 0
                && !Arrays.equals(manifest.serviceAddress().toByteArray(), expectedServiceAddress20)) {
            throw ProofException.besuQbft(
                    "endpoint-manifest service_address does not match the trusted peer service address");
        }
        return manifestPreimage.clone();
    }

    @NonNull
    private static List<StorageProofEntry> decodeStorageProofList(@NonNull final Rlp.Item item) {
        if (!item.isList()) {
            throw ProofException.besuQbft("storageProof is not an RLP list");
        }
        final List<Rlp.Item> entries = item.children();
        final List<StorageProofEntry> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            final Rlp.Item entry = entries.get(i);
            if (!entry.isList() || entry.children().size() != 2) {
                throw ProofException.besuQbft("storageProof[" + i + "] is not a [key, proof[]] RLP list");
            }
            out.add(new StorageProofEntry(
                    entry.children().get(0).asBytes(),
                    decodeNodeList(entry.children().get(1), "storageProof[" + i + "].proof")));
        }
        return List.copyOf(out);
    }

    // -----------------------------------------------------------------------------------
    // QBFT trust-anchor verification
    // -----------------------------------------------------------------------------------

    /**
     * Encodes a validator set as a canonically-sorted RLP list of 20-byte addresses.
     * Sorting is lexicographic (unsigned byte comparison) so the encoding is order-independent:
     * two sets with the same members always produce identical bytes regardless of input order.
     */
    public static byte[] encodeValidatorSet(@NonNull final List<byte[]> validators) {
        final List<byte[]> sorted = new ArrayList<>(validators);
        sorted.sort(Arrays::compareUnsigned);
        final List<byte[]> encoded = new ArrayList<>(sorted.size());
        for (final byte[] addr : sorted) {
            encoded.add(Rlp.encodeBytes(addr));
        }
        return Rlp.encodeList(encoded);
    }

    /**
     * Decodes a validator set previously encoded by {@link #encodeValidatorSet}.
     * Returns the addresses in the stored (sorted) order.
     *
     * @throws ProofException if {@code encoded} is not a non-empty RLP list
     * @throws IllegalArgumentException if any address is not exactly 20 bytes
     */
    static List<byte[]> decodeValidatorSet(@NonNull final byte[] encoded, @NonNull final String name) {
        final Rlp.Item item;
        try {
            item = Rlp.decodeOne(encoded);
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft(name + " is not valid RLP: " + e.getMessage());
        }
        if (!item.isList()) {
            throw ProofException.besuQbft(name + " is not an RLP list");
        }
        final List<Rlp.Item> children = item.children();
        if (children.isEmpty()) {
            throw ProofException.besuQbft(name + " contains no validators");
        }
        final List<byte[]> result = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            result.add(checkedCopy(children.get(i).asBytes(), ADDRESS_LENGTH, name + "[" + i + "]"));
        }
        return result;
    }

    /**
     * Extracts all validator addresses from a block header's QBFT extra-data validators list.
     * Returns the addresses sorted canonically (same order as {@link #encodeValidatorSet}).
     *
     * @throws ProofException if the validators list is absent or empty
     * @throws IllegalArgumentException if any address is not exactly 20 bytes
     */
    static List<byte[]> extractValidators(@NonNull final Rlp.Item headerItem) {
        final byte[] extraDataRlp =
                headerItem.children().get(HEADER_EXTRA_DATA_INDEX).asBytes();
        final Rlp.Item extra;
        try {
            extra = Rlp.decodeOne(extraDataRlp);
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("blockHeader.extraData is not valid RLP: " + e.getMessage());
        }
        if (!extra.isList() || extra.children().size() != QBFT_EXTRA_FIELDS) {
            throw ProofException.besuQbft(
                    "blockHeader.extraData is not a QBFT extra-data RLP list of " + QBFT_EXTRA_FIELDS + " fields");
        }
        final Rlp.Item validatorsItem = extra.children().get(QBFT_EXTRA_INDEX_VALIDATORS);
        if (!validatorsItem.isList()) {
            throw ProofException.besuQbft("blockHeader.extraData.validators is not an RLP list");
        }
        final List<Rlp.Item> validatorItems = validatorsItem.children();
        if (validatorItems.isEmpty()) {
            throw ProofException.besuQbft("blockHeader.extraData.validators list is empty");
        }
        final List<byte[]> validators = new ArrayList<>(validatorItems.size());
        for (int i = 0; i < validatorItems.size(); i++) {
            validators.add(checkedCopy(validatorItems.get(i).asBytes(), ADDRESS_LENGTH, "validator[" + i + "]"));
        }
        validators.sort(Arrays::compareUnsigned);
        return validators;
    }

    /**
     * Walks the epoch-header chain: for each header, verifies the QBFT committed seals against the
     * current validator set, then advances to the new validator set declared in that header's
     * extra-data. Returns the RLP-encoded new validator set after the last epoch header.
     */
    @NonNull
    private static byte[] processEpochHeaders(
            @NonNull final Rlp.Item epochHeadersItem, @NonNull final byte[] currentTrustAnchor) {
        List<byte[]> validatorSet = decodeValidatorSet(currentTrustAnchor, "trustAnchor");
        final List<Rlp.Item> epochHeaders = epochHeadersItem.children();
        long prevBlockNumber = -1L;
        for (int i = 0; i < epochHeaders.size(); i++) {
            final Rlp.Item epochHeader = epochHeaders.get(i);
            if (!epochHeader.isList()
                    || epochHeader.children().size() < MIN_HEADER_FIELDS
                    || epochHeader.children().size() > MAX_HEADER_FIELDS) {
                throw ProofException.besuQbft("epochBlockHeaders[" + i + "] is not an RLP list of " + MIN_HEADER_FIELDS
                        + ".." + MAX_HEADER_FIELDS + " fields");
            }
            // Enforce monotonically increasing block numbers to prevent replay/reorder attacks.
            final byte[] blockNumBytes =
                    epochHeader.children().get(HEADER_BLOCK_NUMBER_INDEX).asBytes();
            if (blockNumBytes.length > 8) {
                throw ProofException.besuQbft("epochBlockHeaders[" + i + "] block number exceeds 8 bytes");
            }
            final byte[] padded = new byte[8];
            System.arraycopy(
                    blockNumBytes,
                    blockNumBytes.length - Math.min(blockNumBytes.length, 8),
                    padded,
                    8 - Math.min(blockNumBytes.length, 8),
                    Math.min(blockNumBytes.length, 8));
            final long blockNumber = java.nio.ByteBuffer.wrap(padded).getLong();
            if (blockNumber <= prevBlockNumber) {
                throw ProofException.besuQbft("epochBlockHeaders[" + i + "] block number " + blockNumber
                        + " is not strictly greater than previous " + prevBlockNumber);
            }
            prevBlockNumber = blockNumber;
            log.debug(
                    "processEpochHeaders[{}]: blockNumber={}, verifying seal against {} validators",
                    i,
                    blockNumber,
                    validatorSet.size());
            verifyQbftSealAgainstValidatorSet(epochHeader, validatorSet);
            validatorSet = extractValidators(epochHeader);
        }
        return encodeValidatorSet(validatorSet);
    }

    /**
     * Computes the epoch number for an epoch-boundary block header and encodes it as
     * {@code BigInteger.valueOf(n).toByteArray()}.
     */
    @NonNull
    private static byte[] epochNumberBytesFromHeader(@NonNull final Rlp.Item lastEpochHeader, final long epochLength) {
        final byte[] blockNumberBytes =
                lastEpochHeader.children().get(HEADER_BLOCK_NUMBER_INDEX).asBytes();
        if (blockNumberBytes.length > 8) {
            throw ProofException.besuQbft(
                    "epoch header block number exceeds 8 bytes (" + blockNumberBytes.length + ")");
        }
        final byte[] padded = new byte[8];
        final int len = Math.min(blockNumberBytes.length, 8);
        System.arraycopy(blockNumberBytes, blockNumberBytes.length - len, padded, 8 - len, len);
        final long blockNumber = java.nio.ByteBuffer.wrap(padded).getLong();
        final long epochNumber = blockNumber / epochLength;
        return java.math.BigInteger.valueOf(epochNumber).toByteArray();
    }

    /**
     * Returns the {@code service_address} field of the already-parsed {@link ClprLedgerConfiguration}
     * as a 20-byte array. Used to derive the account-proof lookup key.
     */
    private static byte[] extractServiceAddress(@NonNull final ClprLedgerConfiguration config) {
        try {
            final var addr = config.serviceAddress();
            if (addr.length() != ADDRESS_LENGTH) {
                throw ProofException.besuQbft("ClprLedgerConfiguration.service_address must be " + ADDRESS_LENGTH
                        + " bytes, got " + addr.length());
            }
            return addr.toByteArray();
        } catch (final ProofException e) {
            throw e;
        } catch (final Exception e) {
            throw ProofException.besuQbft("cannot read service_address from ledger configuration: " + e.getMessage());
        }
    }

    /**
     * Verifies that the header's QBFT committed seals satisfy the quorum requirement for
     * {@code validatorSet}.
     *
     * <p>Quorum = {@code (2 * N) / 3 + 1} (integer division), where N = validatorSet.size().
     * Every recovered signer must be a member of the validator set, and no signer may appear
     * more than once. Processing uses sorted lists throughout — no {@link java.util.Set} — so
     * behaviour is deterministic regardless of JVM.
     *
     * @throws ProofException if seals are absent, malformed, below quorum, contain an unknown
     *     signer, or contain a duplicate signer
     */
    static void verifyQbftSealAgainstValidatorSet(
            @NonNull final Rlp.Item headerItem, @NonNull final List<byte[]> validatorSet) {
        if (validatorSet.isEmpty()) {
            throw ProofException.besuQbft("validator set is empty");
        }
        final List<byte[]> sortedValidatorSet = new ArrayList<>(validatorSet);
        sortedValidatorSet.sort(Arrays::compareUnsigned);
        final int n = sortedValidatorSet.size();
        final int quorum = (2 * n) / 3 + 1;

        // 1. Pull extraData out of the block header.
        final byte[] extraDataRlp =
                headerItem.children().get(HEADER_EXTRA_DATA_INDEX).asBytes();
        final Rlp.Item extra;
        try {
            extra = Rlp.decodeOne(extraDataRlp);
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("blockHeader.extraData is not valid RLP: " + e.getMessage());
        }
        if (!extra.isList() || extra.children().size() != QBFT_EXTRA_FIELDS) {
            throw ProofException.besuQbft(
                    "blockHeader.extraData is not a QBFT extra-data RLP list of " + QBFT_EXTRA_FIELDS + " fields");
        }
        final Rlp.Item committedSealsItem = extra.children().get(QBFT_EXTRA_INDEX_COMMITTED_SEALS);
        if (!committedSealsItem.isList()) {
            throw ProofException.besuQbft("blockHeader.extraData.committedSeals is not an RLP list");
        }
        final List<Rlp.Item> committedSeals = committedSealsItem.children();
        if (committedSeals.size() < quorum) {
            throw ProofException.besuQbft("expected at least " + quorum + " committed seal" + (quorum > 1 ? "s" : "")
                    + " for " + n + "-validator set, got " + committedSeals.size());
        }

        final byte[] commitSealMessageHash = buildCommitSealHash(headerItem, extra);

        // 2. ecRecover every seal; collect recovered addresses into a list.
        final List<byte[]> recoveredSigners = new ArrayList<>(committedSeals.size());
        for (int i = 0; i < committedSeals.size(); i++) {
            final byte[] seal = committedSeals.get(i).asBytes();
            if (seal.length != QBFT_SEAL_LENGTH) {
                throw ProofException.besuQbft("committedSeals[" + i + "] must be " + QBFT_SEAL_LENGTH
                        + " bytes (r||s||v), got " + seal.length);
            }
            recoveredSigners.add(recoverEthereumAddress(commitSealMessageHash, seal));
        }

        // 3. Sort recovered signers for deterministic duplicate detection and membership lookup.
        recoveredSigners.sort(Arrays::compareUnsigned);

        // 4. Walk sorted list: detect adjacent duplicates; verify each signer is in the validator set.
        for (int i = 0; i < recoveredSigners.size(); i++) {
            final byte[] signer = recoveredSigners.get(i);
            if (i > 0 && Arrays.equals(signer, recoveredSigners.get(i - 1))) {
                throw ProofException.besuQbft("duplicate committed seal from 0x" + HEX.formatHex(signer));
            }
            if (Collections.binarySearch(sortedValidatorSet, signer, Arrays::compareUnsigned) < 0) {
                throw ProofException.besuQbft(
                        "committed seal recovers to 0x" + HEX.formatHex(signer) + " which is not in the validator set");
            }
        }

        log.debug(
                "BesuQbftProofVerifier.verifyQbftSealAgainstValidatorSet: verified {}/{} seals (quorum={})",
                recoveredSigners.size(),
                n,
                quorum);
    }

    /**
     * Compute the commit-seal message hash that QBFT validators sign.
     *
     * <p>Re-encodes the header with committedSeals stripped (but round preserved), then returns
     * keccak256 of the result. Both the single-node and multi-node verifiers use this identical
     * hash construction — port of Besu 26.5.0's
     * {@code BftExtraDataCodec.encodeWithoutCommitSeals} / {@code BftBlockHashing}.
     */
    static byte[] buildCommitSealHash(@NonNull final Rlp.Item headerItem, @NonNull final Rlp.Item extra) {
        // Re-encode extraData with committedSeals=[] but PRESERVE the original round.
        final List<byte[]> sealingExtraFields = new ArrayList<>(QBFT_EXTRA_FIELDS);
        sealingExtraFields.add(extra.children().get(0).rawBytes()); // vanity
        sealingExtraFields.add(extra.children().get(1).rawBytes()); // validators[]
        sealingExtraFields.add(extra.children().get(2).rawBytes()); // vote
        sealingExtraFields.add(extra.children().get(3).rawBytes()); // round — preserve original
        sealingExtraFields.add(Rlp.encodeList(List.of())); // committedSeals = []
        final byte[] sealingExtraData = Rlp.encodeList(sealingExtraFields);

        // Re-encode the full header, swapping in the stripped extraData.
        final List<Rlp.Item> headerChildren = headerItem.children();
        final List<byte[]> sealingHeaderFields = new ArrayList<>(headerChildren.size());
        for (int i = 0; i < headerChildren.size(); i++) {
            sealingHeaderFields.add(
                    i == HEADER_EXTRA_DATA_INDEX
                            ? Rlp.encodeBytes(sealingExtraData)
                            : headerChildren.get(i).rawBytes());
        }
        return keccak256(Rlp.encodeList(sealingHeaderFields));
    }

    /**
     * Recover the 20-byte Ethereum address that signed {@code msgHash32} with the supplied
     * 65-byte {@code r||s||v} signature.
     */
    @NonNull
    static byte[] recoverEthereumAddress(@NonNull final byte[] msgHash32, @NonNull final byte[] seal65) {
        if (msgHash32.length != 32) {
            throw new IllegalArgumentException("msgHash must be 32 bytes");
        }
        if (seal65.length != QBFT_SEAL_LENGTH) {
            throw new IllegalArgumentException("seal must be " + QBFT_SEAL_LENGTH + " bytes");
        }
        int v = seal65[64] & 0xFF;
        if (v == 27 || v == 28) {
            v -= 27;
        }
        if (v != 0 && v != 1) {
            throw ProofException.besuQbft("committed seal v byte must be 0/1 (or 27/28), got " + (seal65[64] & 0xFF));
        }
        final byte[] compact = Arrays.copyOf(seal65, 64);
        final var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        if (LibSecp256k1.secp256k1_ecdsa_recoverable_signature_parse_compact(
                        LibSecp256k1.CONTEXT, recoverableSig, compact, v)
                != 1) {
            throw ProofException.besuQbft("failed to parse committed seal signature");
        }
        final var pubKey = new LibSecp256k1.secp256k1_pubkey();
        if (LibSecp256k1.secp256k1_ecdsa_recover(LibSecp256k1.CONTEXT, pubKey, recoverableSig, msgHash32) != 1) {
            throw ProofException.besuQbft("ecRecover failed for committed seal");
        }
        final ByteBuffer serializedPubKey = ByteBuffer.allocate(65);
        final var serializedPubKeySize = new LongByReference(serializedPubKey.capacity());
        if (LibSecp256k1.secp256k1_ec_pubkey_serialize(
                        LibSecp256k1.CONTEXT,
                        serializedPubKey,
                        serializedPubKeySize,
                        pubKey,
                        LibSecp256k1.SECP256K1_EC_UNCOMPRESSED)
                != 1) {
            throw ProofException.besuQbft("failed to serialize recovered public key");
        }
        final byte[] addr = EthSigsUtils.recoverAddressFromPubKey(serializedPubKey.array());
        if (addr == null || addr.length != ADDRESS_LENGTH) {
            throw ProofException.besuQbft("recovered address has wrong length");
        }
        return addr;
    }

    // -----------------------------------------------------------------------------------
    // Queue metadata decoding
    // -----------------------------------------------------------------------------------

    /**
     * Reorders the (key-sorted) proven storage slots into the positional layout
     * {@link #decodeQueueMetadata} expects. The relay proves the 5 Channel-struct slots
     * {@code keccak(connId,15)+{1,2,4,5,16}} — which cluster within a 16-wide window (min..max span
     * {@value #CHANNEL_SLOT_CLUSTER_SPAN}) — plus, for a message bundle, the last-message running-hash
     * slot, a far keccak outlier. This verifier has no {@code channelId} to recompute the exact
     * slot keys, so it identifies the cluster as the 5-slot window whose span equals
     * {@value #CHANNEL_SLOT_CLUSTER_SPAN} and treats the leftover entry as the message running hash.
     *
     * <p>Within the ascending cluster the fields sit at fixed positions: 0 = {@code status|nextMsgId},
     * 1 = {@code acked|received|reply}, 2 = {@code sentRunningHash}, 3 = {@code receivedRunningHash},
     * 4 = {@code endpointManifestVersion} (not part of the queue metadata — dropped).
     *
     * @return a 4-slot array (ACK-only) or 5-slot array (with the message running hash appended)
     */
    @NonNull
    private static byte[][] reorderQueueSlots(
            @NonNull final byte[][] slotKeys, @NonNull final byte[][] provenSlotValues) {
        final int n = slotKeys.length; // CHANNEL_STORAGE_SLOTS (ACK-only) or +1 (with a message)
        final boolean hasMessage = n == CHANNEL_STORAGE_SLOTS + 1;
        // Locate the cluster start. With no outlier (ACK-only) it starts at 0; otherwise the outlier
        // is either the first or the last sorted entry, so exactly one of the two candidate 5-windows
        // has the cluster span.
        int clusterStart = 0;
        if (hasMessage) {
            final BigInteger spanFromZero =
                    new BigInteger(1, slotKeys[CHANNEL_STORAGE_SLOTS - 1]).subtract(new BigInteger(1, slotKeys[0]));
            clusterStart = spanFromZero.equals(BigInteger.valueOf(CHANNEL_SLOT_CLUSTER_SPAN)) ? 0 : 1;
            final BigInteger clusterSpan = new BigInteger(1, slotKeys[clusterStart + CHANNEL_STORAGE_SLOTS - 1])
                    .subtract(new BigInteger(1, slotKeys[clusterStart]));
            if (!clusterSpan.equals(BigInteger.valueOf(CHANNEL_SLOT_CLUSTER_SPAN))) {
                throw ProofException.besuQbft(
                        "could not locate the Channel storage-slot cluster (span " + clusterSpan + ")");
            }
        }
        final byte[][] out = new byte[hasMessage ? STORAGE_PROOF_MAX_ENTRIES : STORAGE_PROOF_MIN_ENTRIES][];
        out[SP_INDEX_CHANNEL_STATUS_NEXTMSGID] = provenSlotValues[clusterStart];
        out[SP_INDEX_CHANNEL_RECEIVED_MSG_ID] = provenSlotValues[clusterStart + 1];
        out[SP_INDEX_CHANNEL_SENT_RUNNING_HASH] = provenSlotValues[clusterStart + 2];
        out[SP_INDEX_CHANNEL_RECEIVED_RUNNING_HASH] = provenSlotValues[clusterStart + 3];
        // clusterStart+4 is endpointManifestVersion — intentionally not copied.
        if (hasMessage) {
            final int msgHashIdx = clusterStart == 0 ? CHANNEL_STORAGE_SLOTS : 0; // the entry outside the cluster
            out[SP_INDEX_LAST_MSG_RUNNING_HASH] = provenSlotValues[msgHashIdx];
        }
        return out;
    }

    /**
     * Reorders the proven storage slots into the queue-metadata positional layout by <em>exact</em>
     * slot-key matching against the canonical SC-189 Channel storage keys derived from
     * {@code channelId32} — superseding the connId-less span heuristic in {@link #reorderQueueSlots}.
     * The {@code _channels} mapping base is {@code keccak256(channelId(32) || uint256(15))}; the
     * four queue-metadata fields sit at offsets {@code {1,2,4,5}}, {@code endpointManifestVersion} at
     * {@code 16} (dropped). Any proven key matching none of the five is taken to be the last message's
     * running-hash slot; a second such leftover, or a missing required queue slot, is a hard error
     * (fail-safe — the verifier never silently mis-decodes).
     *
     * @return a 4-slot array (ACK-only) or 5-slot array (with the message running hash appended)
     */
    @NonNull
    private static byte[][] matchQueueSlotsByConnId(
            @NonNull final byte[][] slotKeys,
            @NonNull final byte[][] provenSlotValues,
            @NonNull final byte[] channelId32) {
        final int n = slotKeys.length; // CHANNEL_STORAGE_SLOTS (ACK-only) or +1 (with a message)
        final boolean hasMessage = n == CHANNEL_STORAGE_SLOTS + 1;
        final byte[] base = keccak256(concat(channelId32, uint256Bytes(CHANNELS_MAPPING_SLOT)));
        final byte[] kStatus = addToBytes32(base, CHANNEL_OFFSET_STATUS_NEXTMSGID);
        final byte[] kReceived = addToBytes32(base, CHANNEL_OFFSET_ACKED_RECEIVED);
        final byte[] kSentHash = addToBytes32(base, CHANNEL_OFFSET_SENT_RUNNING_HASH);
        final byte[] kRecvHash = addToBytes32(base, CHANNEL_OFFSET_RECEIVED_RUNNING_HASH);
        final byte[] kManifestVer = addToBytes32(base, CHANNEL_OFFSET_ENDPOINT_MANIFEST_VERSION);

        final byte[][] out = new byte[hasMessage ? STORAGE_PROOF_MAX_ENTRIES : STORAGE_PROOF_MIN_ENTRIES][];
        int messageHashIdx = -1;
        for (int i = 0; i < n; i++) {
            final byte[] key = slotKeys[i];
            if (Arrays.equals(key, kStatus)) {
                out[SP_INDEX_CHANNEL_STATUS_NEXTMSGID] = provenSlotValues[i];
            } else if (Arrays.equals(key, kReceived)) {
                out[SP_INDEX_CHANNEL_RECEIVED_MSG_ID] = provenSlotValues[i];
            } else if (Arrays.equals(key, kSentHash)) {
                out[SP_INDEX_CHANNEL_SENT_RUNNING_HASH] = provenSlotValues[i];
            } else if (Arrays.equals(key, kRecvHash)) {
                out[SP_INDEX_CHANNEL_RECEIVED_RUNNING_HASH] = provenSlotValues[i];
            } else if (Arrays.equals(key, kManifestVer)) {
                // endpointManifestVersion — proven but not part of the queue metadata; dropped.
                continue;
            } else if (messageHashIdx < 0) {
                messageHashIdx = i; // candidate last-message running-hash slot
            } else {
                throw ProofException.besuQbft("bundle proves an unexpected storage slot 0x" + HEX.formatHex(key)
                        + " for channelId 0x" + HEX.formatHex(channelId32));
            }
        }
        if (out[SP_INDEX_CHANNEL_STATUS_NEXTMSGID] == null
                || out[SP_INDEX_CHANNEL_RECEIVED_MSG_ID] == null
                || out[SP_INDEX_CHANNEL_SENT_RUNNING_HASH] == null
                || out[SP_INDEX_CHANNEL_RECEIVED_RUNNING_HASH] == null) {
            throw ProofException.besuQbft(
                    "bundle storage proof is missing a required Channel queue-metadata slot for channelId 0x"
                            + HEX.formatHex(channelId32));
        }
        if (hasMessage) {
            if (messageHashIdx < 0) {
                throw ProofException.besuQbft(
                        "6-slot bundle has no message running-hash slot outside the Channel cluster for channelId 0x"
                                + HEX.formatHex(channelId32));
            }
            out[SP_INDEX_LAST_MSG_RUNNING_HASH] = provenSlotValues[messageHashIdx];
        } else if (messageHashIdx >= 0) {
            throw ProofException.besuQbft("5-slot bundle proves a non-Channel storage slot 0x"
                    + HEX.formatHex(slotKeys[messageHashIdx]) + " for channelId 0x"
                    + HEX.formatHex(channelId32));
        }
        return out;
    }

    /** Concatenates two byte arrays. */
    private static byte[] concat(@NonNull final byte[] a, @NonNull final byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 32-byte big-endian encoding of a small non-negative {@code value} (&le; 255). */
    private static byte[] uint256Bytes(final int value) {
        if (value < 0 || value > 0xff) {
            throw new IllegalArgumentException("uint256Bytes only supports 0..255, got " + value);
        }
        final byte[] out = new byte[32];
        out[31] = (byte) value;
        return out;
    }

    /** Returns {@code base32 + offset} as a new 32-byte big-endian value. Throws on overflow. */
    private static byte[] addToBytes32(@NonNull final byte[] base32, final int offset) {
        final byte[] out = base32.clone();
        int carry = offset;
        for (int i = 31; i >= 0 && carry != 0; i--) {
            final int sum = (out[i] & 0xff) + (carry & 0xff);
            out[i] = (byte) (sum & 0xff);
            carry = (carry >>> 8) + (sum >>> 8);
        }
        if (carry != 0) {
            throw new IllegalStateException("bytes32 overflow adding offset " + offset);
        }
        return out;
    }

    /**
     * Decode a {@link QueueMetadata} from the 4 or 5 storage-slot values produced by
     * {@link #reorderQueueSlots}, in this positional order:
     * <ol>
     *   <li>index 0 — packed slot: {@code verifier(20) | status(1) | nextMessageId(8)}.</li>
     *   <li>index 1 — packed slot: {@code ackedMessageId(8) | receivedMessageId(8) | nextExpectedReplyId(8)}.</li>
     *   <li>index 2 — {@code sentRunningHash} (bytes32, full slot).</li>
     *   <li>index 3 — {@code receivedRunningHash} (bytes32, full slot).</li>
     *   <li>index 4 — last queued message's {@code runningHashAfterProcessing} (bytes32, full slot).
     *       <strong>Optional</strong> — absent in ACK-only bundles.</li>
     * </ol>
     *
     * <p>Solidity packs primitive fields starting at the LSB of a slot, so on a 32-byte
     * big-endian storage word the first-declared field sits at the right (high index of the
     * byte array). The unpacks below mirror that exactly.
     */
    @NonNull
    static QueueMetadata decodeQueueMetadata(@NonNull final byte[][] provenSlotValues) {
        Objects.requireNonNull(provenSlotValues, "provenSlotValues");
        if (provenSlotValues.length < STORAGE_PROOF_MIN_ENTRIES
                || provenSlotValues.length > STORAGE_PROOF_MAX_ENTRIES) {
            throw ProofException.besuQbft("expected 4 or 5 proven slot values, got " + provenSlotValues.length);
        }

        // Slot 0: verifier(20) | status(1) | nextMessageId(8) — first declared at LSB.
        // Byte layout (MSB→LSB): 3B padding | 8B nextMessageId | 1B status | 20B verifier.
        final byte[] statusSlot =
                checkedCopy(provenSlotValues[SP_INDEX_CHANNEL_STATUS_NEXTMSGID], 32, "connStatusNextMsgIdSlot");
        final long nextMessageId = readUint64BigEndian(statusSlot, 3);
        final int status = statusSlot[11] & 0xFF;

        // Slot 1: ackedMessageId(8) | receivedMessageId(8) | nextExpectedReplyId(8) — first at LSB.
        // Byte layout (MSB→LSB): 8B padding | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId.
        final byte[] receivedIdSlot =
                checkedCopy(provenSlotValues[SP_INDEX_CHANNEL_RECEIVED_MSG_ID], 32, "connReceivedMsgIdSlot");
        final long receivedMessageId = readUint64BigEndian(receivedIdSlot, 16);

        final byte[] sentRunningHash =
                checkedCopy(provenSlotValues[SP_INDEX_CHANNEL_SENT_RUNNING_HASH], 32, "sentRunningHash");
        final byte[] receivedRunningHash =
                checkedCopy(provenSlotValues[SP_INDEX_CHANNEL_RECEIVED_RUNNING_HASH], 32, "receivedRunningHash");

        // Slot 4 is optional — absent in ACK-only bundles (no queued messages).
        final byte[] lastMsgRunningHash = provenSlotValues.length > SP_INDEX_LAST_MSG_RUNNING_HASH
                ? checkedCopy(provenSlotValues[SP_INDEX_LAST_MSG_RUNNING_HASH], 32, "lastMessageRunningHash")
                : new byte[32];

        return new QueueMetadata(
                nextMessageId, sentRunningHash, receivedMessageId, receivedRunningHash, status, lastMsgRunningHash);
    }

    /** Reads 8 bytes from {@code buf} starting at {@code offset} as a big-endian unsigned long. */
    private static long readUint64BigEndian(final byte[] buf, final int offset) {
        return ByteBuffer.wrap(buf, offset, 8).getLong();
    }

    // -----------------------------------------------------------------------------------
    // Public types
    // -----------------------------------------------------------------------------------

    /**
     * Result of a successful {@link #verifyBundle} call.
     *
     * @param blockHash32 the keccak256 hash of the proof's block header
     * @param bundleContentBytes the verbatim protobuf-serialized {@code ClprBundleContent} bytes
     * @param queueMetadata the {@code ClprQueueMetadata} fields decoded from the storage slots
     * @param newTrustAnchor the RLP-encoded validator set extracted from the last epoch header
     *     processed (via {@link #encodeValidatorSet}), or {@code byte[0]} if no epoch headers
     *     were present
     * @param newTrustAnchorId {@code BigInteger.valueOf(epochNumber).toByteArray()} for the
     *     last processed epoch header, or {@code byte[0]} if no epoch headers were present
     */
    public record VerifiedBundle(
            @NonNull byte[] blockHash32,
            @NonNull byte[] bundleContentBytes,
            @NonNull QueueMetadata queueMetadata,
            @NonNull byte[] newTrustAnchor,
            @NonNull byte[] newTrustAnchorId,
            @NonNull byte[] newEndpointManifestBytes) {
        public VerifiedBundle {
            blockHash32 = checkedCopy(blockHash32, 32, "blockHash32");
            bundleContentBytes = Objects.requireNonNull(bundleContentBytes, "bundleContentBytes")
                    .clone();
            Objects.requireNonNull(queueMetadata, "queueMetadata");
            Objects.requireNonNull(newTrustAnchor, "newTrustAnchor");
            newTrustAnchor = newTrustAnchor.clone();
            Objects.requireNonNull(newTrustAnchorId, "newTrustAnchorId");
            newTrustAnchorId = newTrustAnchorId.clone();
            // Empty (length 0) means the bundle carried no endpoint-manifest advance.
            newEndpointManifestBytes = Objects.requireNonNull(newEndpointManifestBytes, "newEndpointManifestBytes")
                    .clone();
        }

        @Override
        public byte[] blockHash32() {
            return blockHash32.clone();
        }

        @Override
        public byte[] newEndpointManifestBytes() {
            return newEndpointManifestBytes.clone();
        }

        @Override
        public byte[] bundleContentBytes() {
            return bundleContentBytes.clone();
        }

        @Override
        public byte[] newTrustAnchor() {
            return newTrustAnchor.clone();
        }

        @Override
        public byte[] newTrustAnchorId() {
            return newTrustAnchorId.clone();
        }
    }

    /**
     * The {@code ClprQueueMetadata} fields proven by the bundle's storage proofs. Values mirror
     * the proto / Solidity field names exactly so callers can plug them into
     * {@code ClprQueueMetadata.newBuilder()} without further translation.
     *
     * @param nextMessageId outgoing-message counter, decoded from the packed Channel slot
     * @param sentRunningHash the cumulative outbound running hash (bytes32)
     * @param receivedMessageId highest inbound message id seen, decoded from the packed slot
     * @param receivedRunningHash the cumulative inbound running hash (bytes32)
     * @param status the {@code ClprChannelStatus} enum ordinal (0=PENDING,…,5=CLOSED)
     * @param lastMessageRunningHash the {@code runningHashAfterProcessing} of the last queued
     *     outbound message — proven alongside the queue metadata; carried here so callers can
     *     cross-check it against the bundle's last {@code ClprMessageValue}
     */
    public record QueueMetadata(
            long nextMessageId,
            @NonNull byte[] sentRunningHash,
            long receivedMessageId,
            @NonNull byte[] receivedRunningHash,
            int status,
            @NonNull byte[] lastMessageRunningHash) {
        public QueueMetadata {
            sentRunningHash = checkedCopy(sentRunningHash, 32, "sentRunningHash");
            receivedRunningHash = checkedCopy(receivedRunningHash, 32, "receivedRunningHash");
            lastMessageRunningHash = checkedCopy(lastMessageRunningHash, 32, "lastMessageRunningHash");
        }

        @Override
        public byte[] sentRunningHash() {
            return sentRunningHash.clone();
        }

        @Override
        public byte[] receivedRunningHash() {
            return receivedRunningHash.clone();
        }

        @Override
        public byte[] lastMessageRunningHash() {
            return lastMessageRunningHash.clone();
        }
    }

    /**
     * Result of a successful {@link #verifyConfigPayload} call.
     *
     * @param blockHash32 keccak256 hash of the proven current block header
     * @param ledgerConfiguration the proven {@link ClprLedgerConfiguration} parsed from the payload
     * @param endpointManifestBytes the verified protobuf {@code ClprEndpointManifest} preimage from the
     *     optional config-path manifest proof, or {@code byte[0]} when no manifest proof was supplied
     */
    public record VerifiedConfig(
            @NonNull byte[] blockHash32,
            @NonNull ClprLedgerConfiguration ledgerConfiguration,
            @NonNull byte[] endpointManifestBytes) {
        public VerifiedConfig {
            blockHash32 = checkedCopy(blockHash32, 32, "blockHash32");
            Objects.requireNonNull(ledgerConfiguration, "ledgerConfiguration");
            // Empty (length 0) means no config-path manifest proof was supplied.
            endpointManifestBytes = Objects.requireNonNull(endpointManifestBytes, "endpointManifestBytes")
                    .clone();
        }

        @Override
        public byte[] blockHash32() {
            return blockHash32.clone();
        }

        @Override
        public byte[] endpointManifestBytes() {
            return endpointManifestBytes.clone();
        }
    }

    /**
     * Verifier configuration.
     *
     * @param expectedContractAddress20 the CLPR service contract address on the peer ledger
     *     (20 bytes); required for account-proof verification
     * @param expectedContractCodeHash32 expected {@code codeHash} of the contract account
     *     (32 bytes); optional but strongly recommended
     * @param epochLength number of blocks per QBFT epoch; used to convert an epoch-header block
     *     number into the epoch number stored as {@code trustAnchorId}. Must be &gt; 0.
     */
    public record Config(
            @Nullable byte[] expectedContractAddress20,
            @Nullable byte[] expectedContractCodeHash32,
            long epochLength) {
        public Config {
            if (epochLength <= 0) {
                throw new IllegalArgumentException("epochLength must be > 0, got " + epochLength);
            }
            expectedContractAddress20 = expectedContractAddress20 == null
                    ? null
                    : checkedCopy(expectedContractAddress20, 20, "expectedContractAddress20");
            expectedContractCodeHash32 = expectedContractCodeHash32 == null
                    ? null
                    : checkedCopy(expectedContractCodeHash32, 32, "expectedContractCodeHash32");
        }

        @Override
        public @Nullable byte[] expectedContractAddress20() {
            return expectedContractAddress20 == null ? null : expectedContractAddress20.clone();
        }

        @Override
        public @Nullable byte[] expectedContractCodeHash32() {
            return expectedContractCodeHash32 == null ? null : expectedContractCodeHash32.clone();
        }
    }

    // -----------------------------------------------------------------------------------
    // Internal types
    // -----------------------------------------------------------------------------------

    private record StorageProofEntry(
            @NonNull byte[] key, @NonNull byte[][] proofNodes) {}

    private record Account(
            @NonNull byte[] storageRoot32, @NonNull byte[] codeHash32) {
        private Account {
            storageRoot32 = storageRoot32.clone();
            codeHash32 = codeHash32.clone();
        }

        static Account decode(@NonNull final byte[] accountRlp) {
            final Rlp.Item item = Rlp.decodeOne(accountRlp);
            if (!item.isList() || item.children().size() != 4) {
                throw ProofException.besuQbft("account value is not RLP [nonce, balance, storageRoot, codeHash]");
            }
            return new Account(
                    leftPad32(item.children().get(2).asBytes(), "account.storageRoot"),
                    leftPad32(item.children().get(3).asBytes(), "account.codeHash"));
        }

        @Override
        public byte[] storageRoot32() {
            return storageRoot32.clone();
        }

        @Override
        public byte[] codeHash32() {
            return codeHash32.clone();
        }
    }

    // -----------------------------------------------------------------------------------
    // Merkle Patricia Trie proof verification (Ethereum hex-prefix encoded MPT)
    // -----------------------------------------------------------------------------------

    private static final class Mpt {
        static Optional<byte[]> get(
                @NonNull final byte[] rootHash32, @NonNull final byte[] key32, @NonNull final byte[][] proofNodes) {
            checkedCopy(rootHash32, 32, "rootHash32");
            checkedCopy(key32, 32, "key32");
            if (proofNodes.length == 0 && Arrays.equals(rootHash32, EMPTY_TRIE_ROOT)) {
                return Optional.empty();
            }
            final Map<BytesKey, byte[]> nodesByHash = new HashMap<>();
            for (final byte[] node : proofNodes) {
                if (node.length == 0) {
                    throw ProofException.besuQbft("empty proof node");
                }
                nodesByHash.put(new BytesKey(keccak256(node)), node.clone());
            }

            byte[] nodeRef = rootHash32.clone();
            final int[] path = toNibbles(key32);
            int pathPos = 0;
            int guard = 0;
            while (true) {
                if (++guard > 128) {
                    throw ProofException.besuQbft("MPT traversal exceeded sanity limit");
                }
                if (nodeRef.length == 0) {
                    return Optional.empty();
                }

                final byte[] nodeRlp;
                if (nodeRef.length == 32) {
                    nodeRlp = nodesByHash.get(new BytesKey(nodeRef));
                    if (nodeRlp == null) {
                        throw ProofException.besuQbft("missing proof node for hash reference");
                    }
                } else if (nodeRef.length < 32) {
                    nodeRlp = nodeRef;
                } else {
                    throw ProofException.besuQbft("invalid MPT node reference length " + nodeRef.length);
                }

                final Rlp.Item node = Rlp.decodeOne(nodeRlp);
                if (!node.isList()) {
                    throw ProofException.besuQbft("MPT node is not an RLP list");
                }
                final List<Rlp.Item> items = node.children();
                if (items.size() == 17) {
                    if (pathPos == path.length) {
                        final Rlp.Item value = items.get(16);
                        if (value.isEmptyString()) {
                            return Optional.empty();
                        }
                        return Optional.of(value.asBytes());
                    }
                    final Rlp.Item child = items.get(path[pathPos]);
                    if (child.isEmptyString()) {
                        return Optional.empty();
                    }
                    nodeRef = child.asNodeReference();
                    pathPos++;
                } else if (items.size() == 2) {
                    final PathSegment segment = decodeHexPrefix(items.get(0).asBytes());
                    if (!startsWith(path, pathPos, segment.nibbles())) {
                        return Optional.empty();
                    }
                    pathPos += segment.nibbles().length;
                    if (segment.leaf()) {
                        if (pathPos != path.length) {
                            return Optional.empty();
                        }
                        return Optional.of(items.get(1).asBytes());
                    }
                    nodeRef = items.get(1).asNodeReference();
                } else {
                    throw ProofException.besuQbft("invalid MPT node length " + items.size());
                }
            }
        }

        private static int[] toNibbles(final byte[] bytes) {
            final int[] out = new int[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                out[i * 2] = (bytes[i] >>> 4) & 0x0f;
                out[i * 2 + 1] = bytes[i] & 0x0f;
            }
            return out;
        }

        private static PathSegment decodeHexPrefix(final byte[] encoded) {
            final int[] all = toNibbles(encoded);
            if (all.length == 0) {
                throw ProofException.besuQbft("empty hex-prefix path");
            }
            final int flags = all[0];
            final boolean leaf = (flags & 0x2) != 0;
            final boolean odd = (flags & 0x1) != 0;
            final int start = odd ? 1 : 2;
            if (!odd && all.length > 1 && all[1] != 0) {
                throw ProofException.besuQbft("invalid even hex-prefix path");
            }
            return new PathSegment(leaf, Arrays.copyOfRange(all, start, all.length));
        }

        private static boolean startsWith(final int[] path, final int offset, final int[] segment) {
            if (offset + segment.length > path.length) {
                return false;
            }
            for (int i = 0; i < segment.length; i++) {
                if (path[offset + i] != segment[i]) {
                    return false;
                }
            }
            return true;
        }

        private record PathSegment(boolean leaf, int[] nibbles) {}
    }

    record BytesKey(byte[] bytes) {
        BytesKey {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof BytesKey key && Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    // -----------------------------------------------------------------------------------
    // Byte / hex helpers
    // -----------------------------------------------------------------------------------

    private static byte[] keccak256(final byte[] input) {
        final KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        final byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }

    static byte[] checkedCopy(final byte[] bytes, final int len, final String name) {
        Objects.requireNonNull(bytes, name);
        if (bytes.length != len) {
            throw new IllegalArgumentException(name + " must be " + len + " bytes, got " + bytes.length);
        }
        return bytes.clone();
    }

    /**
     * Left-pads {@code value} to 32 bytes with leading zeros. Throws if {@code value} is longer
     * than 32 bytes.
     */
    private static byte[] leftPad32(final byte[] value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.length > 32) {
            throw ProofException.besuQbft(name + " is longer than 32 bytes (" + value.length + ")");
        }
        final byte[] out = new byte[32];
        System.arraycopy(value, 0, out, 32 - value.length, value.length);
        return out;
    }

    /** Parses a 64-character hex string into a 32-byte array. Used only for the constant {@link #EMPTY_TRIE_ROOT}. */
    private static byte[] hexToBytes32(final String hex) {
        if (hex.length() != 64) {
            throw new IllegalArgumentException("expected 64-character hex, got " + hex.length());
        }
        final byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            final int hi = Character.digit(hex.charAt(i * 2), 16);
            final int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex character in: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
