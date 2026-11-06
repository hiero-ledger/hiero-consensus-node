// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.endpointManifestCommitmentSlot;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.leftPad32;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ProvenQueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.CommitteeRotation;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.FinalizedExecution;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.MessageDelivery;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.NonSignerProof;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.SignedHeader;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.Ssz.SszMerkleBranch;
import com.hedera.node.app.service.clpr.impl.verifier.evm.ClprContractStorage;
import com.hedera.node.app.service.clpr.impl.verifier.evm.EvmAccount;
import com.hedera.node.app.service.clpr.impl.verifier.evm.RlpDecoder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verifies Ethereum proofs via the consensus-layer light client protocol (sync committees, BLS aggregate signatures),
 * for the Gloas fork (EIP-7732) onwards:
 *
 * <ul>
 *   <li>{@link #verifyBundle} verifies a bundle proof against a known trust anchor: it advances the trusted sync
 *       committee through the bundle's rotations, authenticates the delivery's attested beacon header through a
 *       sync-committee BLS aggregate signature, follows the finality branch to a finalized header, and extends that
 *       trust down to the CLPR contract's execution-layer state via the execution block header and Merkle-Patricia
 *       Trie proofs — see its javadoc for the verification steps.</li>
 *   <li>{@link #verifyConfigPayload} bootstraps the initial trust anchor from a light-client bootstrap and a signed
 *       update: it opens both committees against their beacon state roots, requires their aggregate keys to be valid
 *       BLS public keys, and checks that the current committee signed the update. The bootstrap header itself is not
 *       authenticated; the approving party is the trust gate — see its javadoc for the trust model.</li>
 * </ul>
 *
 * <p>This class is the orchestration layer. The wire formats are decoded by {@link PayloadDecoder} into typed object
 * graphs ({@link BundlePayload}, {@link PeerLedgerConfigPayload}) and the anchor by {@link TrustAnchor}; SSZ hashing
 * and Merkle-branch math live in {@link Ssz}; MPT proof and account decoding live in the shared {@code verifier.evm}
 * package. This class reads the decoded graph and performs the trust decisions (supermajority, non-signer membership,
 * BLS, branch and MPT verification).
 *
 * <p>The trust anchor holds the committees of two consecutive periods, {@code P} and {@code P + 1} (see
 * {@link TrustAnchor}). A delivery's signature slot selects which of them signed it. A rotation carries no signature
 * slot, so it is always signed by the committee of {@code P + 1}; it proves the committee of {@code P + 2}. The CLPR
 * service contract address and the channel id come from the {@code channelContext}, which the
 * CLPR service stored for the channel when it was completed, so the relay cannot choose which contract or channel the
 * storage proofs are for. The contract's code hash is pinned in the anchor, so the proven account must carry it.
 *
 * <p>Scope note: the anchor pins one fork version, so a hard fork that changes it requires a configuration update.
 */
public final class EthereumSyncCommitteeProofVerifier {

    private static final Logger log = LogManager.getLogger(EthereumSyncCommitteeProofVerifier.class);

    /**
     * Length of the channel id that opens {@code channelContext}, which the CLPR service stores as
     * {@code channelId (32) ‖ serviceAddress (20)}.
     */
    private static final int CHANNEL_ID_LENGTH = 32;

    /**
     * Length of the service contract address that closes {@code channelContext}.
     */
    private static final int SERVICE_ADDRESS_LENGTH = 20;

    /**
     * Index of {@code stateRoot} in an RLP-encoded EVM block header.
     */
    private static final int BLOCK_HEADER_INDEX_STATE_ROOT = 3;

    private final BlsSignatureVerifier blsVerifier;

    public EthereumSyncCommitteeProofVerifier(@NonNull final BlsSignatureVerifier blsVerifier) {
        this.blsVerifier = Objects.requireNonNull(blsVerifier, "blsVerifier");
    }

    /**
     * Decodes and verifies a bundle payload, returning the attested beacon block root, the (verbatim)
     * protobuf-serialized {@code ClprBundleContent} bytes, the proven queue metadata, the proven endpoint manifest
     * update, if any, and — when the payload carries rotations — the successor trust anchor.
     *
     * <p>Verification steps:
     * <ol>
     *   <li>For each rotation, in order: verify the attested header's signature under the anchor's next committee;
     *       verify the finality branch to a finalized header in the same period; verify the branch of the committee
     *       after it in the attested state root; then slide the anchor by one period.</li>
     *   <li>Verify the delivery's attested header signature under the committee of its signature slot's period, which
     *       must be one of the two the anchor trusts after the rotations, and its finality branch to a finalized
     *       header.</li>
     *   <li>Verify the execution block hash ({@code keccak256(blockHeaderRlp)}) against the finalized header's
     *       {@code bodyRoot} and read the execution state root from that block header.</li>
     *   <li>Verify the MPT account proof of the CLPR service contract against the execution state root, require its
     *       code hash to be the pinned one, verify the Channel slot proofs against its storage root, and decode the
     *       queue metadata.</li>
     *   <li>Verify the endpoint-manifest update, if the bundle carries one.</li>
     * </ol>
     *
     * @param bundlePayload  the RLP-encoded bundle payload {@code [ [rotation...], delivery ]}
     * @param trustAnchor    the {@link TrustAnchor} encoding
     * @param channelContext {@code channelId (32) ‖ serviceAddress (20)}
     * @return the verified bundle
     * @throws ProofException if decoding fails or any verification step is violated
     */
    @NonNull
    public VerifiedBundle verifyBundle(
            @NonNull final byte[] bundlePayload,
            @NonNull final byte[] trustAnchor,
            @NonNull final byte[] channelContext) {
        Objects.requireNonNull(bundlePayload, "bundlePayload");
        Objects.requireNonNull(trustAnchor, "trustAnchor");
        Objects.requireNonNull(channelContext, "channelContext");
        log.debug(
                "EthSyncCommitteeProofVerifier.verifyBundle ENTER: bundlePayload={} bytes, trustAnchor={} bytes",
                bundlePayload.length,
                trustAnchor.length);

        final TrustAnchor currentAnchor = TrustAnchor.decode(trustAnchor);
        validateChannelContext(channelContext);
        final byte[] channelId32 = Arrays.copyOf(channelContext, CHANNEL_ID_LENGTH);
        final byte[] serviceAddress20 = Arrays.copyOfRange(channelContext, CHANNEL_ID_LENGTH, channelContext.length);
        final BundlePayload bundle = PayloadDecoder.decodeBundle(bundlePayload, currentAnchor.preset());
        try {
            return verifyDecodedBundle(bundle, currentAnchor, channelId32, serviceAddress20);
        } catch (final ProofException e) {
            throw EthProofs.withContext(describe(bundle, currentAnchor), e);
        }
    }

    /**
     * Decodes and verifies a sync-committee config payload, returning the advertised ledger configuration with the
     * initial trust anchor.
     *
     * <p>The payload carries a light-client bootstrap and a signed update. The verifier opens the current committee
     * under the anchor header's state root, opens the next committee under the update header's state root, requires
     * both aggregate keys to be valid BLS public keys, and checks that the current committee signed the update header,
     * which lies in the anchor header's period. The anchor then trusts both committees from the anchor header's period
     * on, pins the service contract's code hash, and takes the preset from the committee size.
     *
     * <p>Trust model: the anchor header is not authenticated against Ethereum, so the committees vouch only for each
     * other. The party approving the configuration update must check that the anchor header, the preset,
     * {@code genesisValidatorsRoot}, {@code forkVersion} and the code hash match the intended Ethereum network and
     * contract. Steady-state authentication begins with the first bundle, whose sync-aggregate signature is verified
     * against these now-trusted committees.
     *
     * @throws ProofException if decoding fails or any check is violated
     */
    @NonNull
    public VerifiedConfig verifyConfigPayload(@NonNull final byte[] configPayload) {
        Objects.requireNonNull(configPayload, "configPayload");
        log.info("EthSyncCommitteeProofVerifier.verifyConfigPayload ENTER: {} bytes", configPayload.length);

        final PeerLedgerConfigPayload payload = PayloadDecoder.decodeConfig(configPayload);
        final SyncCommitteePreset preset = payload.preset();
        final BeaconHeader anchorHeader = payload.anchorHeader();
        if (payload.slot() != anchorHeader.slot()) {
            throw EthProofs.fail("configPayload slot " + Long.toUnsignedString(payload.slot())
                    + " is not the anchor header slot " + Long.toUnsignedString(anchorHeader.slot()));
        }
        final long period = preset.periodOf(anchorHeader.slot());
        requirePeriod(payload.updateHeader().slot(), period, preset, "update header slot");
        final SyncCommittee currentCommittee = payload.current().toSyncCommittee();
        verifyCommitteeBranch(
                currentCommittee,
                payload.currentBranch(),
                Gloas.CURRENT_SYNC_COMMITTEE,
                anchorHeader,
                "current sync committee");
        final SyncCommittee nextCommittee = payload.next().toSyncCommittee();
        verifyCommitteeBranch(
                nextCommittee,
                payload.nextBranch(),
                Gloas.NEXT_SYNC_COMMITTEE,
                payload.updateHeader(),
                "next sync committee");
        requireValidAggregatePubkey(currentCommittee.decompressAggregatePubkey(), "current sync committee");
        requireValidAggregatePubkey(nextCommittee.decompressAggregatePubkey(), "next sync committee");
        final TrustAnchor initialAnchor = new TrustAnchor(
                preset,
                payload.genesisValidatorsRoot32(),
                payload.forkVersion4(),
                period,
                currentCommittee,
                nextCommittee,
                payload.codeHash32());
        verifyUpdateSignature(payload, initialAnchor);

        final var ledgerCfg = payload.ledgerConfiguration()
                .copyBuilder()
                .initialTrustAnchor(Bytes.wrap(initialAnchor.encode()))
                .initialTrustAnchorId(Bytes.wrap(initialAnchor.id()))
                .build();
        // The anchor header is not authenticated (see the trust model above), so record what the approver accepted.
        log.info(
                "EthSyncCommitteeProofVerifier.verifyConfigPayload EXIT: SUCCESS chainId={} preset={} period={}"
                        + " anchorSlot={} anchorHeaderRoot=0x{} genesisValidatorsRoot=0x{} forkVersion=0x{}"
                        + " codeHash=0x{}",
                ledgerCfg::chainId,
                () -> preset,
                initialAnchor::period,
                () -> Long.toUnsignedString(anchorHeader.slot()),
                () -> HexFormat.of().formatHex(anchorHeader.hashTreeRoot()),
                () -> HexFormat.of().formatHex(initialAnchor.genesisValidatorsRoot32()),
                () -> HexFormat.of().formatHex(initialAnchor.forkVersion4()),
                () -> HexFormat.of().formatHex(initialAnchor.codeHash32()));
        return new VerifiedConfig(ledgerCfg, payload.slot());
    }

    private static void validateChannelContext(final byte[] channelContext) {
        if (channelContext.length != CHANNEL_ID_LENGTH + SERVICE_ADDRESS_LENGTH) {
            throw EthProofs.fail("channelContext must be " + (CHANNEL_ID_LENGTH + SERVICE_ADDRESS_LENGTH)
                    + " bytes (channelId ‖ serviceAddress), got " + channelContext.length);
        }
    }

    @NonNull
    private VerifiedBundle verifyDecodedBundle(
            @NonNull final BundlePayload bundle,
            @NonNull final TrustAnchor currentAnchor,
            @NonNull final byte[] channelId32,
            @NonNull final byte[] serviceAddress20) {
        // --- Advance the trusted committee through the rotations -----
        final TrustAnchor anchor = verifyRotations(bundle.rotations(), currentAnchor);

        // --- Authenticate the chain down to the CLPR contract account -----
        final MessageDelivery delivery = bundle.delivery();
        final BeaconHeader attestedHeader = delivery.signedHeader().attestedHeader();
        final SyncCommittee signer = verifySignatureSlot(delivery.signatureSlot(), attestedHeader, anchor);
        final byte[] beaconBlockRoot32 = verifySignedHeader(delivery.signedHeader(), signer, anchor);
        verifyFinality(delivery.finalizedExecution().finalizedHeader(), delivery.finalityBranch(), attestedHeader);
        final byte[] executionStateRoot32 = verifyExecutionStateRoot(delivery.finalizedExecution());
        final EvmAccount provenAccount = verifyAccountProof(
                executionStateRoot32, serviceAddress20, delivery.accountProof(), anchor.codeHash32());

        // --- Prove the queue metadata -----
        // A bundle with no queue storage proof simply has no queue metadata — the all-zero absent sentinel
        // (nextMessageId == 0). It is decoded and treated as any other bundle; the §8.1.4 invariant is enforced after
        // the manifest step below.
        final ProvenQueueMetadata queueMetadata;
        if (delivery.storageProof().isEmpty()) {
            log.debug("EthSyncCommitteeProofVerifier: no queue storage proof; queue metadata is absent");
            queueMetadata = ProvenQueueMetadata.absent();
        } else {
            queueMetadata = proveQueueMetadata(delivery.storageProof(), provenAccount, channelId32);
        }

        // --- Endpoint-manifest update (spec §4.9), if the bundle carries one -----
        // Verified against the SAME proven account storage root as the queue metadata. Null when absent.
        final ClprEndpointManifest newEndpointManifest = verifyEndpointManifestProof(
                delivery.manifestPreimage(), delivery.manifestStorageProof(), provenAccount, serviceAddress20);

        // A bundle must carry SOMETHING: queue metadata, message content, or a manifest advance. When both the
        // queue metadata and the content are absent, the bundle MUST carry a manifest advance (spec §8.1.4);
        // otherwise it is empty/meaningless and verification fails.
        if (delivery.storageProof().isEmpty() && delivery.bundleContent().length == 0 && newEndpointManifest == null) {
            throw EthProofs.fail("bundle has no queue metadata, no content, and no endpoint-manifest advance");
        }

        // --- Trust anchor rotation -----
        final boolean rotated = !bundle.rotations().isEmpty();
        log.debug(
                "EthSyncCommitteeProofVerifier.verifyBundle EXIT: SUCCESS beaconBlockRoot=0x{}, rotations={}, period={},"
                        + " signatureSlot={}, attestedSlot={}, finalizedSlot={}",
                () -> HexFormat.of().formatHex(beaconBlockRoot32),
                () -> bundle.rotations().size(),
                anchor::period,
                () -> Long.toUnsignedString(delivery.signatureSlot()),
                () -> Long.toUnsignedString(attestedHeader.slot()),
                () -> Long.toUnsignedString(
                        delivery.finalizedExecution().finalizedHeader().slot()));
        return VerifiedBundle.builder()
                .beaconBlockRoot32(beaconBlockRoot32)
                .bundleContentBytes(delivery.bundleContent())
                .queueMetadata(queueMetadata)
                .nextTrustAnchor(rotated ? anchor.encode() : null)
                .nextTrustAnchorId(rotated ? anchor.id() : null)
                .newEndpointManifest(newEndpointManifest)
                .build();
    }

    /**
     * Describes the decoded bundle for a rejection message: the anchor it was verified against and the delivery's
     * slots.
     */
    @NonNull
    private static String describe(@NonNull final BundlePayload bundle, @NonNull final TrustAnchor anchor) {
        final MessageDelivery delivery = bundle.delivery();
        return "bundle rejected (anchorPeriod=" + anchor.period()
                + ", preset=" + anchor.preset()
                + ", rotations=" + bundle.rotations().size()
                + ", signatureSlot=" + Long.toUnsignedString(delivery.signatureSlot())
                + ", attestedSlot="
                + Long.toUnsignedString(delivery.signedHeader().attestedHeader().slot())
                + ", finalizedSlot="
                + Long.toUnsignedString(
                        delivery.finalizedExecution().finalizedHeader().slot())
                + ")";
    }

    /**
     * Verifies the rotations in order, each under the anchor the previous one produced, and returns the anchor trusted
     * after the last one ({@code anchor} itself when there are none).
     *
     * @throws ProofException if any rotation fails to verify
     */
    @NonNull
    private TrustAnchor verifyRotations(
            @NonNull final List<CommitteeRotation> rotations, @NonNull final TrustAnchor anchor) {
        TrustAnchor trusted = anchor;
        for (final CommitteeRotation rotation : rotations) {
            trusted = verifyRotation(rotation, trusted);
        }
        return trusted;
    }

    /**
     * Verifies one committee rotation under {@code anchor}, which trusts periods {@code P} and {@code P + 1}, and
     * returns the anchor that trusts periods {@code P + 1} and {@code P + 2}.
     *
     * <p>The rotation is an update of period {@code P + 1}: its attested header lies in that period and is signed by
     * the anchor's next committee (see {@link CommitteeRotation}), and its beacon state proves the committee of
     * {@code P + 2}, whose aggregate key must be a valid BLS public key. Like the light-client protocol, a next committee is adopted only from an update whose finalized
     * header lies in the attested header's period, so the committee is taken from finalized state.
     *
     * @throws ProofException if any step is violated
     */
    @NonNull
    private TrustAnchor verifyRotation(@NonNull final CommitteeRotation rotation, @NonNull final TrustAnchor anchor) {
        final SyncCommitteePreset preset = anchor.preset();
        final long rotationPeriod = anchor.period() + 1;
        final BeaconHeader attestedHeader = rotation.signedHeader().attestedHeader();
        requirePeriod(attestedHeader.slot(), rotationPeriod, preset, "rotation attested header slot");
        verifySignedHeader(rotation.signedHeader(), anchor.next(), anchor);
        final BeaconHeader finalizedHeader = rotation.finalizedHeader();
        verifyFinality(finalizedHeader, rotation.finalityBranch(), attestedHeader);
        requirePeriod(finalizedHeader.slot(), rotationPeriod, preset, "rotation finalized header slot");

        // The beacon state commits to the committee's compressed aggregate; the wire carries it uncompressed.
        final SyncCommittee committeeAfterNext = new SyncCommittee(
                rotation.nextCommittee().pubkeysRoot32(),
                G1Compression.compress(rotation.nextCommittee().aggregatePubkey128(), "nextCommittee.aggregatePubkey"));
        verifyCommitteeBranch(
                committeeAfterNext,
                rotation.nextCommitteeBranch(),
                Gloas.NEXT_SYNC_COMMITTEE,
                attestedHeader,
                "next sync committee");
        requireValidAggregatePubkey(rotation.nextCommittee().aggregatePubkey128(), "next sync committee");
        log.info(
                "EthSyncCommitteeProofVerifier: rotation verified: periods {}-{} -> {}-{}, attestedSlot={},"
                        + " finalizedSlot={}, committee of period {} pubkeysRoot=0x{}",
                anchor.period(),
                rotationPeriod,
                rotationPeriod,
                rotationPeriod + 1,
                Long.toUnsignedString(attestedHeader.slot()),
                Long.toUnsignedString(finalizedHeader.slot()),
                rotationPeriod + 1,
                HexFormat.of().formatHex(committeeAfterNext.pubkeysRoot32()));
        return anchor.rotatedTo(committeeAfterNext);
    }

    /**
     * Requires a committee's aggregate key to be a valid BLS public key: in the prime-order subgroup and not the
     * identity. The BLS check also validates the aggregate on every signature, but a trust anchor holding an invalid
     * one would reject every later bundle, so the key is refused before it enters the anchor.
     *
     * @param aggregatePubkey128 the aggregate key, a 128-byte EIP-2537 G1 point of the curve
     * @param name               the committee's name in the failure message
     * @throws ProofException if the key is not valid, naming it in the compressed form a beacon node reports
     */
    private void requireValidAggregatePubkey(@NonNull final byte[] aggregatePubkey128, @NonNull final String name) {
        if (!blsVerifier.validatePubkeys(List.of(aggregatePubkey128))) {
            final byte[] aggregatePubkey48 = G1Compression.compress(aggregatePubkey128, name + " aggregate pubkey");
            throw EthProofs.fail(name + " aggregate pubkey 0x" + HexFormat.of().formatHex(aggregatePubkey48)
                    + " is not a valid BLS public key");
        }
    }

    /**
     * Verifies the delivery's signature slot and returns the committee that signed: the slot follows the attested
     * slot, and its period is one of the two the anchor trusts. The attested header may lie in an earlier period: a
     * header attested in the last slot of a period is signed in the first slot of the next.
     *
     * @throws ProofException if either check fails
     */
    @NonNull
    private static SyncCommittee verifySignatureSlot(
            final long signatureSlot, @NonNull final BeaconHeader attestedHeader, @NonNull final TrustAnchor anchor) {
        if (Long.compareUnsigned(signatureSlot, attestedHeader.slot()) <= 0) {
            throw EthProofs.fail("signature slot " + Long.toUnsignedString(signatureSlot)
                    + " is not after the attested header slot " + Long.toUnsignedString(attestedHeader.slot()));
        }
        final long signingPeriod = anchor.preset().periodOf(signatureSlot);
        final SyncCommittee signer = anchor.committeeFor(signingPeriod);
        log.debug(
                "EthSyncCommitteeProofVerifier: signature slot {} is in period {}; signer is the anchor's {} committee"
                        + " (anchor periods {}-{})",
                () -> Long.toUnsignedString(signatureSlot),
                () -> signingPeriod,
                () -> signingPeriod == anchor.period() ? "current" : "next",
                anchor::period,
                () -> anchor.period() + 1);
        return signer;
    }

    /**
     * Verifies that {@code committee} signed the attested header: at least 2/3 of the committee participated, every
     * non-signer key is a committee member, and the BLS aggregate signature of the participants verifies over the
     * header's sync-committee signing root. The caller has checked that {@code committee} signs for the period of the
     * signature.
     *
     * @return the attested header's {@code hash_tree_root}
     * @throws ProofException if any check fails
     */
    @NonNull
    private byte[] verifySignedHeader(
            @NonNull final SignedHeader signedHeader,
            @NonNull final SyncCommittee committee,
            @NonNull final TrustAnchor anchor) {
        final SyncAggregate syncAggregate = signedHeader.syncAggregate();
        requireSupermajority(syncAggregate, anchor.preset());
        final List<byte[]> nonSignerPubkeys =
                verifyNonSigners(signedHeader.nonSignerProofs(), syncAggregate, committee, anchor.preset());
        return verifyAggregateSignature(
                signedHeader.attestedHeader(), syncAggregate, committee, nonSignerPubkeys, anchor);
    }

    /**
     * Authenticates the non-signers' keys against the committee's {@code pubkeysRoot}. The j-th proof belongs to the
     * j-th clear participation bit, and its branch is folded along that member's committee index, so only the key the
     * beacon state holds at that index verifies.
     *
     * <p>This check is mandatory: the BLS check subtracts the non-signers from the committee aggregate, so without it
     * a relay could pass {@code aggregate − K} as a "non-signer" and have a signature by a key {@code K} it controls
     * accepted.
     *
     * @return the non-signers' uncompressed keys, in committee order
     * @throws ProofException if the proof count does not match the clear bits or any proof does not verify
     */
    @NonNull
    private static List<byte[]> verifyNonSigners(
            @NonNull final List<NonSignerProof> nonSignerProofs,
            @NonNull final SyncAggregate syncAggregate,
            @NonNull final SyncCommittee committee,
            @NonNull final SyncCommitteePreset preset) {
        final int nonSignerCount = preset.committeeSize() - syncAggregate.participantCount();
        if (nonSignerProofs.size() != nonSignerCount) {
            throw EthProofs.fail("expected " + nonSignerCount
                    + " non-signer proofs (one per clear participation bit), got " + nonSignerProofs.size());
        }
        final List<byte[]> nonSignerPubkeys = new ArrayList<>(nonSignerCount);
        for (int index = 0; index < preset.committeeSize(); index++) {
            if (syncAggregate.hasSigned(index)) {
                continue;
            }
            final NonSignerProof proof = nonSignerProofs.get(nonSignerPubkeys.size());
            final byte[] leaf = Ssz.pubkeyHash64(
                    G1Compression.compress(proof.pubkey128(), "non-signer pubkey at committee index " + index));
            final SszMerkleBranch branch = new SszMerkleBranch(proof.branch(), preset.pubkeysBranchDepth(), index);
            if (!branch.proves(leaf, committee.pubkeysRoot32())) {
                throw EthProofs.fail("non-signer pubkey at committee index " + index
                        + " is not the trusted committee's member at that index");
            }
            nonSignerPubkeys.add(proof.pubkey128());
        }
        return nonSignerPubkeys;
    }

    /**
     * Verifies that {@code finalizedHeader} is the finalized checkpoint recorded in the attested header's beacon state:
     * its slot does not exceed the attested slot, and its root sits at the finalized-root position of the attested
     * {@code stateRoot}. There is no force-update path: an attested header is never treated as finalized.
     *
     * @throws ProofException if either check fails
     */
    private static void verifyFinality(
            @NonNull final BeaconHeader finalizedHeader,
            @NonNull final byte[][] finalityBranch,
            @NonNull final BeaconHeader attestedHeader) {
        if (Long.compareUnsigned(finalizedHeader.slot(), attestedHeader.slot()) > 0) {
            throw EthProofs.fail("finalized header slot " + Long.toUnsignedString(finalizedHeader.slot())
                    + " is after the attested header slot " + Long.toUnsignedString(attestedHeader.slot()));
        }
        final SszMerkleBranch branch = new SszMerkleBranch(finalityBranch, Gloas.FINALIZED_ROOT);
        if (!branch.proves(finalizedHeader.hashTreeRoot(), attestedHeader.stateRoot32())) {
            throw EthProofs.fail("finality branch does not verify against the attested header stateRoot");
        }
    }

    /**
     * Verifies the finalized execution block and returns its state root. Since Gloas, the beacon body commits to the
     * execution block hash, not to the state root: the block hash is recomputed as {@code keccak256(blockHeaderRlp)},
     * proven in the finalized header's {@code bodyRoot}, and the now-authenticated block header supplies the state
     * root.
     *
     * @throws ProofException if the branch does not verify or the block header has no 32-byte state root
     */
    @NonNull
    private static byte[] verifyExecutionStateRoot(@NonNull final FinalizedExecution finalizedExecution) {
        final byte[] blockHeaderRlp = finalizedExecution.blockHeaderRlp();
        final byte[] executionBlockHash32 = MiscCryptoUtils.keccak256DigestOf(blockHeaderRlp);
        final SszMerkleBranch branch =
                new SszMerkleBranch(finalizedExecution.executionBranch(), Gloas.EXECUTION_BLOCK_HASH);
        if (!branch.proves(
                executionBlockHash32, finalizedExecution.finalizedHeader().bodyRoot32())) {
            throw EthProofs.fail("execution block hash branch does not verify against the finalized header bodyRoot");
        }
        final Rlp.Item blockHeader;
        try {
            blockHeader = Rlp.decodeOne(blockHeaderRlp);
        } catch (final RuntimeException e) {
            throw EthProofs.fail("execution block header is not a valid RLP item", e);
        }
        if (!blockHeader.isList() || blockHeader.children().size() <= BLOCK_HEADER_INDEX_STATE_ROOT) {
            throw EthProofs.fail("execution block header is not an RLP list with a stateRoot");
        }
        final Rlp.Item stateRoot = blockHeader.children().get(BLOCK_HEADER_INDEX_STATE_ROOT);
        if (stateRoot.isList() || stateRoot.asBytes().length != 32) {
            throw EthProofs.fail("execution block header stateRoot is not 32 bytes");
        }
        return stateRoot.asBytes();
    }

    /**
     * Verifies the MPT account proof for {@code contractAddress20} against {@code stateRoot32}, requires the proven
     * account to carry {@code expectedCodeHash32}, and returns it.
     *
     * @throws ProofException if the account is absent or its code hash differs
     */
    @NonNull
    private static EvmAccount verifyAccountProof(
            @NonNull final byte[] stateRoot32,
            @NonNull final byte[] contractAddress20,
            @NonNull final byte[][] accountProof,
            @NonNull final byte[] expectedCodeHash32) {
        final byte[] accountKey = MiscCryptoUtils.keccak256DigestOf(contractAddress20);
        final byte[] accountRlp = RlpDecoder.decodeMerklePatriciaTrie(stateRoot32, accountProof)
                .provenValue(accountKey)
                .orElseThrow(() -> EthProofs.fail("contract account is absent from state trie"));
        final EvmAccount account = RlpDecoder.decodeAccount(accountRlp);
        if (!Arrays.equals(account.codeHash32(), expectedCodeHash32)) {
            throw EthProofs.fail("contract account code hash does not match the code hash pinned in the trust anchor");
        }
        return account;
    }

    /**
     * Proves every storage entry against the account's storage root, then decodes the queue metadata of
     * {@code channelId32} from the proven storage. {@link ClprContractStorage#decodeQueueMetadata} derives the Channel
     * slot keys from the channel id, so every decoded value is bound to this channel.
     *
     * @throws ProofException if a slot key repeats, a proof does not verify, or a Channel slot is missing
     */
    @NonNull
    private static ProvenQueueMetadata proveQueueMetadata(
            @NonNull final List<StorageProofEntry> storageProof,
            @NonNull final EvmAccount provenAccount,
            @NonNull final byte[] channelId32) {
        final ClprContractStorage storage = new ClprContractStorage();
        for (int i = 0; i < storageProof.size(); i++) {
            final byte[] slot32 = leftPad32(storageProof.get(i).key(), "storageProof[" + i + "].key");
            storage.addProvenSlot(
                    slot32,
                    proveStorageValue(provenAccount, slot32, storageProof.get(i).proofNodes()));
        }
        return storage.decodeQueueMetadata(channelId32);
    }

    /**
     * Verifies an MPT storage proof of {@code slot32} against the account's storage root and returns the proven 32-byte
     * value; an exclusion proof yields 32 zero bytes.
     */
    @NonNull
    private static byte[] proveStorageValue(
            @NonNull final EvmAccount provenAccount, @NonNull final byte[] slot32, @NonNull final byte[][] proofNodes) {
        return RlpDecoder.decodeMerklePatriciaTrie(provenAccount.storageRoot32(), proofNodes)
                .provenValue(MiscCryptoUtils.keccak256DigestOf(slot32))
                .map(Rlp::decodeTrieStorageValueAsBytes32)
                .orElseGet(() -> new byte[32]);
    }

    /**
     * Verifies an optional endpoint-manifest advance (spec §4.9). When {@code manifestPreimage} is present the manifest
     * commitment ({@code keccak256(manifestPreimage)}) is proven at the shared endpoint-manifest commitment slot
     * (slot 18, {@code ProofBytes.endpointManifestCommitmentSlot()}) against the already-authenticated account
     * {@code storageRoot32} — the same root the queue metadata was proven against — and the reconstructed manifest is
     * checked for the spec §4.8 invariants. Returns {@code null} when the bundle carries no manifest advance.
     *
     * @throws ProofException if the storage proof fails, the commitment does not match, or a §4.8 invariant is violated
     */
    @Nullable
    private static ClprEndpointManifest verifyEndpointManifestProof(
            @Nullable final byte[] manifestPreimage,
            @Nullable final StorageProofEntry entry,
            @NonNull final EvmAccount provenAccount,
            @NonNull final byte[] serviceAddress20) {
        // The decoder guarantees the preimage and its proof are both present or both absent.
        if (manifestPreimage == null || entry == null) {
            return null;
        }
        // Pin the proof to the designated commitment slot so a peer cannot substitute an unrelated slot.
        final byte[] slot32 = leftPad32(entry.key(), "manifestStorageProof.key");
        if (!Arrays.equals(slot32, endpointManifestCommitmentSlot())) {
            throw EthProofs.fail("manifest storage proof is not for the endpoint-manifest commitment slot");
        }
        // Prove the committed value at that slot against the account's storage root.
        final byte[] provenCommitment = RlpDecoder.decodeMerklePatriciaTrie(
                        provenAccount.storageRoot32(), entry.proofNodes())
                .provenValue(MiscCryptoUtils.keccak256DigestOf(slot32))
                .map(Rlp::decodeTrieStorageValueAsBytes32)
                .orElseThrow(() -> EthProofs.fail("endpoint-manifest commitment slot absent from the storage trie"));
        // The on-chain commitment is keccak256 of the manifest protobuf preimage.
        if (!Arrays.equals(provenCommitment, MiscCryptoUtils.keccak256DigestOf(manifestPreimage))) {
            throw EthProofs.fail("endpoint-manifest preimage does not match the proven commitment");
        }
        // Reconstruct the manifest and enforce spec §4.8 invariants.
        final ClprEndpointManifest manifest;
        try {
            // Spec §1: reject a manifest carrying unrecognized fields.
            manifest = ClprEndpointManifest.PROTOBUF.parseStrict(
                    Bytes.wrap(manifestPreimage).toReadableSequentialData());
        } catch (final ParseException | RuntimeException e) {
            throw EthProofs.fail("manifest preimage is not a valid ClprEndpointManifest", e);
        }
        if (manifest.version() == 0L) {
            throw EthProofs.fail("endpoint-manifest version is 0 (must be >= 1)");
        }
        if (!Arrays.equals(manifest.serviceAddress().toByteArray(), serviceAddress20)) {
            throw EthProofs.fail("endpoint-manifest service_address does not match the channel's service address");
        }
        return manifest;
    }

    /**
     * Requires {@code slot} to lie in {@code expectedPeriod}.
     *
     * @throws ProofException if it lies in another period
     */
    private static void requirePeriod(
            final long slot,
            final long expectedPeriod,
            @NonNull final SyncCommitteePreset preset,
            @NonNull final String name) {
        final long period = preset.periodOf(slot);
        if (period != expectedPeriod) {
            throw EthProofs.fail(name + " " + Long.toUnsignedString(slot) + " is in period " + period
                    + ", expected period " + expectedPeriod);
        }
    }

    /**
     * Verifies that {@code committee} sits at {@code position} of the beacon state whose root {@code header} carries.
     *
     * @throws ProofException if the branch does not verify
     */
    private static void verifyCommitteeBranch(
            @NonNull final SyncCommittee committee,
            @NonNull final byte[][] branch,
            @NonNull final GeneralizedIndex position,
            @NonNull final BeaconHeader header,
            @NonNull final String name) {
        if (!new SszMerkleBranch(branch, position).proves(committee.hashTreeRoot(), header.stateRoot32())) {
            throw EthProofs.fail(name + " branch does not verify against the header stateRoot");
        }
    }

    /**
     * Verifies that the current committee signed the config's update header. The payload carries the committee's full
     * key set, so the non-signers are read from it, with no membership proofs.
     *
     * @throws ProofException if participation is below the supermajority, a non-signer key is malformed, or the BLS
     *                        check fails
     */
    private void verifyUpdateSignature(
            @NonNull final PeerLedgerConfigPayload payload, @NonNull final TrustAnchor anchor) {
        final SyncAggregate syncAggregate = payload.syncAggregate();
        requireSupermajority(syncAggregate, anchor.preset());
        verifyAggregateSignature(
                payload.updateHeader(),
                syncAggregate,
                anchor.current(),
                payload.current().nonSignerPubkeys(syncAggregate),
                anchor);
    }

    /**
     * Requires at least 2/3 of the committee to have signed. Decided from the bits alone, before touching any key
     * material.
     *
     * @throws ProofException if participation is below the supermajority
     */
    private static void requireSupermajority(
            @NonNull final SyncAggregate syncAggregate, @NonNull final SyncCommitteePreset preset) {
        final int participants = syncAggregate.participantCount();
        if (!preset.isSupermajority(participants)) {
            throw EthProofs.fail("sync committee participation " + participants + "/" + preset.committeeSize()
                    + " is below the 2/3 supermajority");
        }
    }

    /**
     * Verifies the BLS aggregate signature of {@code committee}'s participants over {@code header}'s sync-committee
     * signing root, in the anchor's signing domain.
     *
     * @param nonSignerPubkeys the keys of the members that did not sign, 128-byte EIP-2537 points
     * @return {@code header}'s {@code hash_tree_root}
     * @throws ProofException if the signature does not verify; the message carries the header, the participation and
     *     the fork version, to tell a single bad signature from a signing domain that no longer matches the chain
     */
    @NonNull
    private byte[] verifyAggregateSignature(
            @NonNull final BeaconHeader header,
            @NonNull final SyncAggregate syncAggregate,
            @NonNull final SyncCommittee committee,
            @NonNull final List<byte[]> nonSignerPubkeys,
            @NonNull final TrustAnchor anchor) {
        final byte[] headerRoot32 = header.hashTreeRoot();
        final byte[] domain32 = Ssz.computeSyncCommitteeDomain(anchor.forkVersion4(), anchor.genesisValidatorsRoot32());
        final byte[] signingRoot32 = Ssz.computeSigningRoot(headerRoot32, domain32);
        if (!blsVerifier.verifySyncAggregate(
                committee.decompressAggregatePubkey(), nonSignerPubkeys, signingRoot32, syncAggregate.signature())) {
            throw EthProofs.fail("sync aggregate BLS signature verification failed: headerSlot="
                    + Long.toUnsignedString(header.slot()) + ", headerRoot=0x"
                    + HexFormat.of().formatHex(headerRoot32)
                    + ", participants=" + syncAggregate.participantCount() + "/"
                    + anchor.preset().committeeSize()
                    + ", nonSigners=" + nonSignerPubkeys.size() + ", forkVersion=0x"
                    + HexFormat.of().formatHex(anchor.forkVersion4()));
        }
        log.debug(
                "EthSyncCommitteeProofVerifier: BLS aggregate verified: participants={}/{}",
                syncAggregate::participantCount,
                () -> anchor.preset().committeeSize());
        return headerRoot32;
    }
}
