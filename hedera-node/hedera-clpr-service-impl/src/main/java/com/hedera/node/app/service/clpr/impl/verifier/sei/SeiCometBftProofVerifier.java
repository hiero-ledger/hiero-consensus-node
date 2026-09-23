// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.endpointManifestCommitmentSlot;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.keccak256;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprSeiBundlePayload;
import com.hedera.hapi.node.state.clpr.ClprSeiLedgerConfigurationPayload;
import com.hedera.hapi.node.state.clpr.SeiBlockRef;
import com.hedera.hapi.node.state.clpr.SeiCommit;
import com.hedera.hapi.node.state.clpr.SeiCommitSig;
import com.hedera.hapi.node.state.clpr.SeiHeader;
import com.hedera.hapi.node.state.clpr.SeiSignedHeader;
import com.hedera.hapi.node.state.clpr.SeiStateProof;
import com.hedera.hapi.node.state.clpr.SeiStorageProofEntry;
import com.hedera.hapi.node.state.clpr.SeiTrustAnchor;
import com.hedera.hapi.node.state.clpr.SeiValidatorEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verifies Sei (CometBFT consensus) bundle proofs against a peer ledger's application state
 * and decodes self-describing Sei configuration payloads — the CometBFT analog of
 * {@code BesuQbftVerifier}.
 *
 * <p>Accepts the protobuf payloads defined in {@code clpr_sei_ledger_configuration_payload.proto}.
 * Verification chain (validated end to end against Sei testnet {@code atlantic-2}):
 * <ol>
 *   <li>The trusted validator set's simple-Merkle hash must equal the signed header's
 *       {@code validators_hash}.</li>
 *   <li>The header hash is recomputed from the 14 cdc-encoded fields and must equal the
 *       {@code BlockID} hash the commit signs.</li>
 *   <li>Every signature selected by the commit's signer bitset must be a valid Ed25519
 *       signature by that trusted validator over that validator's canonical precommit vote,
 *       and the signers' voting power must exceed 2/3 of the set's total.</li>
 *   <li>The ICS-23 multistore proof ties the {@code evm} store root to the header's
 *       {@code app_hash} (which, per the CometBFT app-hash lag, commits the state of
 *       {@code header.height - 1}).</li>
 *   <li>Each ICS-23 IAVL proof ties one contract storage slot — key layout
 *       {@code 0x03 || service address || slot}, per sei-chain's {@code x/evm} module — to the
 *       proven store root.</li>
 * </ol>
 *
 * <p>The trust anchor is a protobuf-serialized {@link SeiTrustAnchor}: chain id, height, the
 * full trusted validator set, and the CLPR service contract address. {@code verifyConfigPayload}
 * derives the initial anchor from the configuration payload; {@code verifyBundle} authenticates
 * against the current anchor and emits a successor anchor when the payload carries rotation
 * evidence.
 */
public final class SeiCometBftProofVerifier {

    private static final Logger log = LogManager.getLogger(SeiCometBftProofVerifier.class);
    private static final HexFormat HEX = HexFormat.of();

    /** The sei-chain module store holding EVM state. */
    private static final byte[] EXPECTED_STORE_KEY = "evm".getBytes(StandardCharsets.UTF_8);

    /** sei-chain {@code x/evm/types/keys.go} StateKeyPrefix for contract storage. */
    private static final byte EVM_STATE_KEY_PREFIX = 0x03;

    /** Length in bytes of an EVM address. */
    private static final int ADDRESS_LENGTH = 20;

    /** EVM storage key length: prefix byte + address + 32-byte slot. */
    private static final int EVM_STORAGE_KEY_LENGTH = 1 + ADDRESS_LENGTH + 32;

    /** EVM zero word used when a storage slot is proven absent from Sei's sparse store. */
    private static final byte[] EVM_ZERO_WORD = new byte[32];

    // ── Storage-proof layout shared with the QBFT bundle constructor ──
    // Empty-message bundles prove only the four Channel fields. Message-bearing bundles add
    // the last message's running-hash slot as a fifth entry.
    private static final int STORAGE_PROOF_CHANNEL_ENTRIES = 4;
    private static final int STORAGE_PROOF_WITH_MESSAGE_ENTRIES = 5;
    private static final int SP_INDEX_LAST_MSG_RUNNING_HASH = 0;

    /** Protocol ceiling matching the endpoint's default per-bundle rotation limit. */
    static final int MAX_PRIOR_VALIDATOR_SET_UPDATES = 10;

    /** Modulus for EVM uint256 storage slot arithmetic. */
    private static final BigInteger UINT256_MODULUS = BigInteger.ONE.shiftLeft(256);

    /** Field offsets inside the Solidity CLPR Channel struct. */
    private static final BigInteger CHANNEL_OFFSET_STATUS_AND_NEXT_MSG_ID = BigInteger.ONE;

    private static final BigInteger CHANNEL_OFFSET_RECEIVED_MSG_ID = BigInteger.TWO;
    private static final BigInteger CHANNEL_OFFSET_SENT_RUNNING_HASH = BigInteger.valueOf(4);
    private static final BigInteger CHANNEL_OFFSET_RECEIVED_RUNNING_HASH = BigInteger.valueOf(5);

    private SeiCometBftProofVerifier() {}

    /**
     * Decode a Sei config payload, returning the advertised {@link ClprLedgerConfiguration} with
     * its self-describing initial trust anchor filled in.
     *
     * @param configPayload protobuf-serialized {@code ClprSeiLedgerConfigurationPayload}
     * @return the configuration carrying the derived trust anchor
     * @throws ProofException if parsing fails or the payload is structurally invalid
     */
    @NonNull
    public static VerifiedConfig verifyConfigPayload(@NonNull final byte[] configPayload) {
        requireNonNull(configPayload, "configPayload");
        log.info("SeiCometBftProofVerifier.verifyConfigPayload ENTER: {} bytes", configPayload.length);

        final ClprSeiLedgerConfigurationPayload payload;
        try {
            // Spec §1: "Implementations MUST reject messages containing unrecognized fields."
            // Strict parse propagates to the nested ClprLedgerConfiguration.
            payload = ClprSeiLedgerConfigurationPayload.PROTOBUF.parseStrict(
                    Bytes.wrap(configPayload).toReadableSequentialData());
        } catch (final Exception e) {
            throw ProofException.sei(
                    "configPayload is not a valid ClprSeiLedgerConfigurationPayload: " + e.getMessage(), e);
        }
        final var validatorSet = required(payload.initialValidatorSet(), "initial_validator_set");
        final var ledgerConfiguration = required(payload.ledgerConfiguration(), "ledger_configuration");
        final long initialHeight = payload.initialValidatorSetHeight();
        if (initialHeight <= 0) {
            throw ProofException.sei("initial_validator_set_height must be positive, got " + initialHeight);
        }

        final var address = ledgerConfiguration.serviceAddress();
        if (address.length() != ADDRESS_LENGTH) {
            throw ProofException.sei("ClprLedgerConfiguration.service_address must be " + ADDRESS_LENGTH
                    + " bytes, got " + address.length());
        }
        final byte[] serviceAddress20 = address.toByteArray();
        final var anchorChainId = seiChainId(ledgerConfiguration.chainId());
        final byte[] validatorSetHash = SeiHashing.validatorSetHash(validatorSet);
        log.info(
                "SeiCometBftProofVerifier.verifyConfigPayload decoded: ledgerChainId={}, "
                        + "anchorChainId={}, serviceAddress=0x{}, validators={}, initialHeight={}",
                ledgerConfiguration.chainId(),
                anchorChainId,
                HEX.formatHex(serviceAddress20),
                validatorSet.validators().size(),
                initialHeight);

        final var anchor = SeiTrustAnchor.newBuilder()
                .chainId(anchorChainId)
                .height(initialHeight)
                .validatorSet(validatorSet)
                .serviceAddress(Bytes.wrap(serviceAddress20))
                .build();
        final Bytes anchorBytes = SeiTrustAnchor.PROTOBUF.toBytes(anchor);
        final Bytes anchorId = Bytes.wrap(trustAnchorId(validatorSetHash, initialHeight));
        final var ledgerCfg = ledgerConfiguration
                .copyBuilder()
                .initialTrustAnchor(anchorBytes)
                .initialTrustAnchorId(anchorId)
                .build();
        log.info(
                "SeiCometBftProofVerifier.verifyConfigPayload EXIT: SUCCESS chainId={} anchorChainId={} "
                        + "validators={} initialHeight={} initialTrustAnchor={} bytes initialTrustAnchorId=0x{}",
                ledgerCfg.chainId(),
                anchorChainId,
                validatorSet.validators().size(),
                initialHeight,
                anchorBytes.length(),
                anchorId.toHex());
        // No config-path manifest proof for Sei yet (see VerifiedConfig#endpointManifestBytes);
        // the V3 config selector seed-falls-back to a bring-up manifest in SeiVerifyConfigCall.
        return new VerifiedConfig(ledgerCfg, new byte[0]);
    }

    /**
     * Decode and verify a Sei bundle payload against the channel's current trust anchor.
     *
     * <p>The payload is processed in three stages (see {@code ClprSeiBundlePayload}):
     * <ol>
     *   <li><b>Prior validator-set updates.</b> Each {@code prior_validator_set_updates} entry is
     *       verified against the running trust anchor (validator-set hash, commit quorum, chain id,
     *       forward progress, and its {@code next_validator_set} hashing to the header's
     *       {@code next_validators_hash}) and then advances the running anchor to that set at
     *       {@code header.height + 1}.</li>
     *   <li><b>Bundle content.</b> When {@code state_proof} is present it is verified against the
     *       <i>post-update</i> validator set, yielding the proven queue metadata and the verbatim
     *       bundle-content bytes.</li>
     *   <li><b>Current rotation.</b> When the content header announces a validator-set change and
     *       {@code next_validator_set} is present, the anchor advances once more.</li>
     * </ol>
     *
     * <p>A trust-update-only bundle carries only {@code prior_validator_set_updates} (no
     * {@code state_proof}/{@code bundle_content}/{@code next_validator_set}); it returns the
     * advanced trust anchor with no queue metadata or bundle content.
     *
     * @param bundlePayload protobuf-serialized {@code ClprSeiBundlePayload}
     * @param trustAnchor protobuf-serialized {@code SeiTrustAnchor} from the channel
     * @throws ProofException if parsing fails or any verification step is violated
     */
    @NonNull
    public static VerifiedBundle verifyBundle(@NonNull final byte[] bundlePayload, @NonNull final byte[] trustAnchor) {
        requireNonNull(bundlePayload, "bundlePayload");
        requireNonNull(trustAnchor, "trustAnchor");
        log.debug(
                "SeiCometBftProofVerifier.verifyBundle ENTER: bundlePayload={} bytes, trustAnchor={} bytes",
                bundlePayload.length,
                trustAnchor.length);

        final SeiTrustAnchor anchor;
        try {
            anchor = SeiTrustAnchor.PROTOBUF.parse(Bytes.wrap(trustAnchor).toReadableSequentialData());
        } catch (final Exception e) {
            throw ProofException.sei("trustAnchor is not a valid SeiTrustAnchor: " + e.getMessage(), e);
        }
        final var anchorSet = required(anchor.validatorSet(), "trustAnchor.validator_set");
        final byte[] serviceAddress20 = anchor.serviceAddress().toByteArray();
        if (serviceAddress20.length != ADDRESS_LENGTH) {
            throw ProofException.sei(
                    "trustAnchor.service_address must be " + ADDRESS_LENGTH + " bytes, got " + serviceAddress20.length);
        }
        final String chainId = anchor.chainId();
        if (chainId.isEmpty()) {
            throw ProofException.sei("trustAnchor.chain_id is empty");
        }
        log.debug(
                "SeiCometBftProofVerifier.verifyBundle trustAnchor decoded: chainId={}, height={}, validators={}, serviceAddress=0x{}",
                chainId,
                anchor.height(),
                anchorSet.validators().size(),
                HEX.formatHex(serviceAddress20));

        final ClprSeiBundlePayload payload;
        try {
            // Spec §1: "Implementations MUST reject messages containing unrecognized fields."
            payload = ClprSeiBundlePayload.PROTOBUF.parseStrict(
                    Bytes.wrap(bundlePayload).toReadableSequentialData());
        } catch (final Exception e) {
            throw ProofException.sei("bundlePayload is not a valid ClprSeiBundlePayload: " + e.getMessage(), e);
        }
        final var stateProof = payload.stateProof();
        final var nextSet = payload.nextValidatorSet();
        final var priorUpdates = payload.priorValidatorSetUpdates();
        if (priorUpdates.size() > MAX_PRIOR_VALIDATOR_SET_UPDATES) {
            throw ProofException.sei("prior_validator_set_updates must contain at most "
                    + MAX_PRIOR_VALIDATOR_SET_UPDATES + " entries, got " + priorUpdates.size());
        }
        // Optional endpoint-manifest advance (spec §4.2 Step 1b): field 5 (storage proof) and field 6
        // (preimage) are present together or not at all.
        final boolean hasManifestProof = payload.hasManifestStorageProof();
        if (hasManifestProof != (payload.endpointManifest().length() > 0)) {
            throw ProofException.sei("manifest_storage_proof and endpoint_manifest must be present together");
        }
        log.debug(
                "SeiCometBftProofVerifier.verifyBundle payload decoded: stateProof={}, bundleContent={} bytes, priorValidatorSetUpdates={}, nextValidatorSetValidators={}",
                stateProof == null ? "absent" : "present",
                payload.bundleContent().length(),
                priorUpdates.size(),
                nextSet == null ? 0 : nextSet.validators().size());

        // ── Stage 1: apply the chain of prior validator-set updates, advancing the trust anchor ──
        SeiValidatorSet currentSet = anchorSet;
        long currentHeight = anchor.height();
        boolean rotated = false;
        for (int i = 0; i < priorUpdates.size(); i++) {
            final var update = priorUpdates.get(i);
            final var updateSignedHeader = required(update.signedHeader(), "prior_validator_set_updates[" + i + "]");
            final var updateNextSet =
                    required(update.nextValidatorSet(), "prior_validator_set_updates[" + i + "].next_validator_set");
            final ProvenHeader provenUpdate = verifySignedHeader(updateSignedHeader, currentSet);
            final SeiHeader updateHeader = provenUpdate.header();
            if (!chainId.equals(updateHeader.chainId())) {
                throw ProofException.sei("prior_validator_set_updates[" + i + "] header chain_id '"
                        + updateHeader.chainId() + "' does not match trust anchor chain_id '" + chainId + "'");
            }
            if (updateHeader.height() < currentHeight) {
                throw ProofException.sei("prior_validator_set_updates[" + i + "] header height " + updateHeader.height()
                        + " is older than trust anchor height " + currentHeight);
            }
            if (Arrays.equals(
                    updateHeader.validatorsHash().toByteArray(),
                    updateHeader.nextValidatorsHash().toByteArray())) {
                throw ProofException.sei("prior_validator_set_updates[" + i + "] does not change the validator set");
            }
            final byte[] updateNextSetHash = SeiHashing.validatorSetHash(updateNextSet);
            if (!Arrays.equals(
                    updateNextSetHash, updateHeader.nextValidatorsHash().toByteArray())) {
                log.warn(
                        "SeiCometBftProofVerifier.verifyBundle prior update[{}] rejected: computedNextValidatorSetHash=0x{}, headerNextValidatorsHash=0x{}",
                        i,
                        HEX.formatHex(updateNextSetHash),
                        updateHeader.nextValidatorsHash().toHex());
                throw ProofException.sei("prior_validator_set_updates[" + i
                        + "] next_validator_set hash does not match the signed header's next_validators_hash");
            }
            currentSet = updateNextSet;
            currentHeight = successorHeight(updateHeader.height(), "prior_validator_set_updates[" + i + "]");
            rotated = true;
            log.debug(
                    "SeiCometBftProofVerifier.verifyBundle prior update[{}] applied: newValidators={}, newHeight={}",
                    i,
                    currentSet.validators().size(),
                    currentHeight);
        }

        // ── Stage 2: verify bundle content (if present) against the post-update validator set ──
        byte[] headerHash32 = null;
        byte[] bundleContentBytes = null;
        QueueMetadata queueMetadata = null;
        byte[] newEndpointManifestBytes = new byte[0];
        if (stateProof != null) {
            final ProvenState proven = verifyStateProof(stateProof, currentSet, serviceAddress20);
            final SeiHeader contentHeader = proven.header();
            if (!chainId.equals(contentHeader.chainId())) {
                throw ProofException.sei("header chain_id '" + contentHeader.chainId()
                        + "' does not match trust anchor chain_id '" + chainId + "'");
            }
            if (contentHeader.height() < currentHeight) {
                throw ProofException.sei("header height " + contentHeader.height()
                        + " is older than trust anchor height " + currentHeight);
            }
            headerHash32 = proven.headerHash32();

            // Manifest-only recovery bundle (spec §8.1.4): a state_proof (to establish the store root)
            // with EMPTY bundle_content and a slot-18 manifest proof but NO queue storage slots. It
            // authenticates the peer chain and proves the endpoint manifest while carrying no queue
            // state — the store root comes from the multistore commitment, not the per-slot proofs, so
            // verifyStateProof tolerates zero queue slots. bundleContentBytes/queueMetadata stay null;
            // the precompile Call turns this into a V3 manifestOnlySuccess (nextMessageId == 0 sentinel).
            // NOTE: this wire shape is not yet produced by the clpr-evm-endpoint relay (a cross-repo
            // follow-up); today it is exercised only by the synthetic fixtures in the unit tests.
            final boolean manifestOnly = payload.bundleContent().length() == 0 && hasManifestProof;
            if (manifestOnly) {
                newEndpointManifestBytes = verifyEndpointManifestProof(
                        payload.manifestStorageProof(),
                        payload.endpointManifest().toByteArray(),
                        proven.storeRoot(),
                        serviceAddress20);
            } else {
                bundleContentBytes = payload.bundleContent().toByteArray();
                queueMetadata = decodeQueueMetadata(proven.slotValues());

                // Endpoint-manifest advance (spec §4.2 Step 1b): a separate top-level ICS-23 proof of the
                // slot-18 commitment against the same proven store root as the queue slots.
                if (hasManifestProof) {
                    newEndpointManifestBytes = verifyEndpointManifestProof(
                            payload.manifestStorageProof(),
                            payload.endpointManifest().toByteArray(),
                            proven.storeRoot(),
                            serviceAddress20);
                }
            }

            // Endpoint-manifest advance (spec §4.2 Step 1b): a separate top-level ICS-23 proof of the
            // slot-18 commitment against the same proven store root as the queue slots.
            if (hasManifestProof) {
                newEndpointManifestBytes = verifyEndpointManifestProof(
                        payload.manifestStorageProof(),
                        payload.endpointManifest().toByteArray(),
                        proven.storeRoot(),
                        serviceAddress20);
            }

            // ── Stage 3: current rotation from the content header ──
            final byte[] currentSetHash = SeiHashing.validatorSetHash(currentSet);
            final byte[] headerNextHash = contentHeader.nextValidatorsHash().toByteArray();
            final boolean rotationAnnounced = !Arrays.equals(currentSetHash, headerNextHash);
            if (rotationAnnounced) {
                if (nextSet == null) {
                    log.warn(
                            "SeiCometBftProofVerifier.verifyBundle rotation rejected: headerNextValidatorsHash=0x{} differs from currentValidatorSetHash=0x{}, but next_validator_set is missing",
                            contentHeader.nextValidatorsHash().toHex(),
                            HEX.formatHex(currentSetHash));
                    throw ProofException.sei(
                            "signed header advertises validator-set rotation but next_validator_set is missing");
                }
                final byte[] nextSetHash = SeiHashing.validatorSetHash(nextSet);
                if (!Arrays.equals(nextSetHash, headerNextHash)) {
                    log.warn(
                            "SeiCometBftProofVerifier.verifyBundle rotation rejected: computedNextValidatorSetHash=0x{}, headerNextValidatorsHash=0x{}",
                            HEX.formatHex(nextSetHash),
                            contentHeader.nextValidatorsHash().toHex());
                    throw ProofException.sei(
                            "next_validator_set hash does not match the signed header's next_validators_hash");
                }
                currentSet = nextSet;
                currentHeight = successorHeight(contentHeader.height(), "state_proof signed header");
                rotated = true;
            } else if (nextSet != null) {
                throw ProofException.sei(
                        "next_validator_set is present but the signed header does not change the validator set");
            }
        } else {
            // Trust-update-only bundle: nothing but prior updates may be present, and they must
            // have made progress (otherwise the bundle proves nothing).
            if (payload.bundleContent().length() != 0) {
                throw ProofException.sei("bundle_content must be absent when state_proof is absent");
            }
            if (nextSet != null) {
                throw ProofException.sei("next_validator_set must be absent when state_proof is absent");
            }
            if (hasManifestProof) {
                throw ProofException.sei("manifest_storage_proof requires state_proof (nothing to prove against)");
            }
            if (!rotated) {
                throw ProofException.sei("bundle carries neither state_proof nor prior_validator_set_updates");
            }
        }

        // ── Emit the advanced trust anchor when any rotation occurred ──
        byte[] newTrustAnchor = null;
        byte[] newTrustAnchorId = null;
        if (rotated) {
            final var successor = SeiTrustAnchor.newBuilder()
                    .chainId(chainId)
                    .height(currentHeight)
                    .validatorSet(currentSet)
                    .serviceAddress(Bytes.wrap(serviceAddress20))
                    .build();
            newTrustAnchor = SeiTrustAnchor.PROTOBUF.toBytes(successor).toByteArray();
            newTrustAnchorId = trustAnchorId(SeiHashing.validatorSetHash(currentSet), currentHeight);
            log.debug(
                    "SeiCometBftProofVerifier.verifyBundle: trust anchor advanced, successorValidators={}, successorHeight={}, newTrustAnchor={} bytes, newTrustAnchorId=0x{}",
                    currentSet.validators().size(),
                    currentHeight,
                    newTrustAnchor.length,
                    HEX.formatHex(newTrustAnchorId));
        }

        log.debug(
                "SeiCometBftProofVerifier.verifyBundle EXIT: SUCCESS blockHash={} bundleContent={} rotated={}",
                headerHash32 == null ? "none" : "0x" + HEX.formatHex(headerHash32),
                bundleContentBytes == null ? "none" : bundleContentBytes.length + " bytes",
                rotated);
        return new VerifiedBundle(
                headerHash32,
                bundleContentBytes,
                queueMetadata,
                newTrustAnchor,
                newTrustAnchorId,
                newEndpointManifestBytes);
    }

    /**
     * Concatenates {@code validator_set_hash (32) || height (8, big-endian)} to form the opaque
     * 40-byte trust-anchor id stored as {@code trust_anchor_id}.
     */
    @NonNull
    static byte[] trustAnchorId(@NonNull final byte[] validatorSetHash, final long height) {
        final byte[] id = new byte[validatorSetHash.length + Long.BYTES];
        System.arraycopy(validatorSetHash, 0, id, 0, validatorSetHash.length);
        ByteBuffer.wrap(id, validatorSetHash.length, Long.BYTES).putLong(height);
        return id;
    }

    // -----------------------------------------------------------------------------------
    // Shared verification core
    // -----------------------------------------------------------------------------------

    record ProvenState(
            @NonNull SeiHeader header,
            @NonNull byte[] headerHash32,
            @NonNull byte[][] slotValues,
            @NonNull byte[] storeRoot) {}

    record ProvenHeader(@NonNull SeiHeader header, @NonNull byte[] headerHash32) {}

    /**
     * Authenticates a signed header against a trusted validator set: the set's simple-Merkle hash
     * must equal the header's {@code validators_hash}, the recomputed header hash must be the
     * {@code BlockID} hash the commit signs, and every selected commit signature must be a valid
     * Ed25519 signature carrying &gt;2/3 of the set's voting power. Shared by {@link #verifyStateProof}
     * (bundle content) and the {@code prior_validator_set_updates} rotation chain.
     */
    @NonNull
    static ProvenHeader verifySignedHeader(
            @NonNull final SeiSignedHeader signedHeader, @NonNull final SeiValidatorSet trustedSet) {
        final SeiHeader header = required(signedHeader.header(), "signed_header.header");
        final SeiCommit commit = required(signedHeader.commit(), "signed_header.commit");
        validateHeader(header);

        final List<SeiValidatorEntry> validators = trustedSet.validators();
        log.debug(
                "SeiCometBftProofVerifier.verifySignedHeader header: chainId={}, height={}, appHash=0x{}, validatorsHash=0x{}, nextValidatorsHash=0x{}, commitRound={}, signatures={}, signersBits={} bytes",
                header.chainId(),
                header.height(),
                header.appHash().toHex(),
                header.validatorsHash().toHex(),
                header.nextValidatorsHash().toHex(),
                commit.round(),
                commit.signatures().size(),
                commit.signersBits().length());

        // 1. The trusted set must be exactly the set this header names.
        final byte[] validatorSetHash = SeiHashing.validatorSetHash(trustedSet);
        if (!Arrays.equals(validatorSetHash, header.validatorsHash().toByteArray())) {
            log.warn(
                    "SeiCometBftProofVerifier.verifySignedHeader validator set hash mismatch: computed=0x{}, header=0x{}",
                    HEX.formatHex(validatorSetHash),
                    header.validatorsHash().toHex());
            throw ProofException.sei("trusted validator set hash does not match header validators_hash");
        }

        // 2. The recomputed header hash is the BlockID hash the validators signed.
        final byte[] headerHash32 = SeiHashing.headerHash(header);
        final var commitBlockId = SeiBlockRef.newBuilder()
                .hash(Bytes.wrap(headerHash32))
                .partSetTotal(commit.partSetTotal())
                .partSetHash(commit.partSetHash())
                .build();
        if (commitBlockId.partSetTotal() == 0 || commitBlockId.partSetHash().length() != 32) {
            throw ProofException.sei("commit part-set header must have positive total and 32-byte hash");
        }

        // 3. Verify the commit: >2/3 of trusted voting power, every selected signature valid.
        verifyCommit(commit, commitBlockId, header, validators);
        log.debug(
                "SeiCometBftProofVerifier.verifySignedHeader verified: blockHash=0x{}, validators={}",
                HEX.formatHex(headerHash32),
                validators.size());
        return new ProvenHeader(header, headerHash32);
    }

    /**
     * Runs the full verification chain for one state proof: validator-set hash, header hash,
     * commit signatures with quorum, multistore proof to {@code app_hash}, and one IAVL proof
     * per storage slot, with every slot key required to target the given service contract.
     */
    @NonNull
    static ProvenState verifyStateProof(
            @NonNull final SeiStateProof stateProof,
            @NonNull final SeiValidatorSet trustedSet,
            @NonNull final byte[] serviceAddress20) {
        final SeiSignedHeader signedHeader = required(stateProof.signedHeader(), "signed_header");
        final ProvenHeader provenHeader = verifySignedHeader(signedHeader, trustedSet);
        final SeiHeader header = provenHeader.header();
        final byte[] headerHash32 = provenHeader.headerHash32();
        log.debug(
                "SeiCometBftProofVerifier.verifyStateProof header authenticated: chainId={}, height={}, appHash=0x{}, storageProofs={}, serviceAddress=0x{}",
                header.chainId(),
                header.height(),
                header.appHash().toHex(),
                stateProof.storageProofs().size(),
                HEX.formatHex(serviceAddress20));

        // 4. Multistore (ics23:simple) proof: ("evm" -> store root) up to app_hash.
        final byte[] storeKey = stateProof.storeKey().toByteArray();
        if (!Arrays.equals(storeKey, EXPECTED_STORE_KEY)) {
            log.warn(
                    "SeiCometBftProofVerifier.verifyStateProof store key mismatch: expected=0x{}, actual=0x{}",
                    HEX.formatHex(EXPECTED_STORE_KEY),
                    HEX.formatHex(storeKey));
            throw ProofException.sei("store_key must be 'evm', got 0x" + HEX.formatHex(storeKey));
        }
        final var multistoreProof =
                SeiIcs23.parseCommitmentProof(stateProof.multistoreProof().toByteArray());
        if (!Arrays.equals(multistoreProof.key(), storeKey)) {
            log.warn(
                    "SeiCometBftProofVerifier.verifyStateProof multistore proof key mismatch: storeKey=0x{}, proofKey=0x{}",
                    HEX.formatHex(storeKey),
                    HEX.formatHex(multistoreProof.key()));
            throw ProofException.sei("multistore proof key does not match store_key");
        }
        final byte[] storeRoot = multistoreProof.value();
        SeiIcs23.verifyMembership(
                multistoreProof, SeiIcs23.TENDERMINT_SPEC, header.appHash().toByteArray(), storeKey, storeRoot);
        log.debug(
                "SeiCometBftProofVerifier.verifyStateProof multistore proof verified: storeKey={}, storeRoot=0x{}, appHash=0x{}",
                new String(storeKey, StandardCharsets.UTF_8),
                HEX.formatHex(storeRoot),
                header.appHash().toHex());

        // 5. One IAVL proof per storage slot, all scoped to the CLPR service contract.
        final List<SeiStorageProofEntry> entries = stateProof.storageProofs();
        final byte[][] slotValuesInProofOrder = new byte[entries.size()][];
        for (int i = 0; i < entries.size(); i++) {
            final SeiStorageProofEntry entry = entries.get(i);
            final byte[] key = entry.key().toByteArray();
            if (key.length != EVM_STORAGE_KEY_LENGTH
                    || key[0] != EVM_STATE_KEY_PREFIX
                    || !Arrays.equals(key, 1, 1 + ADDRESS_LENGTH, serviceAddress20, 0, ADDRESS_LENGTH)) {
                log.warn(
                        "SeiCometBftProofVerifier.verifyStateProof storage_proofs[{}] target mismatch: key=0x{}, expectedPrefix=0x{}{}",
                        i,
                        HEX.formatHex(key),
                        String.format("%02x", EVM_STATE_KEY_PREFIX),
                        HEX.formatHex(serviceAddress20));
                throw ProofException.sei("storage_proofs[" + i + "].key does not target the CLPR service contract"
                        + " (expected 0x03 || serviceAddress || slot)");
            }
            final byte[] value = entry.value().toByteArray();
            final var proof = SeiIcs23.parseAnyCommitmentProof(entry.iavlProof().toByteArray());
            final byte[] slotValue;
            if (proof.existence() != null) {
                if (value.length != 32) {
                    log.warn(
                            "SeiCometBftProofVerifier.verifyStateProof storage_proofs[{}] value length mismatch: expected=32, actual={}",
                            i,
                            value.length);
                    throw ProofException.sei("storage_proofs[" + i + "].value must be 32 bytes, got " + value.length);
                }
                SeiIcs23.verifyMembership(proof.existence(), SeiIcs23.IAVL_SPEC, storeRoot, key, value);
                slotValue = value;
            } else {
                if (value.length != 0) {
                    log.warn(
                            "SeiCometBftProofVerifier.verifyStateProof storage_proofs[{}] non-existence proof carries non-empty value: actual={}",
                            i,
                            value.length);
                    throw ProofException.sei(
                            "storage_proofs[" + i + "].nonexist value must be empty, got " + value.length);
                }
                SeiIcs23.verifyNonMembership(proof.nonExistence(), SeiIcs23.IAVL_SPEC, storeRoot, key);
                slotValue = EVM_ZERO_WORD.clone();
            }
            slotValuesInProofOrder[i] = slotValue;
            log.debug(
                    "SeiCometBftProofVerifier.verifyStateProof storageProof[{}] verified: slot=0x{}, value=0x{}, iavlProof={} bytes",
                    i,
                    HEX.formatHex(key, 1 + ADDRESS_LENGTH, key.length),
                    HEX.formatHex(slotValue),
                    entry.iavlProof().length());
        }
        final byte[][] slotValues = orderQueueSlotValuesByProofKey(entries, slotValuesInProofOrder);
        log.debug(
                "SeiCometBftProofVerifier.verifyStateProof EXIT: SUCCESS blockHash=0x{}, provenSlots={}",
                HEX.formatHex(headerHash32),
                slotValues.length);
        return new ProvenState(header, headerHash32, slotValues, storeRoot);
    }

    /**
     * Verifies the optional endpoint-manifest advance carried by a Sei bundle (spec §4.2 Step 1b) —
     * the CometBFT analog of the QBFT slot-18 manifest proof. {@code manifest_storage_proof} is a
     * single ICS-23 IAVL existence proof of the {@code _endpointManifest.commitment} slot (18) in the
     * peer's {@code evm} store, verified against the <b>same</b> {@code storeRoot} (hence the same
     * signed header / app-hash) as the queue-metadata slots. Kept out of {@code state_proof.storage_proofs}
     * so the fixed queue-slot ordering is unaffected. Binding + invariants mirror {@code BesuQbftVerifier}:
     * {@code keccak256(preimage)} must equal the proven commitment, the preimage strict-parses to a
     * {@link ClprEndpointManifest} with {@code version >= 1}, and its {@code service_address} must match
     * the channel's service address.
     *
     * @return the verified manifest preimage bytes (a defensive clone)
     * @throws ProofException if the proof, binding, or manifest invariants fail
     */
    @NonNull
    private static byte[] verifyEndpointManifestProof(
            @NonNull final SeiStorageProofEntry manifestStorageProof,
            @NonNull final byte[] manifestPreimage,
            @NonNull final byte[] storeRoot,
            @NonNull final byte[] serviceAddress20) {
        final byte[] key = manifestStorageProof.key().toByteArray();
        if (key.length != EVM_STORAGE_KEY_LENGTH
                || key[0] != EVM_STATE_KEY_PREFIX
                || !Arrays.equals(key, 1, 1 + ADDRESS_LENGTH, serviceAddress20, 0, ADDRESS_LENGTH)) {
            throw ProofException.sei("manifest_storage_proof.key does not target the CLPR service contract"
                    + " (expected 0x03 || serviceAddress || slot)");
        }
        if (!Arrays.equals(key, 1 + ADDRESS_LENGTH, key.length, endpointManifestCommitmentSlot(), 0, 32)) {
            throw ProofException.sei("manifest_storage_proof is not for the endpoint-manifest commitment slot (18)");
        }
        final byte[] value = manifestStorageProof.value().toByteArray();
        if (value.length != 32) {
            throw ProofException.sei("manifest_storage_proof.value must be 32 bytes, got " + value.length);
        }
        // Same IAVL-existence machinery as the queue slots, against the same proven store root.
        final var proof =
                SeiIcs23.parseCommitmentProof(manifestStorageProof.iavlProof().toByteArray());
        SeiIcs23.verifyMembership(proof, SeiIcs23.IAVL_SPEC, storeRoot, key, value);

        // Bind the supplied preimage to the proven commitment: commitment = keccak256(preimage).
        if (!Arrays.equals(keccak256(manifestPreimage), value)) {
            throw ProofException.sei("endpoint_manifest preimage does not match the proven commitment (slot 18)");
        }
        final ClprEndpointManifest manifest;
        try {
            // Spec §1: reject a manifest carrying unrecognized fields.
            manifest = ClprEndpointManifest.PROTOBUF.parseStrict(
                    Bytes.wrap(manifestPreimage).toReadableSequentialData());
        } catch (final Exception e) {
            throw ProofException.sei("endpoint_manifest is not a valid ClprEndpointManifest: " + e.getMessage(), e);
        }
        if (manifest.version() == 0L) {
            throw ProofException.sei("endpoint_manifest version is 0 (must be >= 1)");
        }
        if (!Arrays.equals(manifest.serviceAddress().toByteArray(), serviceAddress20)) {
            throw ProofException.sei("endpoint_manifest service_address does not match the channel service address");
        }
        log.debug(
                "SeiCometBftProofVerifier.verifyEndpointManifestProof verified: version={}, endpoints={}",
                manifest.version(),
                manifest.endpoints().size());
        return manifestPreimage.clone();
    }

    @NonNull
    private static byte[][] orderQueueSlotValuesByProofKey(
            @NonNull final List<SeiStorageProofEntry> entries, @NonNull final byte[][] valuesInProofOrder) {
        if (entries.size() != STORAGE_PROOF_CHANNEL_ENTRIES && entries.size() != STORAGE_PROOF_WITH_MESSAGE_ENTRIES) {
            return valuesInProofOrder;
        }

        final BigInteger[] slots = new BigInteger[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            slots[i] = storageSlotFromKey(entries.get(i).key().toByteArray());
            for (int j = 0; j < i; j++) {
                if (slots[i].equals(slots[j])) {
                    throw ProofException.sei("duplicate storage proof for slot 0x" + storageSlotHex(slots[i]));
                }
            }
        }

        int[] selected = null;
        BigInteger selectedBase = null;
        for (final BigInteger candidateStatusSlot : slots) {
            final BigInteger base = subtractUint256(candidateStatusSlot, CHANNEL_OFFSET_STATUS_AND_NEXT_MSG_ID);
            final int statusIndex = findSlot(slots, addUint256(base, CHANNEL_OFFSET_STATUS_AND_NEXT_MSG_ID));
            final int receivedIdIndex = findSlot(slots, addUint256(base, CHANNEL_OFFSET_RECEIVED_MSG_ID));
            final int sentRunningHashIndex = findSlot(slots, addUint256(base, CHANNEL_OFFSET_SENT_RUNNING_HASH));
            final int receivedRunningHashIndex =
                    findSlot(slots, addUint256(base, CHANNEL_OFFSET_RECEIVED_RUNNING_HASH));
            if (statusIndex < 0 || receivedIdIndex < 0 || sentRunningHashIndex < 0 || receivedRunningHashIndex < 0) {
                continue;
            }
            if (!distinct(statusIndex, receivedIdIndex, sentRunningHashIndex, receivedRunningHashIndex)) {
                continue;
            }
            final int[] candidate;
            if (entries.size() == STORAGE_PROOF_WITH_MESSAGE_ENTRIES) {
                int lastMessageRunningHashIndex = -1;
                for (int i = 0; i < slots.length; i++) {
                    if (i != statusIndex
                            && i != receivedIdIndex
                            && i != sentRunningHashIndex
                            && i != receivedRunningHashIndex) {
                        if (lastMessageRunningHashIndex >= 0) {
                            throw ProofException.sei("storage proofs contain more than one non-channel slot");
                        }
                        lastMessageRunningHashIndex = i;
                    }
                }
                if (lastMessageRunningHashIndex < 0) {
                    continue;
                }
                candidate = new int[] {
                    lastMessageRunningHashIndex,
                    statusIndex,
                    receivedIdIndex,
                    sentRunningHashIndex,
                    receivedRunningHashIndex
                };
            } else {
                candidate = new int[] {statusIndex, receivedIdIndex, sentRunningHashIndex, receivedRunningHashIndex};
            }
            if (selected != null && !Arrays.equals(selected, candidate)) {
                throw ProofException.sei("storage proofs contain ambiguous channel slot layout");
            }
            selected = candidate;
            selectedBase = base;
        }

        if (selected == null) {
            throw ProofException.sei("storage proofs do not contain the expected CLPR channel slot layout");
        }

        final byte[][] ordered = new byte[selected.length][];
        for (int i = 0; i < selected.length; i++) {
            ordered[i] = valuesInProofOrder[selected[i]];
        }
        log.debug(
                "SeiCometBftProofVerifier.verifyStateProof storage proofs mapped by key: channelBase=0x{}, proofOrder={}",
                storageSlotHex(selectedBase),
                Arrays.toString(selected));
        return ordered;
    }

    /**
     * Verifies the compact commit signatures against the trusted validators. Strict: an
     * invalid signer bitset, missing signature, or extra signature fails the whole proof
     * rather than just not counting, since an honest relay never forwards malformed commits.
     */
    private static void verifyCommit(
            @NonNull final SeiCommit commit,
            @NonNull final SeiBlockRef blockId,
            @NonNull final SeiHeader header,
            @NonNull final List<SeiValidatorEntry> validators) {
        long totalPower = 0;
        for (final SeiValidatorEntry validator : validators) {
            totalPower = addExact(totalPower, validator.votingPower());
        }

        final byte[] signersBits = commit.signersBits().toByteArray();
        final int expectedSignerBytes = (validators.size() + 7) / 8;
        log.debug(
                "SeiCometBftProofVerifier.verifyCommit ENTER: chainId={}, height={}, round={}, validators={}, signatures={}, signersBits={} bytes, totalPower={}",
                header.chainId(),
                header.height(),
                commit.round(),
                validators.size(),
                commit.signatures().size(),
                signersBits.length,
                totalPower);
        if (signersBits.length != expectedSignerBytes) {
            log.warn(
                    "SeiCometBftProofVerifier.verifyCommit signers_bits length mismatch: expected={} bytes, actual={} bytes, validators={}",
                    expectedSignerBytes,
                    signersBits.length,
                    validators.size());
            throw ProofException.sei("commit signers_bits must be " + expectedSignerBytes + " bytes for "
                    + validators.size() + " validators, got " + signersBits.length);
        }
        for (int bit = validators.size(); bit < signersBits.length * 8; bit++) {
            if (signerBitSet(signersBits, bit)) {
                log.warn(
                        "SeiCometBftProofVerifier.verifyCommit signers_bits has out-of-range bit set: bit={}, validators={}",
                        bit,
                        validators.size());
                throw ProofException.sei(
                        "commit signers_bits references validator index " + bit + " outside the trusted set");
            }
        }

        long signedPower = 0;
        int signatureIndex = 0;
        for (int validatorIndex = 0; validatorIndex < validators.size(); validatorIndex++) {
            if (!signerBitSet(signersBits, validatorIndex)) {
                continue;
            }
            if (signatureIndex >= commit.signatures().size()) {
                log.warn(
                        "SeiCometBftProofVerifier.verifyCommit missing signature: validatorIndex={}, signaturesProvided={}",
                        validatorIndex,
                        commit.signatures().size());
                throw ProofException.sei("commit signers_bits selects more validators than signatures provided");
            }
            final SeiCommitSig sig = commit.signatures().get(signatureIndex++);
            final SeiValidatorEntry validator = validators.get(validatorIndex);
            final var timestamp = sig.timestampOrElse(com.hedera.hapi.node.base.Timestamp.DEFAULT);
            if (timestamp.seconds() < 0 || timestamp.nanos() < 0 || timestamp.nanos() > 999_999_999) {
                throw ProofException.sei("commit sig[" + validatorIndex + "] has invalid timestamp: seconds="
                        + timestamp.seconds() + " nanos=" + timestamp.nanos());
            }
            final byte[] signBytes = SeiHashing.precommitSignBytes(
                    header.chainId(), header.height(), commit.round(), blockId, timestamp);
            if (!SeiHashing.verifyEd25519(
                    validator.ed25519PubKey().toByteArray(),
                    signBytes,
                    sig.signature().toByteArray())) {
                log.warn(
                        "SeiCometBftProofVerifier.verifyCommit invalid signature: validatorIndex={}, validatorAddress=0x{}, votingPower={}, signatureLength={}, signBytes={} bytes",
                        validatorIndex,
                        HEX.formatHex(SeiHashing.validatorAddress(
                                validator.ed25519PubKey().toByteArray())),
                        validator.votingPower(),
                        sig.signature().length(),
                        signBytes.length);
                throw ProofException.sei("invalid commit signature for validator index " + validatorIndex);
            }
            signedPower = addExact(signedPower, validator.votingPower());
        }
        if (signatureIndex != commit.signatures().size()) {
            log.warn(
                    "SeiCometBftProofVerifier.verifyCommit extra signatures: signaturesProvided={}, selectedValidators={}",
                    commit.signatures().size(),
                    signatureIndex);
            throw ProofException.sei("commit contains " + commit.signatures().size()
                    + " signatures but signers_bits selects " + signatureIndex + " validators");
        }
        // quorum: signedPower * 3 > totalPower * 2, in exact arithmetic
        if (multiplyExact(signedPower, 3) <= multiplyExact(totalPower, 2)) {
            log.warn(
                    "SeiCometBftProofVerifier.verifyCommit quorum not met: signedPower={}, totalPower={}, signaturesCounted={}",
                    signedPower,
                    totalPower,
                    signatureIndex);
            throw ProofException.sei(
                    "commit signed power " + signedPower + " does not exceed 2/3 of total power " + totalPower);
        }
        log.debug(
                "SeiCometBftProofVerifier.verifyCommit EXIT: SUCCESS signedPower={}, totalPower={}, signaturesCounted={}",
                signedPower,
                totalPower,
                signatureIndex);
    }

    private static boolean signerBitSet(@NonNull final byte[] bits, final int bitIndex) {
        return (bits[bitIndex / 8] & (0x80 >>> (bitIndex % 8))) != 0;
    }

    /**
     * Decodes queue metadata from four Channel slots and, for message-bearing bundles, a
     * fifth last-message running-hash slot. The Channel packing is identical to QBFT because
     * both ledgers use the same CLPR service contract.
     */
    @NonNull
    static QueueMetadata decodeQueueMetadata(@NonNull final byte[][] provenSlotValues) {
        final boolean hasLastMessageProof;
        final int channelOffset;
        final byte[] lastMsgRunningHash;
        if (provenSlotValues.length == STORAGE_PROOF_WITH_MESSAGE_ENTRIES) {
            hasLastMessageProof = true;
            channelOffset = 1;
            lastMsgRunningHash = provenSlotValues[SP_INDEX_LAST_MSG_RUNNING_HASH];
        } else if (provenSlotValues.length == STORAGE_PROOF_CHANNEL_ENTRIES) {
            hasLastMessageProof = false;
            channelOffset = 0;
            lastMsgRunningHash = EVM_ZERO_WORD.clone();
        } else {
            throw ProofException.sei("expected " + STORAGE_PROOF_CHANNEL_ENTRIES + " or "
                    + STORAGE_PROOF_WITH_MESSAGE_ENTRIES + " proven slot values, got " + provenSlotValues.length);
        }

        // Slot +1: verifier(20) | status(1) | nextMessageId(8) — first declared field at LSB.
        // Byte layout (MSB→LSB): 3B padding | 8B nextMessageId | 1B status | 20B verifier.
        final byte[] statusSlot = provenSlotValues[channelOffset];
        final long nextMessageId = readUint64BigEndian(statusSlot, 3);
        final int status = statusSlot[11] & 0xFF;

        // Slot +2: ackedMessageId(8) | receivedMessageId(8) | nextExpectedReplyId(8) — first at LSB.
        // Byte layout (MSB→LSB): 8B padding | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId.
        final byte[] receivedIdSlot = provenSlotValues[channelOffset + 1];
        final long receivedMessageId = readUint64BigEndian(receivedIdSlot, 16);

        return new QueueMetadata(
                nextMessageId,
                provenSlotValues[channelOffset + 2],
                receivedMessageId,
                provenSlotValues[channelOffset + 3],
                status,
                lastMsgRunningHash,
                hasLastMessageProof);
    }

    // -----------------------------------------------------------------------------------
    // Conversions and small helpers
    // -----------------------------------------------------------------------------------

    private static void validateHeader(@NonNull final SeiHeader header) {
        if (header.chainId().isEmpty()) {
            throw ProofException.sei("header chain_id is empty");
        }
        if (header.height() <= 0) {
            throw ProofException.sei("header height must be positive, got " + header.height());
        }
        if (header.validatorsHash().length() != 32
                || header.nextValidatorsHash().length() != 32
                || header.appHash().length() != 32) {
            throw ProofException.sei("header validators_hash, next_validators_hash, and app_hash must be 32 bytes");
        }
        final var time = header.time();
        if (time != null && (time.seconds() < 0 || time.nanos() < 0 || time.nanos() > 999_999_999)) {
            throw ProofException.sei(
                    "header time has invalid timestamp: seconds=" + time.seconds() + " nanos=" + time.nanos());
        }
    }

    @NonNull
    private static <T> T required(@Nullable final T value, @NonNull final String field) {
        if (value == null) {
            throw ProofException.sei(field + " is missing");
        }
        return value;
    }

    @NonNull
    private static String seiChainId(@NonNull final String ledgerChainId) {
        final var trimmed = requireNonNull(ledgerChainId, "ledgerChainId").trim();
        if (trimmed.isEmpty()) {
            throw ProofException.sei("ClprLedgerConfiguration.chain_id is empty");
        }
        final int separator = trimmed.indexOf(':');
        final var rawChainId = separator >= 0 ? trimmed.substring(separator + 1) : trimmed;
        if (rawChainId.isEmpty()) {
            throw ProofException.sei("ClprLedgerConfiguration.chain_id has empty Sei chain id: " + ledgerChainId);
        }
        return rawChainId;
    }

    private static long addExact(final long a, final long b) {
        try {
            return Math.addExact(a, b);
        } catch (final ArithmeticException e) {
            throw ProofException.sei("voting power overflow");
        }
    }

    private static long successorHeight(final long height, @NonNull final String field) {
        try {
            return Math.incrementExact(height);
        } catch (final ArithmeticException e) {
            throw ProofException.sei(field + " height cannot be incremented past " + Long.MAX_VALUE);
        }
    }

    private static long multiplyExact(final long a, final long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (final ArithmeticException e) {
            throw ProofException.sei("voting power overflow");
        }
    }

    @NonNull
    private static BigInteger storageSlotFromKey(@NonNull final byte[] key) {
        return new BigInteger(1, Arrays.copyOfRange(key, 1 + ADDRESS_LENGTH, EVM_STORAGE_KEY_LENGTH));
    }

    @NonNull
    private static BigInteger addUint256(@NonNull final BigInteger value, @NonNull final BigInteger increment) {
        return value.add(increment).mod(UINT256_MODULUS);
    }

    @NonNull
    private static BigInteger subtractUint256(@NonNull final BigInteger value, @NonNull final BigInteger decrement) {
        return value.subtract(decrement).mod(UINT256_MODULUS);
    }

    private static int findSlot(@NonNull final BigInteger[] slots, @NonNull final BigInteger expected) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].equals(expected)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean distinct(@NonNull final int... values) {
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                if (values[i] == values[j]) {
                    return false;
                }
            }
        }
        return true;
    }

    @NonNull
    private static String storageSlotHex(@NonNull final BigInteger slot) {
        final byte[] bytes = slot.toByteArray();
        final byte[] out = new byte[32];
        final int copyLen = Math.min(bytes.length, out.length);
        System.arraycopy(bytes, bytes.length - copyLen, out, out.length - copyLen, copyLen);
        return HEX.formatHex(out);
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
     * <p>For a normal bundle {@code blockHash32}, {@code bundleContentBytes} and
     * {@code queueMetadata} are all present. For a trust-update-only bundle (no {@code state_proof})
     * they are all {@code null} and only the advanced trust anchor is returned. For a manifest-only
     * recovery bundle (spec §8.1.4 — {@code state_proof} present but empty {@code bundle_content} and no
     * queue slots) {@code blockHash32} is set while {@code bundleContentBytes} and {@code queueMetadata}
     * are {@code null} and {@code newEndpointManifestBytes} carries the proven manifest preimage.
     *
     * @param blockHash32 the recomputed (and commit-authenticated) content header hash, or
     *     {@code null} for a trust-update-only bundle
     * @param bundleContentBytes the verbatim protobuf-serialized {@code ClprBundleContent} bytes, or
     *     {@code null} for a trust-update-only or manifest-only bundle
     * @param queueMetadata the queue-metadata fields decoded from the four Channel storage slots
     *     and optional last-message slot, or {@code null} for a trust-update-only or manifest-only bundle
     * @param newTrustAnchor protobuf-serialized successor {@code SeiTrustAnchor} when any rotation
     *     was verified (prior updates and/or the current rotation); {@code null} otherwise
     * @param newTrustAnchorId {@code validator_set_hash || height} of {@code newTrustAnchor};
     *     {@code null} iff it is
     * @param newEndpointManifestBytes protobuf {@code ClprEndpointManifest} preimage proven by the
     *     bundle's slot-18 manifest proof (spec §4.2 Step 1b); empty ({@code byte[0]}) when the bundle
     *     carried no manifest advance
     */
    public record VerifiedBundle(
            @Nullable byte[] blockHash32,
            @Nullable byte[] bundleContentBytes,
            @Nullable QueueMetadata queueMetadata,
            @Nullable byte[] newTrustAnchor,
            @Nullable byte[] newTrustAnchorId,
            @NonNull byte[] newEndpointManifestBytes) {}

    /**
     * The queue-metadata fields proven by the bundle's storage proofs; identical shape to the
     * QBFT verifier's record so downstream handling is verifier-agnostic.
     */
    public record QueueMetadata(
            long nextMessageId,
            @NonNull byte[] sentRunningHash,
            long receivedMessageId,
            @NonNull byte[] receivedRunningHash,
            int status,
            @NonNull byte[] lastMessageRunningHash,
            boolean hasLastMessageProof) {
        public QueueMetadata(
                final long nextMessageId,
                @NonNull final byte[] sentRunningHash,
                final long receivedMessageId,
                @NonNull final byte[] receivedRunningHash,
                final int status,
                @NonNull final byte[] lastMessageRunningHash) {
            this(
                    nextMessageId,
                    sentRunningHash,
                    receivedMessageId,
                    receivedRunningHash,
                    status,
                    lastMessageRunningHash,
                    true);
        }

        public QueueMetadata {
            requireNonNull(sentRunningHash, "sentRunningHash");
            requireNonNull(receivedRunningHash, "receivedRunningHash");
            requireNonNull(lastMessageRunningHash, "lastMessageRunningHash");
        }
    }

    /**
     * Result of a successful {@link #verifyConfigPayload} call.
     *
     * @param ledgerConfiguration the advertised configuration, with
     *     {@code initial_trust_anchor} set to the derived {@code SeiTrustAnchor} bytes and
     *     {@code initial_trust_anchor_id} to their SHA-256
     * @param endpointManifestBytes protobuf {@code ClprEndpointManifest} preimage proven by a
     *     config-path manifest proof; currently always empty for Sei (there is no config-path manifest
     *     proof producer yet — the real manifest advances via the bundle path, spec §4.2 Step 1b), so
     *     the V3 config selector uses a bring-up seed-fallback in {@code SeiVerifyConfigCall}
     */
    public record VerifiedConfig(
            @NonNull ClprLedgerConfiguration ledgerConfiguration,
            @NonNull byte[] endpointManifestBytes) {
        public VerifiedConfig {
            requireNonNull(ledgerConfiguration, "ledgerConfiguration");
            requireNonNull(endpointManifestBytes, "endpointManifestBytes");
        }
    }
}
