// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.endpointManifestCommitmentSlot;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprQbftLedgerConfigurationPayload;
import com.hedera.hapi.node.state.clpr.QbftContractModeValidatorSource;
import com.hedera.hapi.node.state.clpr.StorageProofEntry;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.crypto.SECPPublicKey;
import org.hyperledger.besu.crypto.SECPSignature;

/**
 * Verifies Besu QBFT bundle proofs against a peer ledger's execution state.
 *
 * <p>Accepts the RLP-encoded payload produced by
 * {@code org.hiero.clpr.relay.evm.QbftBundleConstructor.PayloadRlpEncoder} in the
 * {@code clpr-evm-endpoint} repo. Top-level layout (5 items, or 7 with the endpoint-manifest
 * extension at indices 5-6, verified when present):
 * <pre>
 *   index 0: currentBlockHeader    // RLP list of at least 15 Ethereum header fields
 *   index 1: validatorSource       // RLP [trustedSet, hops]; trustedSet empty or the anchor's set, hops = headers
 *   index 2: accountProof          // RLP list of MPT trie nodes
 *   index 3: storageProof          // RLP list of [key, proof[]] pairs; 5 or 6 entries (0 for a manifest-only bundle)
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
 *   offset 16: endpointManifestVersion (uint64)
 *   + (only for a bundle that ends before nextMessageId - 1) the running-hash slot of its last message
 *     (message-queue mapping); the system contract binds it to a message id ({@link #findLastMessageId})
 * </pre>
 * Every slot key is recomputed from the channel id ({@link #matchQueueSlotsByConnId}), so the proof
 * is bound to the channel being verified.
 *
 * <p>Verification steps:
 * <ol>
 *   <li>Walk the rotation hops (field 1, {@code [trustedSet, hops]}): each must come after the anchor block and the previous hop,
 *       change the trusted validator set by one member, and be sealed by a quorum of the set it lists;
 *       that set becomes the trusted set.</li>
 *   <li>Verify {@code currentBlockHeader}, which must come after the last hop (or the anchor block),
 *       against the final trusted set.</li>
 *   <li>Verify the account proof against {@code currentBlockHeader.stateRoot}; recover the contract
 *       account.</li>
 *   <li>For each storage-proof entry, verify against the proven account's {@code storageRoot}.</li>
 *   <li>Return the proven block hash, bundle-content bytes, queue metadata, and — when hops were
 *       crossed or the current block changed the set — the new validator set and its anchor id (the
 *       current block number).</li>
 * </ol>
 */
public final class BesuQbftVerifier {

    private static final Logger log = LogManager.getLogger(BesuQbftVerifier.class);
    private static final HexFormat HEX = HexFormat.of();

    /** Besu's secp256k1 implementation: native libsecp256k1 when available, pure Java otherwise. */
    private static final SECP256K1 SECP256K1_ALGORITHM = new SECP256K1();

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
     * New forks can append more fields, so only a minimum is enforced: every field the verifier reads
     * (up to {@code extraData}, index 12) is present. Reference:
     *   besu/ethereum/core/.../BlockHeader.java#writeTo @ tag 26.5.0.
     */
    private static final int MIN_HEADER_FIELDS = 15;

    /** Length in bytes of a trust anchor id: the anchor block number, big-endian. */
    private static final int TRUST_ANCHOR_ID_LENGTH = Long.BYTES;

    /** Items in the bundle's validator source (field 1): {@code [trustedSet, hops]}. */
    private static final int VALIDATOR_SOURCE_ITEMS = 2;

    /** Items in a contract-mode bundle's validator source (field 1): {@code [trustedSet, hops, contractProof]}. */
    private static final int CONTRACT_MODE_VALIDATOR_SOURCE_ITEMS = 3;

    /**
     * Most hops a contract-mode bundle may cross. Each hop costs a validator-array proof and up to a quorum of
     * signature recoveries; a relay that is further behind delivers the catch-up over several bundles (the relay
     * sends at most 5 by default).
     */
    static final int MAX_CONTRACT_MODE_HOPS = 32;

    /** Most members a contract-mode validator array may hold. */
    static final int MAX_CONTRACT_MODE_VALIDATORS = 256;

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

    private static final byte[] ZERO_ADDRESS = new byte[ADDRESS_LENGTH];

    // ── Storage-proof layout produced by QbftBundleConstructor (SC-189 Channel struct) ──
    // The relay proves 5 Channel-struct slots — keccak(connId,15)+{1,2,4,5,16} = status|nextMsgId,
    // acked|received|reply, sentRunningHash, receivedRunningHash, endpointManifestVersion — plus, when
    // the bundle carries messages, the running-hash slot of the last message. So a bundle proves 5
    // (the bundle ends at nextMessageId - 1, with or without messages) or 6 (the bundle stops earlier and
    // proves the running hash of its last message) storage slots.
    private static final int CHANNEL_STORAGE_SLOTS = 5;

    /** Length in bytes of a CLPR channel id. */
    private static final int CHANNEL_ID_LENGTH = 32;

    // SC-189 storage layout of the `_channels` mapping and the proven Channel-struct field
    // offsets, used for exact slot-key matching against the channel id
    // (base = keccak256(channelId(32) || uint256(CHANNELS_MAPPING_SLOT))).
    private static final int CHANNELS_MAPPING_SLOT = 15;
    // `_messageQueues` mapping base slot: message L's running hash sits at
    // keccak256(abi.encode(uint64(L), keccak256(abi.encode(channelId, 1)))) + 1.
    private static final int MESSAGE_QUEUES_MAPPING_SLOT = 1;
    private static final int MESSAGE_OFFSET_RUNNING_HASH = 1;
    private static final int CHANNEL_OFFSET_STATUS_NEXTMSGID = 1;
    private static final int CHANNEL_OFFSET_ACKED_RECEIVED = 2;
    private static final int CHANNEL_OFFSET_SENT_RUNNING_HASH = 4;
    private static final int CHANNEL_OFFSET_RECEIVED_RUNNING_HASH = 5;
    private static final int CHANNEL_OFFSET_ENDPOINT_MANIFEST_VERSION = 16;
    // After reordering into the queue-metadata positional layout, decodeQueueMetadata sees 5 slots (the bundle
    // ends at nextMessageId - 1) or 6: the 4 queue-metadata slots, the endpointManifestVersion slot (offset 16),
    // and, for a bundle that ends earlier, the running-hash slot of its last message.
    private static final int STORAGE_PROOF_MIN_ENTRIES = 5;
    private static final int STORAGE_PROOF_MAX_ENTRIES = 6;
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
    private static final int SP_INDEX_CHANNEL_ENDPOINT_MANIFEST_VERSION = 4;
    private static final int SP_INDEX_LAST_MSG_RUNNING_HASH = 5; // optional

    private final Config config;

    public BesuQbftVerifier(@NonNull final Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Decode and verify a QBFT bundle payload, binding the proven Channel storage slots to
     * {@code channelId}. The four queue-metadata slots and the {@code endpointManifestVersion} slot are
     * identified by recomputing their canonical storage keys — {@code keccak256(channelId || 15)} (the
     * {@code _channels} mapping base) plus field offsets {@code {1,2,4,5}} and {@code 16}. A sixth proven
     * slot that matches none of the expected Channel keys is taken to be the running-hash slot of the
     * bundle's last message; its key is returned so the system contract can bind it to a message id
     * ({@link #findLastMessageId}). More than one such leftover is a hard error.
     *
     * @param bundlePayload the RLP-encoded payload as produced by
     *     {@code QbftBundleConstructor.PayloadRlpEncoder.encodeQbftBundlePayload}
     * @param trustAnchor the RLP-encoded trusted validator set ({@link #encodeValidatorSet})
     * @param anchorBlockNumber the block at which the trusted set was last confirmed; every hop and the
     *     current block must come after it (replay floor)
     * @param channelId the 32-byte id of the channel the bundle is submitted to
     * @return the verified block hash, the protobuf-serialized {@code ClprBundleContent}, the proven
     *     queue metadata and any proven trust-anchor or endpoint-manifest advance
     * @throws ProofException if RLP decoding fails or any structural check is violated
     */
    @NonNull
    public VerifiedBundle verifyBundle(
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor,
            final long anchorBlockNumber,
            @NonNull final byte[] channelId) {
        Objects.requireNonNull(bundlePayload, "bundlePayload");
        Objects.requireNonNull(trustAnchor, "trustAnchor");
        Objects.requireNonNull(channelId, "channelId");
        return rejectingMalformedInput(
                () -> verifyBundleUnchecked(bundlePayload, trustAnchor, anchorBlockNumber, channelId));
    }

    @NonNull
    private VerifiedBundle verifyBundleUnchecked(
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor,
            final long anchorBlockNumber,
            @NonNull final byte[] channelId) {
        Objects.requireNonNull(bundlePayload, "bundlePayload");
        Objects.requireNonNull(trustAnchor, "trustAnchor");
        Objects.requireNonNull(channelId, "channelId");
        if (channelId.length != CHANNEL_ID_LENGTH) {
            throw ProofException.besuQbft("channelId must be " + CHANNEL_ID_LENGTH + " bytes, got " + channelId.length);
        }

        final List<byte[]> anchorValidators = decodeValidatorSet(trustAnchor, "trustAnchor");
        log.debug(
                "BesuQbftProofVerifier.verifyBundle ENTER: bundlePayload={} bytes, trustAnchor(validators={})=0x{}",
                bundlePayload.length,
                anchorValidators.size(),
                HEX.formatHex(trustAnchor));
        log.debug(
                "BesuQbftProofVerifier.verifyBundle config: expectedContractAddress=0x{}, expectedContractCodeHash=0x{}",
                config.expectedContractAddress20() == null
                        ? "<null>"
                        : HEX.formatHex(config.expectedContractAddress20()),
                config.expectedContractCodeHash32() == null
                        ? "<null>"
                        : HEX.formatHex(config.expectedContractCodeHash32()));

        final List<Rlp.Item> fields = decodeBundleFields(bundlePayload);

        // Field 1, the validator source, is RLP([trustedSet, hops]) as in the QBFT verifier spec. Our anchor
        // stores the full trusted set, so the relay may send it empty (0xc0); when sent, it must be the
        // anchor's set.
        final Rlp.Item validatorSource = fields.get(1);
        if (!validatorSource.isList() || validatorSource.children().size() != VALIDATOR_SOURCE_ITEMS) {
            throw ProofException.besuQbft(
                    "header-mode validator source (field 1) must be an RLP list of [trustedSet, hops]");
        }
        final Rlp.Item trustedSetItem = validatorSource.children().get(0);
        final Rlp.Item hopsItem = validatorSource.children().get(1);
        if (!trustedSetItem.isList() || !hopsItem.isList()) {
            throw ProofException.besuQbft("validator source trustedSet and hops must be RLP lists");
        }
        if (!trustedSetItem.children().isEmpty()
                && !sameValidators(
                        decodeValidatorSet(trustedSetItem.rawBytes(), "validatorSource.trustedSet"),
                        anchorValidators)) {
            throw ProofException.besuQbft(
                    "validator source trustedSet does not match the trust anchor's validator set");
        }

        // --- 0. Walk the rotation hops (if any), advancing the trusted validator set ----------
        // Every hop must come after the anchor block (replay floor) and after the previous hop.
        final HopWalk walk = walkHops(hopsItem, anchorValidators, anchorBlockNumber);
        final List<byte[]> effectiveTrustAnchor = walk.validators();

        // --- 1. Parse the block header and extract the state root --------------------------
        final Rlp.Item headerItem = fields.get(0);
        requireHeader(headerItem, "block header");
        final long currentBlockNumber = blockNumber(headerItem, "block header");
        if (currentBlockNumber <= walk.lastBlockNumber()) {
            throw ProofException.besuQbft("block header number " + currentBlockNumber
                    + " is not after the last hop or the anchor block (" + walk.lastBlockNumber() + ")");
        }
        final byte[] stateRoot32 =
                checkedCopy(headerItem.children().get(HEADER_STATE_ROOT_INDEX).asBytes(), 32, "blockHeader.stateRoot");
        final byte[] blockHash32 = besuBlockHash(headerItem);
        log.debug(
                "BesuQbftProofVerifier.verifyBundle header: blockHash=0x{}, stateRoot=0x{}, headerFields={}",
                HEX.formatHex(blockHash32),
                HEX.formatHex(stateRoot32),
                headerItem.children().size());

        // Authenticate the header against the effective validator set: it may list that set or a set one
        // change away (a change at the current block needs no hop), sealed by a quorum of its own list.
        final List<byte[]> currentValidators = verifyHeaderAgainstTrustedSet(headerItem, effectiveTrustAnchor, false);
        log.debug("BesuQbftProofVerifier.verifyBundle QBFT committed seal verified against trustAnchor");

        // A new trust anchor, at the current block, when the bundle crossed hops or the current block lists a
        // changed set: the anchor never silently lags the real validator set, and the replay floor moves past
        // every hop this bundle used.
        final boolean crossedHops = !hopsItem.children().isEmpty();
        final boolean currentSetChanged = !sameValidators(currentValidators, effectiveTrustAnchor);
        final byte[] newTrustAnchor;
        final byte[] newTrustAnchorId;
        if (crossedHops || currentSetChanged) {
            newTrustAnchor = encodeValidatorSet(currentValidators);
            newTrustAnchorId = trustAnchorId(currentBlockNumber);
            log.debug(
                    "BesuQbftProofVerifier.verifyBundle hops={} currentSetChanged={} newValidators={} anchorBlock={}",
                    hopsItem.children().size(),
                    currentSetChanged,
                    currentValidators.size(),
                    currentBlockNumber);
        } else {
            newTrustAnchor = new byte[0];
            newTrustAnchorId = new byte[0];
        }
        return proveChannelState(fields, stateRoot32, blockHash32, channelId, newTrustAnchor, newTrustAnchorId, null);
    }

    /**
     * Decode and verify a QBFT bundle payload against a trust anchor of either mode: a header-mode anchor is
     * verified as in {@link #verifyBundle(byte[], byte[], long, byte[])}, a contract-mode anchor as in
     * {@link #verifyContractModeBundle}.
     *
     * @param bundlePayload the RLP-encoded bundle payload
     * @param trustAnchor the channel's trust anchor
     * @param channelId the 32-byte id of the channel the bundle is submitted to
     * @return as {@link #verifyBundle(byte[], byte[], long, byte[])}; in contract mode a proven advance also
     *     carries the validator contract's storage root at the current block
     * @throws ProofException if the bundle does not verify
     */
    @NonNull
    public VerifiedBundle verifyBundle(
            @NonNull final byte[] bundlePayload,
            @NonNull final TrustAnchor trustAnchor,
            @NonNull final byte[] channelId) {
        Objects.requireNonNull(trustAnchor, "trustAnchor");
        final ValidatorContract validatorContract = trustAnchor.validatorContract();
        if (validatorContract == null) {
            return verifyBundle(
                    bundlePayload, trustAnchor.validatorSetEncoded(), trustAnchor.anchorBlockNumber(), channelId);
        }
        return verifyContractModeBundle(bundlePayload, trustAnchor, validatorContract, channelId);
    }

    /**
     * Verifies a bundle against a contract-mode anchor. Field 1 is {@code RLP([trustedSet, hops, contractProof])}:
     *
     * <ul>
     *   <li>{@code trustedSet} — the anchor's set (duplicates are ignored).</li>
     *   <li>{@code hops} — {@code RLP([changeBlockHeader, contractProof])} each: a block after the previous one
     *       (the anchor block first), sealed by a quorum of the trusted set, whose own state holds a different
     *       validator array; that array becomes the trusted set. Any number of members may change in one hop.</li>
     *   <li>{@code contractProof} — the validator contract at the current block. The current block comes after
     *       the last hop and is sealed by a quorum of the trusted set. If the contract's storage root is the one
     *       last trusted (the anchor's, or the last hop's) the set is unchanged and the array may be omitted;
     *       otherwise the proof carries the whole array, which is the set at the current block.</li>
     * </ul>
     *
     * <p>The set of block N is the array in the state of block N − 1, and block N is sealed by that set. So a
     * change block is sealed by the set before the change, and its own state gives the new set.
     *
     * <p>A new anchor, at the current block, is returned when the bundle crossed hops, the set changed, or the
     * validator contract's storage root changed.
     */
    @NonNull
    private VerifiedBundle verifyContractModeBundle(
            @NonNull final byte[] bundlePayload,
            @NonNull final TrustAnchor trustAnchor,
            @NonNull final ValidatorContract validatorContract,
            @NonNull final byte[] channelId) {
        Objects.requireNonNull(bundlePayload, "bundlePayload");
        Objects.requireNonNull(channelId, "channelId");
        if (channelId.length != CHANNEL_ID_LENGTH) {
            throw ProofException.besuQbft("channelId must be " + CHANNEL_ID_LENGTH + " bytes, got " + channelId.length);
        }
        final List<byte[]> anchorValidators =
                decodeValidatorSet(trustAnchor.validatorSetEncoded(), "trustAnchor.validatorSet");
        final List<Rlp.Item> fields = decodeBundleFields(bundlePayload);

        final Rlp.Item validatorSource = fields.get(1);
        if (!validatorSource.isList() || validatorSource.children().size() != CONTRACT_MODE_VALIDATOR_SOURCE_ITEMS) {
            throw ProofException.besuQbft(
                    "contract-mode validator source (field 1) must be an RLP list of [trustedSet, hops, contractProof]");
        }
        final Rlp.Item trustedSetItem = validatorSource.children().get(0);
        final Rlp.Item hopsItem = validatorSource.children().get(1);
        final Rlp.Item currentContractProofItem = validatorSource.children().get(2);
        if (!trustedSetItem.isList() || !hopsItem.isList()) {
            throw ProofException.besuQbft("validator source trustedSet and hops must be RLP lists");
        }
        // As in the QBFT verifier spec, the trusted set is always sent; duplicates are dropped before it is
        // compared with the anchored set.
        if (trustedSetItem.children().size() > MAX_CONTRACT_MODE_VALIDATORS) {
            throw ProofException.besuQbft("validator source trustedSet lists "
                    + trustedSetItem.children().size() + " validators; at most " + MAX_CONTRACT_MODE_VALIDATORS
                    + " are accepted");
        }
        final List<byte[]> trustedSet =
                distinctSorted(decodeValidatorSet(trustedSetItem.rawBytes(), "validatorSource.trustedSet"));
        if (!sameValidators(trustedSet, anchorValidators)) {
            throw ProofException.besuQbft(
                    "validator source trustedSet does not match the trust anchor's validator set");
        }
        final List<Rlp.Item> hops = hopsItem.children();
        if (hops.size() > MAX_CONTRACT_MODE_HOPS) {
            throw ProofException.besuQbft(
                    "bundle crosses " + hops.size() + " hops; at most " + MAX_CONTRACT_MODE_HOPS + " are accepted");
        }

        // --- 0. Walk the hops: each is sealed by the trusted set and its state gives the next set -------------
        List<byte[]> validators = anchorValidators;
        long lastBlockNumber = trustAnchor.anchorBlockNumber();
        byte[] knownStorageRoot32 = validatorContract.storageRoot32();
        for (int i = 0; i < hops.size(); i++) {
            final String name = "hops[" + i + "]";
            final Rlp.Item hop = hops.get(i);
            if (!hop.isList() || hop.children().size() != 2) {
                throw ProofException.besuQbft(name + " must be an RLP list of [changeBlockHeader, contractProof]");
            }
            final Rlp.Item hopHeader = hop.children().get(0);
            requireHeader(hopHeader, name + ".changeBlockHeader");
            final long blockNumber = blockNumber(hopHeader, name + ".changeBlockHeader");
            if (blockNumber <= lastBlockNumber) {
                throw ProofException.besuQbft(name + " block number " + blockNumber
                        + " is not strictly greater than previous " + lastBlockNumber);
            }
            verifyQbftSealAgainstValidatorSet(hopHeader, validators);
            final ResolvedValidators resolved = resolveValidators(
                    ValidatorContractProof.decode(hop.children().get(1), name + ".contractProof"),
                    stateRoot(hopHeader, name + ".changeBlockHeader"),
                    validatorContract,
                    validatorContract.codeHash32(),
                    null);
            final List<byte[]> next = Objects.requireNonNull(resolved.validators());
            if (sameValidators(next, validators)) {
                throw ProofException.besuQbft(name + " at block " + blockNumber + " does not change the validator set");
            }
            log.debug(
                    "verifyContractModeBundle {}: blockNumber={}, validators {} -> {}",
                    name,
                    blockNumber,
                    validators.size(),
                    next.size());
            validators = next;
            lastBlockNumber = blockNumber;
            knownStorageRoot32 = resolved.storageRoot32();
        }

        // --- 1. The current block: sealed by the trusted set, and its state gives the current set -----------
        final Rlp.Item headerItem = fields.get(0);
        requireHeader(headerItem, "block header");
        final long currentBlockNumber = blockNumber(headerItem, "block header");
        if (currentBlockNumber <= lastBlockNumber) {
            throw ProofException.besuQbft("block header number " + currentBlockNumber
                    + " is not after the last hop or the anchor block (" + lastBlockNumber + ")");
        }
        verifyQbftSealAgainstValidatorSet(headerItem, validators);
        final byte[] stateRoot32 = stateRoot(headerItem, "block header");
        final byte[] blockHash32 = besuBlockHash(headerItem);
        final ResolvedValidators current = resolveValidators(
                ValidatorContractProof.decode(currentContractProofItem, "validatorSource.contractProof"),
                stateRoot32,
                validatorContract,
                validatorContract.codeHash32(),
                knownStorageRoot32);
        final List<byte[]> currentValidators = current.validators() == null ? validators : current.validators();

        final boolean crossedHops = !hops.isEmpty();
        final boolean setChanged = !sameValidators(currentValidators, anchorValidators);
        final boolean storageRootChanged = !Arrays.equals(current.storageRoot32(), validatorContract.storageRoot32());
        final byte[] newTrustAnchor;
        final byte[] newTrustAnchorId;
        if (crossedHops || setChanged || storageRootChanged) {
            newTrustAnchor = encodeValidatorSet(currentValidators);
            newTrustAnchorId = trustAnchorId(currentBlockNumber);
            log.debug(
                    "verifyContractModeBundle hops={} setChanged={} storageRootChanged={} validators={} anchorBlock={}",
                    hops.size(),
                    setChanged,
                    storageRootChanged,
                    currentValidators.size(),
                    currentBlockNumber);
        } else {
            newTrustAnchor = new byte[0];
            newTrustAnchorId = new byte[0];
        }
        return proveChannelState(
                fields,
                stateRoot32,
                blockHash32,
                channelId,
                newTrustAnchor,
                newTrustAnchorId,
                newTrustAnchor.length > 0 ? current.storageRoot32() : null);
    }

    /**
     * Decodes a bundle payload into its top-level fields. A bundle is 5 items, or 7 when the peer attaches its
     * endpoint-manifest advance (spec §4.9): index 5 = manifest storage proof, index 6 = manifest preimage. The
     * message content and queue metadata live in indices 0-4. A manifest-only advance is simply a 7-item bundle
     * with empty content — the exact wire form the QBFT verifier contract and the clpr-evm-endpoint relay
     * produce; there is no distinct "manifest-only" bundle shape. (The 4-item
     * RLP([blockHeader, accountProof, manifestStorageProof, manifestPreimage]) form is the config-path proof
     * consumed by verifyConfig, never a bundle.)
     */
    @NonNull
    private static List<Rlp.Item> decodeBundleFields(@NonNull final byte[] bundlePayload) {
        final Rlp.Item top;
        try {
            top = Rlp.decodeOne(bundlePayload);
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("payload is not a valid RLP item: " + e.getMessage());
        }
        final int itemCount = top.isList() ? top.children().size() : -1;
        if (itemCount != 5 && itemCount != 7) {
            throw ProofException.besuQbft("expected top-level RLP list of 5 or 7 items, got "
                    + (top.isList() ? itemCount + " items" : "non-list"));
        }
        return top.children();
    }

    /** Reads a header's state root (field 3). */
    @NonNull
    private static byte[] stateRoot(@NonNull final Rlp.Item headerItem, @NonNull final String name) {
        return checkedCopy(headerItem.children().get(HEADER_STATE_ROOT_INDEX).asBytes(), 32, name + ".stateRoot");
    }

    /**
     * Proves the CLPR service state of an authenticated block (steps 2–5 of bundle verification, the same in
     * both modes): the service account under the block's state root, the Channel storage slots and the queue
     * metadata they hold, the bundle content, and the optional endpoint-manifest advance.
     *
     * @param newValidatorStorageRoot32 in contract mode, the validator contract's storage root at the current
     *     block when a new anchor is returned; {@code null} otherwise
     */
    @NonNull
    private VerifiedBundle proveChannelState(
            @NonNull final List<Rlp.Item> fields,
            @NonNull final byte[] stateRoot32,
            @NonNull final byte[] blockHash32,
            @NonNull final byte[] channelId,
            @NonNull final byte[] newTrustAnchor,
            @NonNull final byte[] newTrustAnchorId,
            @Nullable final byte[] newValidatorStorageRoot32) {
        // --- 2. Verify the account proof against the block's state root --------------------
        if (config.expectedContractAddress20() == null) {
            throw ProofException.besuQbft("config.expectedContractAddress20 is required to verify the account proof");
        }
        final byte[] contractAddress20 = config.expectedContractAddress20();
        final byte[][] accountProof = decodeNodeList(fields.get(2), "accountProof");
        final byte[] accountKey = MiscCryptoUtils.keccak256DigestOf(contractAddress20);
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
        final List<SlotProof> storageProof = decodeStorageProofList(fields.get(3));
        final QueueMetadata queueMetadata;
        if (storageProof.isEmpty()) {
            // Absent queue metadata: the all-zero sentinel (nextMessageId == 0, manifest version 0).
            queueMetadata = new QueueMetadata(0L, new byte[32], 0L, new byte[32], 0, new byte[32], 0L);
        } else {
            if (storageProof.size() != CHANNEL_STORAGE_SLOTS && storageProof.size() != CHANNEL_STORAGE_SLOTS + 1) {
                throw ProofException.besuQbft("storageProof has " + storageProof.size() + " entries; expected "
                        + CHANNEL_STORAGE_SLOTS + ", " + (CHANNEL_STORAGE_SLOTS + 1)
                        + " (with the last message's running hash), or 0 (manifest-only)");
            }
            // Sort entries by slot key (big-endian unsigned) for a canonical order independent of the
            // EVM's storageProof delivery order.
            final List<SlotProof> orderedStorageProof = new ArrayList<>(storageProof);
            orderedStorageProof.sort((a, b) -> Arrays.compareUnsigned(
                    leftPad32(a.key(), "storageProof.key"), leftPad32(b.key(), "storageProof.key")));
            final int entryCount = orderedStorageProof.size();
            final byte[][] slotKeys = new byte[entryCount][];
            final byte[][] provenSlotValues = new byte[entryCount][];
            for (int i = 0; i < entryCount; i++) {
                final SlotProof entry = orderedStorageProof.get(i);
                final byte[] slot32 = leftPad32(entry.key(), "storageProof[" + i + "].key");
                final byte[] storageKey = MiscCryptoUtils.keccak256DigestOf(slot32);
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
            // Match the proven slots to the channel's canonical slot keys and lay them out in the order
            // decodeQueueMetadata expects: [status|nextMsgId, acked|received|reply, sentRunningHash,
            // receivedRunningHash, endpointManifestVersion] and — when a message is present — the
            // last-message running hash.
            final MatchedSlots matched = matchQueueSlotsByConnId(slotKeys, provenSlotValues, channelId);
            queueMetadata = decodeQueueMetadata(matched.values(), matched.lastMessageSlotKey());
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
        // Content (messages or relayed metadata) is only meaningful against proven queue state: without the
        // Channel storage proof nothing in the content could be checked against the peer's state.
        if (storageProof.isEmpty() && bundleContentBytes.length != 0) {
            throw ProofException.besuQbft("bundle carries content but no Channel storage proof");
        }

        return new VerifiedBundle(
                blockHash32,
                bundleContentBytes,
                queueMetadata,
                newTrustAnchor,
                newTrustAnchorId,
                newEndpointManifestBytes,
                newValidatorStorageRoot32 == null ? new byte[0] : newValidatorStorageRoot32);
    }

    /**
     * Decode and verify a QBFT config payload, returning the proven
     * {@link ClprLedgerConfiguration} bytes.
     *
     * <p>The payload is a {@code ClprQbftLedgerConfigurationPayload}. Its {@code genesis_block_header}
     * field is ignored: every QBFT header lists the validator set that sealed it, so the initial set is
     * read from {@code current_block_header} itself.
     *
     * <p>Verification steps:
     * <ol>
     *   <li>Take the initial validator set from {@code currentBlockHeader}'s extra-data validators list (header
     *       mode) or, when {@code contract_mode_validator_source} is present, prove it from the validator
     *       contract's array under {@code currentBlockHeader.stateRoot} (contract mode).</li>
     *   <li>Verify that a quorum of that set sealed {@code currentBlockHeader}.</li>
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
     * header seal is authenticated against the config's initial validator set, the CLPR-service account
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
        return rejectingMalformedInput(() -> verifyConfigPayloadUnchecked(configPayload, endpointManifestProofBytes));
    }

    /**
     * Runs a verification, reporting any failure as a {@link ProofException}. The payload is untrusted, so a
     * malformed one can also fail in a lower-level check that throws an unchecked exception (a byte string of the
     * wrong length, a list where bytes were expected); such a failure is still a rejected proof, not an error.
     */
    @NonNull
    private static <T> T rejectingMalformedInput(@NonNull final Supplier<T> verification) {
        try {
            return verification.get();
        } catch (final ProofException e) {
            throw e;
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("malformed input: " + e.getMessage());
        }
    }

    @NonNull
    private VerifiedConfig verifyConfigPayloadUnchecked(
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

        if (payload.currentBlockHeader() == null
                || payload.currentBlockHeader().rlp().length() == 0) {
            throw ProofException.besuQbft("ClprQbftLedgerConfigurationPayload.currentBlockHeader is missing or empty");
        }
        if (payload.ledgerConfiguration() == null) {
            throw ProofException.besuQbft("ClprQbftLedgerConfigurationPayload.ledgerConfiguration is missing");
        }

        // --- 1. RLP-decode the block header (rlp field contains raw Ethereum header bytes) ---
        final Rlp.Item currentHeaderItem;
        try {
            currentHeaderItem = Rlp.decodeOne(payload.currentBlockHeader().rlp().toByteArray());
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("block header RLP decoding failed: " + e.getMessage());
        }
        requireHeader(currentHeaderItem, "currentBlockHeader");
        final long configBlockNumber = blockNumber(currentHeaderItem, "currentBlockHeader");
        final byte[] stateRoot32 = checkedCopy(
                currentHeaderItem.children().get(HEADER_STATE_ROOT_INDEX).asBytes(),
                32,
                "currentBlockHeader.stateRoot");
        final byte[] blockHash32 = besuBlockHash(currentHeaderItem);

        // --- 2. The initial validator set: listed in the header (header mode), or proven from the validator
        // contract's array under the header's state root (contract mode, when field 7 is present). A change block
        // fails the seal check below: it is sealed by the set before the change, not by the array it holds.
        final QbftContractModeValidatorSource contractModeSource = payload.contractModeValidatorSource();
        final ResolvedValidators contractModeValidators;
        final List<byte[]> configValidators;
        if (contractModeSource == null) {
            contractModeValidators = null;
            configValidators = extractValidators(currentHeaderItem);
        } else {
            final ValidatorContractProof proof = ValidatorContractProof.of(contractModeSource);
            contractModeValidators =
                    resolveValidators(proof, stateRoot32, proof.contract20(), proof.arraySlot32(), null, null);
            configValidators = Objects.requireNonNull(contractModeValidators.validators());
        }
        log.info(
                "BesuQbftProofVerifier.verifyConfigPayload: mode={}, configValidators={}",
                contractModeSource == null ? "header" : "contract",
                configValidators.size());

        // --- 3. Verify that a quorum of that set sealed the header (self-consistency) -------
        verifyQbftSealAgainstValidatorSet(currentHeaderItem, configValidators);
        log.info("BesuQbftProofVerifier.verifyConfigPayload: QBFT committed seal verified");

        // --- 4. Read service_address directly from the ledger configuration -
        final byte[] contractAddress20 = extractServiceAddress(payload.ledgerConfiguration());
        log.info("BesuQbftProofVerifier.verifyConfigPayload: service_address=0x{}", HEX.formatHex(contractAddress20));

        // --- 5. Verify account proof against current block's state root -------------------
        final byte[][] accountProof = payload.clprServiceAccountProof().stream()
                .map(Bytes::toByteArray)
                .toArray(byte[][]::new);
        final byte[] accountKey = MiscCryptoUtils.keccak256DigestOf(contractAddress20);
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

        // --- 6. Optionally verify the service_address storage slot ------------------------
        // As in the QBFT verifier spec, the configuration values (service address included) are declared by
        // the channel creator and trusted on first use; the account proof above already proves the service
        // account and its code hash. A relay may still prove the service_address slot, and if it does the
        // proven value must match.
        final var serviceAddrEntry = payload.clprServiceStorageProofs().stream()
                .filter(e ->
                        Arrays.equals(leftPad32(e.key().toByteArray(), "storageProof.key"), SERVICE_ADDR_STORAGE_SLOT))
                .findFirst();
        if (serviceAddrEntry.isPresent()) {
            verifyServiceAddressSlot(serviceAddrEntry.get(), provenAccount, contractAddress20);
        }

        final var initialTrustAnchor = new TrustAnchor(
                encodeValidatorSet(configValidators),
                contractAddress20,
                provenAccount.codeHash32(),
                configBlockNumber,
                contractModeSource == null
                        ? null
                        : new ValidatorContract(
                                contractModeSource.validatorContractAddress().toByteArray(),
                                contractModeSource.arraySlot().toByteArray(),
                                contractModeValidators.codeHash32(),
                                contractModeValidators.storageRoot32()));
        final var ledgerCfg = payload.ledgerConfiguration()
                .copyBuilder()
                .initialTrustAnchor(Bytes.wrap(initialTrustAnchor.encode()))
                .initialTrustAnchorId(Bytes.wrap(initialTrustAnchor.id()))
                .build();

        // --- 7. Verify the endpoint-manifest state proof (Step 1b bootstrap), if one was supplied ----
        // A non-empty third verifyConfig argument carries the peer's endpoint-manifest state proof,
        // authenticated against the same config block. Empty → byte[0]; the caller applies its bring-up
        // fallback (the real manifest then arrives via the first bundle's Step 1b).
        final byte[] endpointManifestBytes = endpointManifestProofBytes.length == 0
                ? new byte[0]
                : verifyConfigEndpointManifestProof(endpointManifestProofBytes, configValidators, contractAddress20);

        log.info(
                "BesuQbftProofVerifier.verifyConfigPayload EXIT: SUCCESS blockHash=0x{} chainId={} initialTrustAnchor({} bytes)=0x{} initialTrustAnchorId({} bytes)=0x{} endpointManifest={} bytes",
                HEX.formatHex(blockHash32),
                ledgerCfg.chainId(),
                ledgerCfg.initialTrustAnchor().length(),
                ledgerCfg.initialTrustAnchor().toHex(),
                ledgerCfg.initialTrustAnchorId().length(),
                ledgerCfg.initialTrustAnchorId().toHex(),
                endpointManifestBytes.length);
        return new VerifiedConfig(blockHash32, ledgerCfg, endpointManifestBytes);
    }

    /**
     * Verifies a proof of the {@code _config.serviceAddress} slot (25) against the proven service account's
     * storage root: the slot must hold {@code serviceAddress20} in Solidity's short-{@code bytes} layout.
     */
    private static void verifyServiceAddressSlot(
            @NonNull final StorageProofEntry serviceAddrEntry,
            @NonNull final Account provenAccount,
            @NonNull final byte[] serviceAddress20) {
        final byte[] storageKey = MiscCryptoUtils.keccak256DigestOf(SERVICE_ADDR_STORAGE_SLOT);
        final byte[][] storageProofNodes =
                serviceAddrEntry.proof().stream().map(Bytes::toByteArray).toArray(byte[][]::new);
        final byte[] provenValue32 = Mpt.get(provenAccount.storageRoot32(), storageKey, storageProofNodes)
                .map(Rlp::decodeTrieStorageValueAsBytes32)
                .orElseGet(() -> new byte[32]);
        // _config.serviceAddress is Solidity `bytes` (not `address`). Short-bytes(20) slot layout:
        //   [addr 20B left-aligned] [zeros 11B] [length*2 = 0x28]
        final byte[] expectedValue32 = new byte[32];
        System.arraycopy(serviceAddress20, 0, expectedValue32, 0, ADDRESS_LENGTH);
        expectedValue32[31] = (byte) (ADDRESS_LENGTH * 2);
        if (!Arrays.equals(expectedValue32, provenValue32)) {
            throw ProofException.besuQbft("service_address storage slot does not match proven value");
        }
        log.info(
                "BesuQbftProofVerifier.verifyConfigPayload: service_address slot verified: key=0x{} value=0x{}",
                HEX.formatHex(SERVICE_ADDR_STORAGE_SLOT),
                HEX.formatHex(provenValue32));
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
        requireHeader(headerItem, "endpoint-manifest config proof header");
        verifyQbftSealAgainstValidatorSet(headerItem, configValidators);
        final byte[] stateRoot32 = checkedCopy(
                headerItem.children().get(HEADER_STATE_ROOT_INDEX).asBytes(),
                32,
                "endpointManifestConfigProof.stateRoot");

        // 2. Authenticate the CLPR-service account against the header's state root (codeHash unpinned).
        final byte[][] accountProof =
                decodeNodeList(p.get(CFG_MANIFEST_ACCOUNT_PROOF_INDEX), "endpointManifestConfigProof.accountProof");
        final byte[] accountRlp = Mpt.get(
                        stateRoot32, MiscCryptoUtils.keccak256DigestOf(serviceAddress20), accountProof)
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
        final List<SlotProof> entries = decodeStorageProofList(manifestStorageProofItem);
        if (entries.size() != 1) {
            throw ProofException.besuQbft(
                    "endpoint-manifest storage proof must contain exactly 1 entry, got " + entries.size());
        }
        final SlotProof entry = entries.get(0);
        final byte[] slot32 = leftPad32(entry.key(), "endpointManifestProof.key");
        if (!Arrays.equals(slot32, endpointManifestCommitmentSlot())) {
            throw ProofException.besuQbft("endpoint-manifest storage proof is not for the commitment slot (18)");
        }
        final byte[] provenCommitment = Mpt.get(
                        storageRoot32, MiscCryptoUtils.keccak256DigestOf(slot32), entry.proofNodes())
                .map(Rlp::decodeTrieStorageValueAsBytes32)
                .orElseThrow(() ->
                        ProofException.besuQbft("endpoint-manifest commitment slot absent from the storage trie"));
        if (!Arrays.equals(MiscCryptoUtils.keccak256DigestOf(manifestPreimage), provenCommitment)) {
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
    private static List<SlotProof> decodeStorageProofList(@NonNull final Rlp.Item item) {
        if (!item.isList()) {
            throw ProofException.besuQbft("storageProof is not an RLP list");
        }
        final List<Rlp.Item> entries = item.children();
        final List<SlotProof> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            final Rlp.Item entry = entries.get(i);
            if (!entry.isList() || entry.children().size() != 2) {
                throw ProofException.besuQbft("storageProof[" + i + "] is not a [key, proof[]] RLP list");
            }
            out.add(new SlotProof(
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
        requireNoZeroAddress(result, name);
        return result;
    }

    /** Rejects the zero address, which no validator key can sign for, in a validator list. */
    private static void requireNoZeroAddress(@NonNull final List<byte[]> validators, @NonNull final String name) {
        for (final byte[] validator : validators) {
            if (Arrays.equals(validator, ZERO_ADDRESS)) {
                throw ProofException.besuQbft(name + " contains the zero address");
            }
        }
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
        requireNoZeroAddress(validators, "blockHeader.extraData.validators");
        for (int i = 1; i < validators.size(); i++) {
            if (Arrays.equals(validators.get(i), validators.get(i - 1))) {
                throw ProofException.besuQbft(
                        "blockHeader.extraData.validators lists 0x" + HEX.formatHex(validators.get(i)) + " twice");
            }
        }
        return validators;
    }

    /**
     * Walks the rotation hops (the {@code hops} of field 1): each hop must come after the previous one and after the anchor
     * block (replay floor), change the trusted validator set by exactly one member, and be sealed per
     * {@link #verifyHeaderAgainstTrustedSet}. The set a hop lists becomes the trusted set for the next one.
     *
     * @return the trusted validator set after the last hop, and the last hop's block number (the anchor
     *     block when there are no hops)
     */
    @NonNull
    private static HopWalk walkHops(
            @NonNull final Rlp.Item hopsItem,
            @NonNull final List<byte[]> anchorValidators,
            final long anchorBlockNumber) {
        List<byte[]> validatorSet = anchorValidators;
        long lastBlockNumber = anchorBlockNumber;
        final List<Rlp.Item> hops = hopsItem.children();
        for (int i = 0; i < hops.size(); i++) {
            final Rlp.Item hop = hops.get(i);
            final String name = "hops[" + i + "]";
            requireHeader(hop, name);
            final long blockNumber = blockNumber(hop, name);
            if (blockNumber <= lastBlockNumber) {
                throw ProofException.besuQbft(name + " block number " + blockNumber
                        + " is not strictly greater than previous " + lastBlockNumber);
            }
            lastBlockNumber = blockNumber;
            log.debug(
                    "walkHops[{}]: blockNumber={}, verifying rotation against {} validators",
                    i,
                    blockNumber,
                    validatorSet.size());
            validatorSet = verifyHeaderAgainstTrustedSet(hop, validatorSet, true);
        }
        return new HopWalk(validatorSet, lastBlockNumber);
    }

    /** The result of walking the rotation hops. */
    private record HopWalk(@NonNull List<byte[]> validators, long lastBlockNumber) {}

    /**
     * Proves a contract-mode validator set from a {@link ValidatorContractProof} under {@code stateRoot32}.
     *
     * <ol>
     *   <li>The proof must name the expected validator contract and array slot.</li>
     *   <li>The contract account is proven under {@code stateRoot32}; when {@code expectedCodeHash32} is given,
     *       its code hash must match.</li>
     *   <li>When {@code knownStorageRoot32} is given and equals the proven storage root, the array cannot have
     *       changed: the storage proofs are not read and no set is returned.</li>
     *   <li>Otherwise the array's length slot ({@code arraySlot}) and element slots ({@code keccak256(arraySlot) + i})
     *       are proven under the account's storage root. The array must hold 1 to
     *       {@value #MAX_CONTRACT_MODE_VALIDATORS} distinct, non-zero addresses (the low 20 bytes of each slot).</li>
     * </ol>
     *
     * @param expected the validator contract and array slot the proof must name
     * @param expectedCodeHash32 the pinned code hash, or {@code null} to accept any (configuration time)
     * @param knownStorageRoot32 the last trusted storage root, or {@code null} to always prove the array
     */
    @NonNull
    private static ResolvedValidators resolveValidators(
            @NonNull final ValidatorContractProof proof,
            @NonNull final byte[] stateRoot32,
            @NonNull final ValidatorContract expected,
            @Nullable final byte[] expectedCodeHash32,
            @Nullable final byte[] knownStorageRoot32) {
        return resolveValidators(
                proof,
                stateRoot32,
                expected.address20(),
                expected.arraySlot32(),
                expectedCodeHash32,
                knownStorageRoot32);
    }

    @NonNull
    private static ResolvedValidators resolveValidators(
            @NonNull final ValidatorContractProof proof,
            @NonNull final byte[] stateRoot32,
            @NonNull final byte[] expectedContract20,
            @NonNull final byte[] expectedArraySlot32,
            @Nullable final byte[] expectedCodeHash32,
            @Nullable final byte[] knownStorageRoot32) {
        if (!Arrays.equals(proof.contract20(), expectedContract20)) {
            throw ProofException.besuQbft("validator contract proof is for 0x" + HEX.formatHex(proof.contract20())
                    + ", expected 0x" + HEX.formatHex(expectedContract20));
        }
        if (!Arrays.equals(proof.arraySlot32(), expectedArraySlot32)) {
            throw ProofException.besuQbft("validator contract proof is for array slot 0x"
                    + HEX.formatHex(proof.arraySlot32()) + ", expected 0x" + HEX.formatHex(expectedArraySlot32));
        }
        final byte[] accountRlp = Mpt.get(
                        stateRoot32, MiscCryptoUtils.keccak256DigestOf(proof.contract20()), proof.accountProof())
                .orElseThrow(() -> ProofException.besuQbft("validator contract account is absent from state trie"));
        final Account account = Account.decode(accountRlp);
        if (expectedCodeHash32 != null && !Arrays.equals(account.codeHash32(), expectedCodeHash32)) {
            throw ProofException.besuQbft("proven validator contract codeHash 0x" + HEX.formatHex(account.codeHash32())
                    + " does not match the anchored 0x" + HEX.formatHex(expectedCodeHash32));
        }
        if (knownStorageRoot32 != null && Arrays.equals(account.storageRoot32(), knownStorageRoot32)) {
            return new ResolvedValidators(account.storageRoot32(), account.codeHash32(), null);
        }

        final List<SlotProof> entries = proof.storageProof().get();
        if (entries.size() > MAX_CONTRACT_MODE_VALIDATORS + 1) {
            throw ProofException.besuQbft("validator array proof has " + entries.size() + " entries; at most "
                    + (MAX_CONTRACT_MODE_VALIDATORS + 1) + " (length and elements) are accepted");
        }
        // Entries may come in any order; extra entries are ignored and, for a slot proven twice, the first proof
        // is used (each is verified against the storage root, so both prove the same value).
        final Map<BytesKey, byte[][]> proofBySlot = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            final SlotProof entry = entries.get(i);
            proofBySlot.putIfAbsent(
                    new BytesKey(leftPad32(entry.key(), "validatorArrayProof[" + i + "].key")), entry.proofNodes());
        }
        final byte[] storageRoot32 = account.storageRoot32();
        final byte[] lengthValue = provenSlotValue(proofBySlot, storageRoot32, proof.arraySlot32());
        final BigInteger length = new BigInteger(1, lengthValue);
        if (length.signum() == 0) {
            throw ProofException.besuQbft("validator array is empty");
        }
        if (length.compareTo(BigInteger.valueOf(MAX_CONTRACT_MODE_VALIDATORS)) > 0) {
            throw ProofException.besuQbft("validator array holds " + length + " members; at most "
                    + MAX_CONTRACT_MODE_VALIDATORS + " are accepted");
        }
        final int n = length.intValueExact();
        final byte[] elementsBase = MiscCryptoUtils.keccak256DigestOf(proof.arraySlot32());
        final List<byte[]> validators = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final byte[] value = provenSlotValue(proofBySlot, storageRoot32, addToBytes32(elementsBase, i));
            final byte[] validator = Arrays.copyOfRange(value, 32 - ADDRESS_LENGTH, 32);
            if (Arrays.equals(validator, ZERO_ADDRESS)) {
                throw ProofException.besuQbft("validator array element " + i + " is the zero address");
            }
            validators.add(validator);
        }
        validators.sort(Arrays::compareUnsigned);
        for (int i = 1; i < validators.size(); i++) {
            if (Arrays.equals(validators.get(i), validators.get(i - 1))) {
                throw ProofException.besuQbft("validator array lists 0x" + HEX.formatHex(validators.get(i)) + " twice");
            }
        }
        return new ResolvedValidators(storageRoot32, account.codeHash32(), validators);
    }

    /** The proven value of {@code slot32} (zero when the proof shows it absent); the slot must be in the proof. */
    @NonNull
    private static byte[] provenSlotValue(
            @NonNull final Map<BytesKey, byte[][]> proofBySlot,
            @NonNull final byte[] storageRoot32,
            @NonNull final byte[] slot32) {
        final byte[][] proofNodes = proofBySlot.get(new BytesKey(slot32));
        if (proofNodes == null) {
            throw ProofException.besuQbft("validator array slot 0x" + HEX.formatHex(slot32) + " is not proven");
        }
        return Mpt.get(storageRoot32, MiscCryptoUtils.keccak256DigestOf(slot32), proofNodes)
                .map(Rlp::decodeTrieStorageValueAsBytes32)
                .orElseGet(() -> new byte[32]);
    }

    /**
     * A validator contract proven under a block's state root.
     *
     * @param storageRoot32 the contract's storage root
     * @param codeHash32 the contract's code hash
     * @param validators the proven validator array, sorted; {@code null} when the storage root was the known one
     *     and the array was not read
     */
    private record ResolvedValidators(
            @NonNull byte[] storageRoot32,
            @NonNull byte[] codeHash32,
            @Nullable List<byte[]> validators) {}

    /**
     * A proof of a validator contract and its validator array: the contract address, its account proof, the
     * array slot, and storage proofs of the array's length and element slots. The storage proofs are decoded
     * only when read: when the contract's storage root is the known one they are not needed, and, as in the QBFT
     * verifier spec, not parsed.
     */
    private record ValidatorContractProof(
            @NonNull byte[] contract20,
            @NonNull byte[][] accountProof,
            @NonNull byte[] arraySlot32,
            @NonNull Supplier<List<SlotProof>> storageProof) {

        /**
         * Decodes the bundle form: an RLP byte string wrapping
         * {@code RLP([validatorContract, accountProof[], arraySlot, storageProof[[slot, proof[]], ...]])}.
         */
        @NonNull
        static ValidatorContractProof decode(@NonNull final Rlp.Item item, @NonNull final String name) {
            if (item.isList()) {
                throw ProofException.besuQbft(name + " must be an RLP byte string wrapping the proof list");
            }
            final Rlp.Item proof;
            try {
                proof = Rlp.decodeOne(item.asBytes());
            } catch (final RuntimeException e) {
                throw ProofException.besuQbft(name + " is not valid RLP: " + e.getMessage());
            }
            if (!proof.isList() || proof.children().size() != 4) {
                throw ProofException.besuQbft(
                        name + " must be an RLP list of [validatorContract, accountProof, arraySlot, storageProof]");
            }
            final List<Rlp.Item> parts = proof.children();
            if (parts.get(0).isList() || parts.get(2).isList()) {
                throw ProofException.besuQbft(name + " validatorContract and arraySlot must be byte strings");
            }
            // An address item is a single byte (a small address, Solidity's RLP.readAddress) or 20 bytes.
            final int contractItemLength = parts.get(0).rawBytes().length;
            if (contractItemLength != 1 && contractItemLength != ADDRESS_LENGTH + 1) {
                throw ProofException.besuQbft(name + ".validatorContract must be a 1-byte or 20-byte address");
            }
            final byte[] contract = new byte[ADDRESS_LENGTH];
            final byte[] contractBytes = parts.get(0).asBytes();
            System.arraycopy(contractBytes, 0, contract, ADDRESS_LENGTH - contractBytes.length, contractBytes.length);
            final Rlp.Item storageProofItem = parts.get(3);
            return new ValidatorContractProof(
                    contract,
                    decodeNodeList(parts.get(1), name + ".accountProof"),
                    leftPad32(parts.get(2).asBytes(), name + ".arraySlot"),
                    () -> decodeStorageProofList(storageProofItem));
        }

        /** Converts the configuration form: field 7 of {@code ClprQbftLedgerConfigurationPayload}. */
        @NonNull
        static ValidatorContractProof of(@NonNull final QbftContractModeValidatorSource source) {
            final byte[] contract = source.validatorContractAddress().toByteArray();
            if (contract.length != ADDRESS_LENGTH) {
                throw ProofException.besuQbft("contract_mode_validator_source.validator_contract_address must be "
                        + ADDRESS_LENGTH + " bytes, got " + contract.length);
            }
            final byte[] arraySlot = source.arraySlot().toByteArray();
            if (arraySlot.length != 32) {
                throw ProofException.besuQbft(
                        "contract_mode_validator_source.array_slot must be 32 bytes, got " + arraySlot.length);
            }
            final List<SlotProof> storageProof = source.validatorContractStorageProofs().stream()
                    .map(e -> new SlotProof(
                            e.key().toByteArray(),
                            e.proof().stream().map(Bytes::toByteArray).toArray(byte[][]::new)))
                    .toList();
            return new ValidatorContractProof(
                    contract,
                    source.validatorContractAccountProof().stream()
                            .map(Bytes::toByteArray)
                            .toArray(byte[][]::new),
                    arraySlot,
                    () -> storageProof);
        }
    }

    /**
     * Requires {@code headerItem} to be an RLP list with at least {@value #MIN_HEADER_FIELDS} fields, so
     * that every field the verifier reads exists. No maximum: new forks append fields.
     */
    private static void requireHeader(@NonNull final Rlp.Item headerItem, @NonNull final String name) {
        if (!headerItem.isList() || headerItem.children().size() < MIN_HEADER_FIELDS) {
            throw ProofException.besuQbft(name + " is not an RLP list of at least " + MIN_HEADER_FIELDS + " fields");
        }
    }

    /** Reads a header's block number (field 8), which must fit in 8 bytes. */
    private static long blockNumber(@NonNull final Rlp.Item headerItem, @NonNull final String name) {
        final byte[] blockNumberBytes =
                headerItem.children().get(HEADER_BLOCK_NUMBER_INDEX).asBytes();
        if (blockNumberBytes.length > Long.BYTES) {
            throw ProofException.besuQbft(name + " block number exceeds 8 bytes");
        }
        final BigInteger blockNumber = new BigInteger(1, blockNumberBytes);
        if (blockNumber.bitLength() > Long.SIZE - 1) {
            throw ProofException.besuQbft(name + " block number does not fit in a signed 64-bit value");
        }
        return blockNumber.longValueExact();
    }

    /** A trust anchor id: the anchor block number as 8 big-endian bytes. */
    @NonNull
    static byte[] trustAnchorId(final long anchorBlockNumber) {
        return ByteBuffer.allocate(TRUST_ANCHOR_ID_LENGTH)
                .putLong(anchorBlockNumber)
                .array();
    }

    /**
     * Besu's on-chain block hash: keccak256 of the header with the extra-data round set to 0 and the
     * committed seals emptied ({@code BftBlockHashing.calculateHashOfBftBlockOnchain}). Unlike a hash of the
     * raw header bytes, it does not depend on which quorum of seals a node stored.
     */
    @NonNull
    static byte[] besuBlockHash(@NonNull final Rlp.Item headerItem) {
        final Rlp.Item extra;
        try {
            extra = Rlp.decodeOne(
                    headerItem.children().get(HEADER_EXTRA_DATA_INDEX).asBytes());
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("blockHeader.extraData is not valid RLP: " + e.getMessage());
        }
        if (!extra.isList() || extra.children().size() != QBFT_EXTRA_FIELDS) {
            throw ProofException.besuQbft(
                    "blockHeader.extraData is not a QBFT extra-data RLP list of " + QBFT_EXTRA_FIELDS + " fields");
        }
        final byte[] onchainExtraData = Rlp.encodeList(List.of(
                extra.children().get(0).rawBytes(), // vanity
                extra.children().get(1).rawBytes(), // validators[]
                extra.children().get(2).rawBytes(), // vote
                Rlp.encodeUint(0), // round = 0
                Rlp.encodeList(List.of()))); // committedSeals = []
        final List<Rlp.Item> headerChildren = headerItem.children();
        final List<byte[]> fields = new ArrayList<>(headerChildren.size());
        for (int i = 0; i < headerChildren.size(); i++) {
            fields.add(
                    i == HEADER_EXTRA_DATA_INDEX
                            ? Rlp.encodeBytes(onchainExtraData)
                            : headerChildren.get(i).rawBytes());
        }
        return MiscCryptoUtils.keccak256DigestOf(Rlp.encodeList(fields));
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
     * The QBFT quorum for a validator set of {@code n} members: {@code ceil(2n / 3)}, as in Besu's
     * {@code BftHelpers.calculateRequiredValidatorQuorum} and the QBFT formal specification.
     */
    static int quorum(final int n) {
        return (2 * n + 2) / 3;
    }

    /**
     * Verifies that the header's QBFT committed seals satisfy the quorum requirement for
     * {@code validatorSet}, as Besu's {@code BftCommitSealsValidationRule} does: at least
     * {@link #quorum(int)} seals, every recovered signer a member of the validator set, and no signer
     * more than once. Processing uses sorted lists throughout — no {@link java.util.Set} — so
     * behaviour is deterministic regardless of JVM.
     *
     * @throws ProofException if seals are absent, malformed, below quorum, contain an unknown
     *     signer, or contain a duplicate signer
     */
    static void verifyQbftSealAgainstValidatorSet(
            @NonNull final Rlp.Item headerItem, @NonNull final List<byte[]> validatorSet) {
        verifyCommitters(recoverCommitters(headerItem, validatorSet.size()), sortedCopy(validatorSet));
    }

    /**
     * Verifies a hop or the current block header against the trusted validator set and returns the
     * validator set the header lists (sorted).
     *
     * <p>Besu rules: a header is sealed by a quorum of the set it lists ({@code BftValidatorsValidationRule},
     * {@code BftCommitSealsValidationRule}), and the set changes by at most one member per block
     * ({@code VoteTally}). So the listed set may differ from the trusted set by at most one member, and the
     * seals are checked against the listed set.
     *
     * <p>CLPR light-client rules (not Besu behaviour): when the listed set differs from the trusted set, the
     * trusted set must still vouch for the change — at least {@code quorum(trusted) - 1} of the signers must
     * be trusted members, or the full {@code quorum(trusted)} when a newly added member did not seal. A hop
     * ({@code requireChange}) must change the set; only the current block may list the trusted set unchanged.
     *
     * @param headerItem the hop or current block header
     * @param trustedSet the validator set trusted before this header
     * @param requireChange {@code true} for a hop, {@code false} for the current block
     * @return the validator set the header lists, sorted
     * @throws ProofException if any rule is violated
     */
    @NonNull
    static List<byte[]> verifyHeaderAgainstTrustedSet(
            @NonNull final Rlp.Item headerItem, @NonNull final List<byte[]> trustedSet, final boolean requireChange) {
        final List<byte[]> trusted = sortedCopy(trustedSet);
        final List<byte[]> listed = extractValidators(headerItem);
        final List<byte[]> added = missingFrom(listed, trusted);
        final List<byte[]> removed = missingFrom(trusted, listed);
        final int changes = added.size() + removed.size();
        if (changes > 1) {
            throw ProofException.besuQbft("header changes " + changes
                    + " validators (added " + added.size() + ", removed " + removed.size()
                    + "); Besu applies at most one change per block");
        }
        if (requireChange && changes == 0) {
            throw ProofException.besuQbft("rotation hop does not change the validator set");
        }

        // Besu: the header is sealed by a quorum of the set it lists.
        final List<byte[]> committers = recoverCommitters(headerItem, listed.size());
        verifyCommitters(committers, listed);

        if (changes == 1) {
            // CLPR light-client rule: the trusted set vouches for the change.
            final boolean addedMemberSealed = !added.isEmpty()
                    && Collections.binarySearch(committers, added.get(0), Arrays::compareUnsigned) >= 0;
            final int trustedQuorum = quorum(trusted.size());
            // Math.max differs from the spec's q - 1 only for a 1-member trusted set, where the header's own
            // quorum already requires every listed member to seal.
            final int requiredTrustedSigners =
                    !added.isEmpty() && !addedMemberSealed ? trustedQuorum : Math.max(1, trustedQuorum - 1);
            final int trustedSigners =
                    committers.size() - missingFrom(committers, trusted).size();
            if (trustedSigners < requiredTrustedSigners) {
                throw ProofException.besuQbft("validator set change is sealed by " + trustedSigners
                        + " trusted validator" + (trustedSigners == 1 ? "" : "s") + ", expected at least "
                        + requiredTrustedSigners + " of " + trusted.size());
            }
        }
        return listed;
    }

    /**
     * Checks that every committer is a member of {@code sortedValidators} and that there are at least
     * {@link #quorum(int)} of them.
     */
    private static void verifyCommitters(
            @NonNull final List<byte[]> sortedCommitters, @NonNull final List<byte[]> sortedValidators) {
        if (sortedValidators.isEmpty()) {
            throw ProofException.besuQbft("validator set is empty");
        }
        for (final byte[] signer : sortedCommitters) {
            if (Collections.binarySearch(sortedValidators, signer, Arrays::compareUnsigned) < 0) {
                throw ProofException.besuQbft(
                        "committed seal recovers to 0x" + HEX.formatHex(signer) + " which is not in the validator set");
            }
        }
        final int n = sortedValidators.size();
        final int quorum = quorum(n);
        if (sortedCommitters.size() < quorum) {
            throw ProofException.besuQbft("expected at least " + quorum + " committed seal" + (quorum > 1 ? "s" : "")
                    + " for " + n + "-validator set, got " + sortedCommitters.size());
        }
        log.debug(
                "BesuQbftProofVerifier.verifyCommitters: verified {}/{} seals (quorum={})",
                sortedCommitters.size(),
                n,
                quorum);
    }

    /** Returns the members of {@code sortedA} that are not in {@code sortedB}. */
    @NonNull
    private static List<byte[]> missingFrom(@NonNull final List<byte[]> sortedA, @NonNull final List<byte[]> sortedB) {
        final List<byte[]> out = new ArrayList<>();
        for (final byte[] a : sortedA) {
            if (Collections.binarySearch(sortedB, a, Arrays::compareUnsigned) < 0) {
                out.add(a);
            }
        }
        return out;
    }

    /** Returns {@code true} when both lists hold the same addresses, in any order. */
    private static boolean sameValidators(@NonNull final List<byte[]> a, @NonNull final List<byte[]> b) {
        final List<byte[]> sortedA = sortedCopy(a);
        final List<byte[]> sortedB = sortedCopy(b);
        if (sortedA.size() != sortedB.size()) {
            return false;
        }
        for (int i = 0; i < sortedA.size(); i++) {
            if (!Arrays.equals(sortedA.get(i), sortedB.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Returns the distinct members of {@code addresses}, sorted. */
    @NonNull
    private static List<byte[]> distinctSorted(@NonNull final List<byte[]> addresses) {
        final List<byte[]> sorted = sortedCopy(addresses);
        final List<byte[]> out = new ArrayList<>(sorted.size());
        for (final byte[] address : sorted) {
            if (out.isEmpty() || !Arrays.equals(out.getLast(), address)) {
                out.add(address);
            }
        }
        return out;
    }

    @NonNull
    private static List<byte[]> sortedCopy(@NonNull final List<byte[]> addresses) {
        final List<byte[]> sorted = new ArrayList<>(addresses);
        sorted.sort(Arrays::compareUnsigned);
        return sorted;
    }

    /**
     * Recovers the signers of the header's committed seals, sorted. A duplicate seal is rejected, as in
     * Besu. Fails fast, before any signature recovery, when the header carries fewer seals than the quorum
     * of a {@code validatorCount}-member set, or more seals than it has members.
     */
    @NonNull
    private static List<byte[]> recoverCommitters(@NonNull final Rlp.Item headerItem, final int validatorCount) {
        if (validatorCount == 0) {
            throw ProofException.besuQbft("validator set is empty");
        }
        final int quorum = quorum(validatorCount);

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
                    + " for " + validatorCount + "-validator set, got " + committedSeals.size());
        }
        // More seals than validators means a duplicate or a non-member, both rejected below; reject before
        // recovering any signature, so the work stays bounded by the validator count.
        if (committedSeals.size() > validatorCount) {
            throw ProofException.besuQbft("header carries " + committedSeals.size() + " committed seals for a "
                    + validatorCount + "-validator set");
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

        // 4. Walk the sorted list: adjacent equal signers are duplicate seals.
        for (int i = 1; i < recoveredSigners.size(); i++) {
            if (Arrays.equals(recoveredSigners.get(i), recoveredSigners.get(i - 1))) {
                throw ProofException.besuQbft(
                        "duplicate committed seal from 0x" + HEX.formatHex(recoveredSigners.get(i)));
            }
        }
        return recoveredSigners;
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
        return MiscCryptoUtils.keccak256DigestOf(Rlp.encodeList(sealingHeaderFields));
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
        // Recover through Besu's SECP256K1 abstraction, as the EVM's ecrecover does: native libsecp256k1 when
        // available, otherwise its pure-Java implementation, so every node reaches the same result.
        final SECPSignature signature;
        try {
            signature = SECP256K1_ALGORITHM.createSignature(
                    new BigInteger(1, Arrays.copyOfRange(seal65, 0, 32)),
                    new BigInteger(1, Arrays.copyOfRange(seal65, 32, 64)),
                    (byte) v);
        } catch (final IllegalArgumentException e) {
            throw ProofException.besuQbft("failed to parse committed seal signature: " + e.getMessage());
        }
        // The native implementation throws instead of returning empty when no key can be recovered.
        final Optional<SECPPublicKey> recovered;
        try {
            recovered = SECP256K1_ALGORITHM.recoverPublicKeyFromSignature(Bytes32.wrap(msgHash32), signature);
        } catch (final RuntimeException e) {
            throw ProofException.besuQbft("ecRecover failed for committed seal: " + e.getMessage());
        }
        final SECPPublicKey publicKey =
                recovered.orElseThrow(() -> ProofException.besuQbft("ecRecover failed for committed seal"));
        // An Ethereum address is the last 20 bytes of keccak256 of the 64-byte uncompressed public key.
        final byte[] publicKeyHash = MiscCryptoUtils.keccak256DigestOf(publicKey.getEncoded());
        return Arrays.copyOfRange(publicKeyHash, publicKeyHash.length - ADDRESS_LENGTH, publicKeyHash.length);
    }

    // -----------------------------------------------------------------------------------
    // Queue metadata decoding
    // -----------------------------------------------------------------------------------

    /**
     * Reorders the proven storage slots into the queue-metadata positional layout by <em>exact</em>
     * slot-key matching against the canonical SC-189 Channel storage keys derived from
     * {@code channelId32}, so only slots of the channel being verified are accepted.
     * The {@code _channels} mapping base is {@code keccak256(channelId(32) || uint256(15))}; the
     * four queue-metadata fields sit at offsets {@code {1,2,4,5}}, {@code endpointManifestVersion} at
     * {@code 16} (carried through from proven state). Any proven key matching none of the five is taken
     * to be the last message's running-hash slot; a second such leftover, or a missing required queue
     * slot, is a hard error (fail-safe — the verifier never silently mis-decodes).
     *
     * @return a 5-slot array, or a 6-slot array with the last message's running hash appended, together with
     *     the key of that 6th slot (empty when there is none)
     */
    @NonNull
    private static MatchedSlots matchQueueSlotsByConnId(
            @NonNull final byte[][] slotKeys,
            @NonNull final byte[][] provenSlotValues,
            @NonNull final byte[] channelId32) {
        final int n = slotKeys.length; // CHANNEL_STORAGE_SLOTS, or +1 with the last message's running hash
        final boolean hasMessage = n == CHANNEL_STORAGE_SLOTS + 1;
        final byte[] base = MiscCryptoUtils.keccak256DigestOf(concat(channelId32, uint256Bytes(CHANNELS_MAPPING_SLOT)));
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
                // endpointManifestVersion (offset 16) — carried from PROVEN state (spec §4.5), never
                // from relayed content, which a relay could inflate to suppress our manifest re-pushes.
                out[SP_INDEX_CHANNEL_ENDPOINT_MANIFEST_VERSION] = provenSlotValues[i];
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
                || out[SP_INDEX_CHANNEL_RECEIVED_RUNNING_HASH] == null
                || out[SP_INDEX_CHANNEL_ENDPOINT_MANIFEST_VERSION] == null) {
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
            return new MatchedSlots(out, slotKeys[messageHashIdx]);
        } else if (messageHashIdx >= 0) {
            throw ProofException.besuQbft("5-slot bundle proves a non-Channel storage slot 0x"
                    + HEX.formatHex(slotKeys[messageHashIdx]) + " for channelId 0x"
                    + HEX.formatHex(channelId32));
        }
        return new MatchedSlots(out, new byte[0]);
    }

    /** The proven Channel slot values in decoding order, and the key of the optional 6th slot (empty if none). */
    private record MatchedSlots(
            @NonNull byte[][] values, @NonNull byte[] lastMessageSlotKey) {}

    /**
     * Finds the message whose running-hash slot is {@code lastMessageSlotKey32}: the last message L of a bundle
     * of {@code messageCount} messages. The bundle's messages are not yet acknowledged by the sender, so L lies in
     * {@code [ackedMessageId + messageCount, nextMessageId - 1]}; the search is bounded by the sender's proven
     * queue and hits on its first probe when the bundle starts at {@code ackedMessageId + 1}. Mirrors the Solidity
     * {@code ClprEvmBundleVerifier._findLastMessageId}.
     *
     * @param channelId32 the channel the slot must belong to
     * @param lastMessageSlotKey32 the proven 6th slot's key
     * @param ackedMessageId the sender's proven {@code ackedMessageId}
     * @param nextMessageId the sender's proven {@code nextMessageId}
     * @param messageCount the number of messages in the bundle
     * @return L, or empty when no message in the range has that slot
     */
    @NonNull
    public static OptionalLong findLastMessageId(
            @NonNull final byte[] channelId32,
            @NonNull final byte[] lastMessageSlotKey32,
            final long ackedMessageId,
            final long nextMessageId,
            final int messageCount) {
        Objects.requireNonNull(channelId32, "channelId32");
        Objects.requireNonNull(lastMessageSlotKey32, "lastMessageSlotKey32");
        if (messageCount <= 0 || ackedMessageId < 0 || lastMessageSlotKey32.length != 32) {
            return OptionalLong.empty();
        }
        final byte[] queueBase =
                MiscCryptoUtils.keccak256DigestOf(concat(channelId32, uint256Bytes(MESSAGE_QUEUES_MAPPING_SLOT)));
        for (long id = ackedMessageId + messageCount; id > 0 && id < nextMessageId; id++) {
            final byte[] messageBase = MiscCryptoUtils.keccak256DigestOf(concat(uint256Bytes(id), queueBase));
            if (Arrays.equals(addToBytes32(messageBase, MESSAGE_OFFSET_RUNNING_HASH), lastMessageSlotKey32)) {
                return OptionalLong.of(id);
            }
        }
        return OptionalLong.empty();
    }

    /** {@code value} as a 32-byte big-endian ABI word. */
    @NonNull
    private static byte[] uint256Bytes(final long value) {
        final byte[] out = new byte[32];
        ByteBuffer.wrap(out, 24, Long.BYTES).putLong(value);
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
     * Decode a {@link QueueMetadata} from the 5 or 6 storage-slot values produced by
     * {@link #matchQueueSlotsByConnId}, in this positional order:
     * <ol>
     *   <li>index 0 — packed slot: {@code verifier(20) | status(1) | nextMessageId(8)}.</li>
     *   <li>index 1 — packed slot: {@code ackedMessageId(8) | receivedMessageId(8) | nextExpectedReplyId(8)}.</li>
     *   <li>index 2 — {@code sentRunningHash} (bytes32, full slot).</li>
     *   <li>index 3 — {@code receivedRunningHash} (bytes32, full slot).</li>
     *   <li>index 4 — {@code endpointManifestVersion} (uint64, LSB-packed in its own slot).</li>
     *   <li>index 5 — last queued message's {@code runningHashAfterProcessing} (bytes32, full slot).
     *       <strong>Optional</strong> — present only when the bundle stops before {@code nextMessageId - 1}.</li>
     * </ol>
     *
     * <p>Solidity packs primitive fields starting at the LSB of a slot, so on a 32-byte
     * big-endian storage word the first-declared field sits at the right (high index of the
     * byte array). The unpacks below mirror that exactly.
     */
    @NonNull
    static QueueMetadata decodeQueueMetadata(@NonNull final byte[][] provenSlotValues) {
        return decodeQueueMetadata(provenSlotValues, new byte[0]);
    }

    /**
     * As {@link #decodeQueueMetadata(byte[][])}, also carrying the key of the optional 6th slot so the caller can
     * bind it to a message id ({@link #findLastMessageId}).
     */
    @NonNull
    static QueueMetadata decodeQueueMetadata(
            @NonNull final byte[][] provenSlotValues, @NonNull final byte[] lastMessageSlotKey) {
        Objects.requireNonNull(provenSlotValues, "provenSlotValues");
        Objects.requireNonNull(lastMessageSlotKey, "lastMessageSlotKey");
        if (provenSlotValues.length < STORAGE_PROOF_MIN_ENTRIES
                || provenSlotValues.length > STORAGE_PROOF_MAX_ENTRIES) {
            throw ProofException.besuQbft("expected 5 or 6 proven slot values, got " + provenSlotValues.length);
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
        final long ackedMessageId = readUint64BigEndian(receivedIdSlot, 24);

        final byte[] sentRunningHash =
                checkedCopy(provenSlotValues[SP_INDEX_CHANNEL_SENT_RUNNING_HASH], 32, "sentRunningHash");
        final byte[] receivedRunningHash =
                checkedCopy(provenSlotValues[SP_INDEX_CHANNEL_RECEIVED_RUNNING_HASH], 32, "receivedRunningHash");

        // Slot 4: endpointManifestVersion (uint64) — a lone field in its own slot, LSB-packed (last 8 bytes).
        final byte[] manifestVersionSlot = checkedCopy(
                provenSlotValues[SP_INDEX_CHANNEL_ENDPOINT_MANIFEST_VERSION], 32, "endpointManifestVersionSlot");
        final long endpointManifestVersion = readUint64BigEndian(manifestVersionSlot, 24);

        // Slot 5 is optional — present only when the bundle stops before nextMessageId - 1.
        final byte[] lastMsgRunningHash = provenSlotValues.length > SP_INDEX_LAST_MSG_RUNNING_HASH
                ? checkedCopy(provenSlotValues[SP_INDEX_LAST_MSG_RUNNING_HASH], 32, "lastMessageRunningHash")
                : new byte[32];

        return new QueueMetadata(
                nextMessageId,
                sentRunningHash,
                receivedMessageId,
                receivedRunningHash,
                status,
                lastMsgRunningHash,
                endpointManifestVersion,
                ackedMessageId,
                lastMessageSlotKey);
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
     * @param blockHash32 Besu's on-chain hash of the proof's block header ({@link #besuBlockHash})
     * @param bundleContentBytes the verbatim protobuf-serialized {@code ClprBundleContent} bytes
     * @param queueMetadata the {@code ClprQueueMetadata} fields decoded from the storage slots
     * @param newTrustAnchor the RLP-encoded validator set at the current block (via
     *     {@link #encodeValidatorSet}) when the bundle crossed hops or the current block changed the
     *     set, otherwise {@code byte[0]}
     * @param newTrustAnchorId the current block number, 8 bytes big-endian ({@link #trustAnchorId}),
     *     in the same cases, otherwise {@code byte[0]}
     * @param newEndpointManifestBytes the verified endpoint-manifest preimage, or {@code byte[0]}
     * @param newValidatorStorageRoot32 contract mode only: the validator contract's storage root at the current
     *     block when a new anchor is returned, otherwise {@code byte[0]}
     */
    public record VerifiedBundle(
            @NonNull byte[] blockHash32,
            @NonNull byte[] bundleContentBytes,
            @NonNull QueueMetadata queueMetadata,
            @NonNull byte[] newTrustAnchor,
            @NonNull byte[] newTrustAnchorId,
            @NonNull byte[] newEndpointManifestBytes,
            @NonNull byte[] newValidatorStorageRoot32) {
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
            newValidatorStorageRoot32 = Objects.requireNonNull(newValidatorStorageRoot32, "newValidatorStorageRoot32")
                    .clone();
        }

        /** A bundle verified against a header-mode anchor (no validator contract storage root). */
        public VerifiedBundle(
                @NonNull final byte[] blockHash32,
                @NonNull final byte[] bundleContentBytes,
                @NonNull final QueueMetadata queueMetadata,
                @NonNull final byte[] newTrustAnchor,
                @NonNull final byte[] newTrustAnchorId,
                @NonNull final byte[] newEndpointManifestBytes) {
            this(
                    blockHash32,
                    bundleContentBytes,
                    queueMetadata,
                    newTrustAnchor,
                    newTrustAnchorId,
                    newEndpointManifestBytes,
                    new byte[0]);
        }

        @Override
        public byte[] newValidatorStorageRoot32() {
            return newValidatorStorageRoot32.clone();
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
     * @param endpointManifestVersion the peer Channel's {@code endpoint_manifest_version} (SC-189
     *     Channel offset 16) — the peer's cached view of our endpoint-manifest version; proven, so a
     *     relay cannot inflate it to suppress our manifest re-pushes (spec §4.5)
     * @param ackedMessageId the peer's {@code ackedMessageId}: the highest of its outbound messages this ledger
     *     has acknowledged; bounds the search for a partial bundle's last message
     * @param lastMessageSlotKey the key of the optional 6th proven slot (the running-hash slot of the bundle's
     *     last message), empty when the bundle ends at {@code nextMessageId - 1}
     */
    public record QueueMetadata(
            long nextMessageId,
            @NonNull byte[] sentRunningHash,
            long receivedMessageId,
            @NonNull byte[] receivedRunningHash,
            int status,
            @NonNull byte[] lastMessageRunningHash,
            long endpointManifestVersion,
            long ackedMessageId,
            @NonNull byte[] lastMessageSlotKey) {
        public QueueMetadata {
            sentRunningHash = checkedCopy(sentRunningHash, 32, "sentRunningHash");
            receivedRunningHash = checkedCopy(receivedRunningHash, 32, "receivedRunningHash");
            lastMessageRunningHash = checkedCopy(lastMessageRunningHash, 32, "lastMessageRunningHash");
            lastMessageSlotKey = Objects.requireNonNull(lastMessageSlotKey, "lastMessageSlotKey")
                    .clone();
        }

        /** Queue metadata of a bundle that ends at {@code nextMessageId - 1} (no 6th slot). */
        public QueueMetadata(
                final long nextMessageId,
                @NonNull final byte[] sentRunningHash,
                final long receivedMessageId,
                @NonNull final byte[] receivedRunningHash,
                final int status,
                @NonNull final byte[] lastMessageRunningHash,
                final long endpointManifestVersion) {
            this(
                    nextMessageId,
                    sentRunningHash,
                    receivedMessageId,
                    receivedRunningHash,
                    status,
                    lastMessageRunningHash,
                    endpointManifestVersion,
                    0L,
                    new byte[0]);
        }

        @Override
        public byte[] lastMessageSlotKey() {
            return lastMessageSlotKey.clone();
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
     * @param blockHash32 Besu's on-chain hash of the proven current block header ({@link #besuBlockHash})
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
     */
    public record Config(
            @Nullable byte[] expectedContractAddress20,
            @Nullable byte[] expectedContractCodeHash32) {
        public Config {
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

    /**
     * The native Besu QBFT trust anchor stored on the channel. Header mode (validators listed in block headers):
     * {@code RLP([0, validatorSet, serviceAddress, codeHash, anchorBlockNumber])}. Contract mode (validators
     * held by a validator contract): {@code RLP([1, validatorSet, serviceAddress, codeHash, anchorBlockNumber,
     * validatorContract, arraySlot, validatorCodeHash, validatorStorageRoot])}.
     *
     * <ul>
     *   <li>{@code mode} — one byte, {@link #HEADER_MODE} or {@link #CONTRACT_MODE}. A channel keeps one mode for
     *       its life.</li>
     *   <li>{@code validatorSet} — the RLP-encoded, sorted trusted validator set ({@link #encodeValidatorSet}).</li>
     *   <li>{@code serviceAddress}, {@code codeHash} — the peer CLPR service the state proofs must resolve to.</li>
     *   <li>{@code anchorBlockNumber} — 8 bytes, big-endian: the block at which the set was last confirmed.
     *       Every hop and the current block of a bundle must come after it (replay floor).</li>
     *   <li>contract mode only: the {@link ValidatorContract} the set is proven from.</li>
     * </ul>
     *
     * <p>The anchor id is the anchor block number, 8 bytes big-endian ({@link #id()}).
     *
     * @param validatorContract the validator contract, or {@code null} for a header-mode anchor
     */
    public record TrustAnchor(
            @NonNull byte[] validatorSetEncoded,
            @NonNull byte[] serviceAddress20,
            @NonNull byte[] codeHash32,
            long anchorBlockNumber,
            @Nullable ValidatorContract validatorContract) {
        /** Validators are listed in block headers (Besu header-based validator selection). */
        public static final byte HEADER_MODE = 0;

        /** Validators are held by a validator contract (Besu contract-based validator selection). */
        public static final byte CONTRACT_MODE = 1;

        private static final int HEADER_MODE_ITEM_COUNT = 5;
        private static final int CONTRACT_MODE_ITEM_COUNT = 9;

        public TrustAnchor {
            validatorSetEncoded = Objects.requireNonNull(validatorSetEncoded, "validatorSetEncoded")
                    .clone();
            serviceAddress20 = checkedCopy(serviceAddress20, ADDRESS_LENGTH, "serviceAddress20");
            codeHash32 = checkedCopy(codeHash32, 32, "codeHash32");
            if (anchorBlockNumber < 0) {
                throw new IllegalArgumentException("anchorBlockNumber must be >= 0, got " + anchorBlockNumber);
            }
        }

        /** A header-mode anchor. */
        public TrustAnchor(
                @NonNull final byte[] validatorSetEncoded,
                @NonNull final byte[] serviceAddress20,
                @NonNull final byte[] codeHash32,
                final long anchorBlockNumber) {
            this(validatorSetEncoded, serviceAddress20, codeHash32, anchorBlockNumber, null);
        }

        /** {@link #HEADER_MODE} or {@link #CONTRACT_MODE}. */
        public byte mode() {
            return validatorContract == null ? HEADER_MODE : CONTRACT_MODE;
        }

        /**
         * The anchor that succeeds this one at {@code anchorBlockNumber}, with the proven validator set and, in
         * contract mode, the validator contract's proven storage root. Everything else carries over.
         */
        @NonNull
        public TrustAnchor advance(
                @NonNull final byte[] newValidatorSetEncoded,
                final long newAnchorBlockNumber,
                @NonNull final byte[] newValidatorStorageRoot32) {
            return new TrustAnchor(
                    newValidatorSetEncoded,
                    serviceAddress20,
                    codeHash32,
                    newAnchorBlockNumber,
                    validatorContract == null ? null : validatorContract.withStorageRoot(newValidatorStorageRoot32));
        }

        /** Encodes this anchor as stored on the channel. */
        @NonNull
        public byte[] encode() {
            final List<byte[]> items = new ArrayList<>(List.of(
                    Rlp.encodeBytes(new byte[] {mode()}),
                    Rlp.encodeBytes(validatorSetEncoded),
                    Rlp.encodeBytes(serviceAddress20),
                    Rlp.encodeBytes(codeHash32),
                    Rlp.encodeBytes(trustAnchorId(anchorBlockNumber))));
            if (validatorContract != null) {
                items.add(Rlp.encodeBytes(validatorContract.address20()));
                items.add(Rlp.encodeBytes(validatorContract.arraySlot32()));
                items.add(Rlp.encodeBytes(validatorContract.codeHash32()));
                items.add(Rlp.encodeBytes(validatorContract.storageRoot32()));
            }
            return Rlp.encodeList(items);
        }

        /** The anchor id: the anchor block number, 8 bytes big-endian. */
        @NonNull
        public byte[] id() {
            return trustAnchorId(anchorBlockNumber);
        }

        /**
         * Decodes an anchor as stored on the channel.
         *
         * @throws ProofException if the bytes are not a well-formed header-mode or contract-mode anchor
         */
        @NonNull
        public static TrustAnchor decode(@NonNull final byte[] encoded) {
            final Rlp.Item item;
            try {
                item = Rlp.decodeOne(Objects.requireNonNull(encoded, "encoded"));
            } catch (final RuntimeException e) {
                throw ProofException.besuQbft("trust anchor is not valid RLP: " + e.getMessage());
            }
            if (!item.isList() || item.children().isEmpty()) {
                throw ProofException.besuQbft("trust anchor must be a non-empty RLP list");
            }
            final List<Rlp.Item> items = item.children();
            for (final Rlp.Item child : items) {
                if (child.isList()) {
                    throw ProofException.besuQbft("trust anchor items must be byte strings");
                }
            }
            final byte[] mode = items.get(0).asBytes();
            if (mode.length != 1 || (mode[0] != HEADER_MODE && mode[0] != CONTRACT_MODE)) {
                throw ProofException.besuQbft("trust anchor mode is neither header mode (" + HEADER_MODE
                        + ") nor contract mode (" + CONTRACT_MODE + ")");
            }
            final int expectedItems = mode[0] == HEADER_MODE ? HEADER_MODE_ITEM_COUNT : CONTRACT_MODE_ITEM_COUNT;
            if (items.size() != expectedItems) {
                throw ProofException.besuQbft("trust anchor of mode " + mode[0] + " must be an RLP list of "
                        + expectedItems + " items, got " + items.size());
            }
            final byte[] validatorSet = items.get(1).asBytes();
            decodeValidatorSet(validatorSet, "trustAnchor.validatorSet");
            final byte[] serviceAddress = items.get(2).asBytes();
            final byte[] codeHash = items.get(3).asBytes();
            final byte[] anchorId = items.get(4).asBytes();
            if (serviceAddress.length != ADDRESS_LENGTH
                    || codeHash.length != 32
                    || anchorId.length != TRUST_ANCHOR_ID_LENGTH) {
                throw ProofException.besuQbft(
                        "trust anchor has a malformed service address, code hash or block number");
            }
            final long anchorBlockNumber = ByteBuffer.wrap(anchorId).getLong();
            if (anchorBlockNumber < 0) {
                throw ProofException.besuQbft("trust anchor block number is negative");
            }
            ValidatorContract validatorContract = null;
            if (mode[0] == CONTRACT_MODE) {
                final byte[] contract = items.get(5).asBytes();
                final byte[] arraySlot = items.get(6).asBytes();
                final byte[] contractCodeHash = items.get(7).asBytes();
                final byte[] storageRoot = items.get(8).asBytes();
                if (contract.length != ADDRESS_LENGTH
                        || arraySlot.length != 32
                        || contractCodeHash.length != 32
                        || storageRoot.length != 32) {
                    throw ProofException.besuQbft("trust anchor has a malformed validator contract address, array"
                            + " slot, code hash or storage root");
                }
                validatorContract = new ValidatorContract(contract, arraySlot, contractCodeHash, storageRoot);
            }
            return new TrustAnchor(validatorSet, serviceAddress, codeHash, anchorBlockNumber, validatorContract);
        }

        @Override
        public byte[] validatorSetEncoded() {
            return validatorSetEncoded.clone();
        }

        @Override
        public byte[] serviceAddress20() {
            return serviceAddress20.clone();
        }

        @Override
        public byte[] codeHash32() {
            return codeHash32.clone();
        }
    }

    /**
     * The validator contract of a contract-mode trust anchor.
     *
     * <p>Deployment requirements on the peer network:
     * <ul>
     *   <li>{@code getValidators()} must return the array stored at {@code arraySlot} unchanged: the verifier reads
     *       the storage, not the function.</li>
     *   <li>A proxy validator contract is not supported: the pinned code hash is the proxy's, so an upgrade of the
     *       implementation would go unnoticed.</li>
     * </ul>
     *
     * @param address20 the validator contract's address
     * @param arraySlot32 the storage slot of its {@code address[]} validator array
     * @param codeHash32 its code hash, proven at configuration time and pinned for the channel's life
     * @param storageRoot32 its storage root at the anchor block; a bundle whose current block proves the same
     *     root may omit the array
     */
    public record ValidatorContract(
            @NonNull byte[] address20,
            @NonNull byte[] arraySlot32,
            @NonNull byte[] codeHash32,
            @NonNull byte[] storageRoot32) {
        public ValidatorContract {
            address20 = checkedCopy(address20, ADDRESS_LENGTH, "validatorContract.address20");
            arraySlot32 = checkedCopy(arraySlot32, 32, "validatorContract.arraySlot32");
            codeHash32 = checkedCopy(codeHash32, 32, "validatorContract.codeHash32");
            storageRoot32 = checkedCopy(storageRoot32, 32, "validatorContract.storageRoot32");
        }

        @NonNull
        ValidatorContract withStorageRoot(@NonNull final byte[] newStorageRoot32) {
            return new ValidatorContract(address20, arraySlot32, codeHash32, newStorageRoot32);
        }

        @Override
        public byte[] address20() {
            return address20.clone();
        }

        @Override
        public byte[] arraySlot32() {
            return arraySlot32.clone();
        }

        @Override
        public byte[] codeHash32() {
            return codeHash32.clone();
        }

        @Override
        public byte[] storageRoot32() {
            return storageRoot32.clone();
        }
    }

    // -----------------------------------------------------------------------------------
    // Internal types
    // -----------------------------------------------------------------------------------

    /** A storage slot key and the trie nodes proving its value (the bundle's RLP form of a storage proof). */
    private record SlotProof(@NonNull byte[] key, @NonNull byte[][] proofNodes) {}

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
                nodesByHash.put(new BytesKey(MiscCryptoUtils.keccak256DigestOf(node)), node.clone());
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
