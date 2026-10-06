// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.endpointManifestCommitmentSlot;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.keccak256;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.leftPad32;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.impl.verifier.BlsSignatureVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.clpr.impl.verifier.ethereum.Ssz.SszMerkleBranch;
import com.hedera.node.app.service.clpr.impl.verifier.evm.EvmAccount;
import com.hedera.node.app.service.clpr.impl.verifier.evm.RlpDecoder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verifies Ethereum mainnet proofs via the consensus-layer light client protocol (sync committees, BLS aggregate
 * signatures):
 *
 * <ul>
 *   <li>{@link #verifyBundle} verifies a bundle proof against a known trust anchor: it authenticates an attested
 *       beacon block header through a sync-committee BLS aggregate signature, then extends that trust down to the CLPR
 *       contract's execution-layer state via SSZ Merkle branches and Merkle-Patricia Trie proofs — see its javadoc for
 *       the payload layout and verification steps.</li>
 *   <li>{@link #verifyConfigPayload} bootstraps the initial trust anchor from a self-describing payload. It verifies
 *       nothing cryptographically; the approving party is the trust gate — see its javadoc for the layout and trust
 *       model.</li>
 * </ul>
 *
 * <p>This class is the orchestration layer. The RLP wire format is decoded by
 * {@link PayloadDecoder} into typed object graphs ({@link BundlePayload}, {@link PeerLedgerConfigPayload},
 * {@link TrustAnchor}); SSZ hashing and Merkle-branch math live in {@link Ssz}; MPT proof and
 * account decoding live in the shared {@code verifier} package. This class reads the decoded graph
 * and performs the trust decisions (supermajority, BLS, branch and MPT verification).
 *
 * <p>The trust anchor — consumed by {@code verifyBundle}, produced by {@code verifyConfigPayload}
 * and by bundle rotation proofs — is an RLP list {@code [syncCommittee, genesisValidatorsRoot,
 * forkVersion, serviceAddress]}. The full current sync committee is carried <em>in the anchor</em>,
 * not in every bundle; only a rotation bundle carries a committee, namely the <em>next</em> one,
 * which becomes the successor anchor. The {@code serviceAddress} pins the CLPR service contract the
 * account proof must resolve to, so the verifier needs no external configuration — it is a function
 * of {@code (bundlePayload, trustAnchor)}.
 *
 * <p>Scope notes: this verifier authenticates a single attested header against a known sync
 * committee. The relay is responsible for timing committee rotations — when {@code nextCommittee}
 * is present in a bundle, it is verified and the successor anchor is returned unconditionally.
 * On a hard fork the anchor's {@code forkVersion} must be refreshed via a configuration update.
 */
public final class EthereumSyncCommitteeProofVerifier {

    private static final Logger log = LogManager.getLogger(EthereumSyncCommitteeProofVerifier.class);

    private final BlsSignatureVerifier blsVerifier;

    public EthereumSyncCommitteeProofVerifier(@NonNull final BlsSignatureVerifier blsVerifier) {
        this.blsVerifier = Objects.requireNonNull(blsVerifier, "blsVerifier");
    }

    /**
     * Decode and verify a sync-committee bundle payload, returning the attested beacon block root, the (verbatim)
     * protobuf-serialized {@code ClprBundleContent} bytes, the proven queue metadata, and — when the payload carries a
     * rotation proof — the successor trust anchor.
     *
     * <p>Verification steps:
     * <ol>
     *   <li>Read the current sync committee from the trust anchor; it is the trusted signing set,
     *       so no per-bundle hash check is needed.</li>
     *   <li>Require a 2/3 supermajority of the 512 sync-committee bits and BLS-verify the
     *       aggregate signature over the attested header's sync-committee signing root.</li>
     *   <li>Verify the SSZ branch from the execution-layer state root to the attested header's
     *       {@code bodyRoot}.</li>
     *   <li>If a next-sync-committee rotation proof is present, verify its branch against the
     *       attested header's {@code stateRoot} and emit a successor trust anchor.</li>
     *   <li>Verify the MPT account proof against the proven execution state root, verify each
     *       storage-proof entry against the proven account's storage root, and decode the queue
     *       metadata.</li>
     * </ol>
     *
     * @param bundlePayload the RLP-encoded bundle payload
     * @param trustAnchor   RLP list {@code [syncCommittee, genesisValidatorsRoot, forkVersion]}
     * @return the verified bundle
     * @throws ProofException if decoding fails or any verification step is violated
     */
    @NonNull
    public VerifiedBundle verifyBundle(@NonNull final byte[] bundlePayload, @NonNull final byte[] trustAnchor) {
        Objects.requireNonNull(bundlePayload, "bundlePayload");
        Objects.requireNonNull(trustAnchor, "trustAnchor");
        log.debug(
                "EthSyncCommitteeProofVerifier.verifyBundle ENTER: bundlePayload={} bytes, trustAnchor={} bytes",
                bundlePayload.length,
                trustAnchor.length);

        // --- 1. Decode the payload ----
        final TrustAnchor currentAnchor = PayloadDecoder.decodeTrustAnchor(trustAnchor);
        final BundlePayload bundle = PayloadDecoder.decodeBundle(bundlePayload);
        final BeaconHeader header = bundle.attestedHeader();
        final byte[] beaconBlockRoot32 = header.hashTreeRoot();

        // --- 2. Authenticate the chain down to the CLPR contract account: sync-committee BLS signature over
        // the beacon header, the execution state root SSZ branch, and the account MPT proof. ------
        final EvmAccount provenAccount = verifyAccount(
                header,
                beaconBlockRoot32,
                bundle.syncAggregate(),
                bundle.executionStateRoot32(),
                bundle.executionBranch(),
                currentAnchor,
                bundle.accountProof());

        // --- 3. Prove the queue metadata fields --------------
        // Prove the queue metadata from the five storage slots. A bundle with no queue storage proof simply has
        // no queue metadata — represented by the all-zero absent sentinel (nextMessageId == 0). It is decoded and
        // treated as any other bundle; there is no distinct "manifest-only" shape or path. The §8.1.4 invariant
        // is enforced after the manifest step below.
        final QueueMetadata queueMetadata;
        if (bundle.storageProof().isEmpty()) {
            queueMetadata = QueueMetadata.absent();
        } else {
            // entry.key() is the EVM storage slot; the MPT proves that slot -> value against the
            // account's storage root. Sort the entries by slot key (big-endian unsigned) so they map
            // to the QueueMetadata slot positions regardless of EVM delivery order.
            final List<StorageProofEntry> orderedStorageProof = new ArrayList<>(bundle.storageProof());
            orderedStorageProof.sort((a, b) -> Arrays.compareUnsigned(
                    leftPad32(a.key(), "storageProof.key"), leftPad32(b.key(), "storageProof.key")));
            // The slots map to QueueMetadata fields by sorted position, so each slot must be distinct.
            for (int i = 1; i < orderedStorageProof.size(); i++) {
                if (Arrays.equals(
                        leftPad32(orderedStorageProof.get(i - 1).key(), "storageProof.key"),
                        leftPad32(orderedStorageProof.get(i).key(), "storageProof.key"))) {
                    throw EthProofs.fail("bundle storage proof contains a duplicate slot key");
                }
            }
            final byte[][] provenSlotValues = new byte[orderedStorageProof.size()][];
            for (int i = 0; i < orderedStorageProof.size(); i++) {
                final StorageProofEntry entry = orderedStorageProof.get(i);
                final byte[] storageKey = keccak256(leftPad32(entry.key(), "storageProof[" + i + "].key"));
                provenSlotValues[i] = RlpDecoder.decodeMerklePatriciaTrie(
                                provenAccount.storageRoot32(), entry.proofNodes())
                        .provenValue(storageKey)
                        .map(Rlp::decodeTrieStorageValueAsBytes32)
                        .orElseGet(() -> new byte[32]);
            }
            queueMetadata = QueueMetadata.decode(provenSlotValues);
        }

        // --- 4. Endpoint-manifest advance (spec §4.9), if the bundle carries one -------------
        // Verified against the SAME proven account storage root as the queue metadata above — so it
        // rides the same (currently fake-BLS) anchor as the rest of the bundle. Null when absent.
        final ClprEndpointManifest newEndpointManifest = verifyEndpointManifestProof(
                bundle.manifestBytes(), bundle.manifestStorageProof(), provenAccount, currentAnchor.serviceAddress20());

        // A bundle must carry SOMETHING: queue metadata, message content, or a manifest advance. When both the
        // queue metadata and the content are absent, the bundle MUST carry a manifest advance (spec §8.1.4);
        // otherwise it is empty/meaningless and verification fails.
        if (bundle.storageProof().isEmpty() && bundle.bundleContent().length == 0 && newEndpointManifest == null) {
            throw EthProofs.fail("bundle has no queue metadata, no content, and no endpoint-manifest advance");
        }

        // --- 5. Trust anchor rotation ---------------------------------------
        final byte[] nextTrustAnchor = verifyNextSyncCommittee(
                bundle.nextCommittee(), bundle.nextCommitteeBranch(), header.stateRoot32(), currentAnchor);
        // for Ethereum, the trustAnchorId is the slot number.
        final byte[] nextTrustAnchorId = nextTrustAnchor == null
                ? null
                : BigInteger.valueOf(header.slot()).toByteArray();
        log.debug(
                "EthSyncCommitteeProofVerifier.verifyBundle EXIT: SUCCESS beaconBlockRoot=0x{}, nextTrustAnchor={}",
                () -> HexFormat.of().formatHex(beaconBlockRoot32),
                () -> nextTrustAnchor == null ? "<none>" : "present");
        return VerifiedBundle.builder()
                .beaconBlockRoot32(beaconBlockRoot32)
                .bundleContentBytes(bundle.bundleContent())
                .queueMetadata(queueMetadata)
                .nextTrustAnchor(nextTrustAnchor)
                .nextTrustAnchorId(nextTrustAnchorId)
                .newEndpointManifest(newEndpointManifest)
                .build();
    }

    /**
     * Authenticates the chain from the sync-committee-signed attested header down to the CLPR contract account
     * (verify step 2):
     * <ol>
     *   <li>require a 2/3 supermajority and BLS-verify the sync aggregate over the attested header;</li>
     *   <li>SSZ-verify the execution-state-root branch against the attested header's {@code bodyRoot}; and</li>
     *   <li>MPT-verify the account proof for the anchor's service address against the proven execution state
     *       root.</li>
     * </ol>
     *
     * @return the proven CLPR contract account
     * @throws ProofException if any step is violated
     */
    @NonNull
    private EvmAccount verifyAccount(
            @NonNull final BeaconHeader header,
            @NonNull final byte[] beaconBlockRoot32,
            @NonNull final SyncAggregate syncAggregate,
            @NonNull final byte[] executionStateRoot32,
            @NonNull final byte[][] executionBranch,
            @NonNull final TrustAnchor anchor,
            @NonNull final byte[][] accountProof) {
        // Step 2: supermajority + BLS aggregate over the attested header.
        verifySupermajorityBlsSignature(
                anchor.committee().pubkeys(),
                syncAggregate,
                beaconBlockRoot32,
                anchor.forkVersion4(),
                anchor.genesisValidatorsRoot32());

        // Step 3.1: the provided execution state is committed to the beacon header.
        final SszMerkleBranch sszMerkleBranch = new SszMerkleBranch(
                executionBranch, Ssz.EXECUTION_STATE_ROOT_BRANCH_DEPTH, Ssz.EXECUTION_STATE_ROOT_LEAF_INDEX);
        if (!sszMerkleBranch.proves(executionStateRoot32, header.bodyRoot32())) {
            throw EthProofs.fail("execution state root branch does not verify against the attested bodyRoot");
        }

        // Step 3.2: account proof against the proven execution state root. The CLPR service contract
        // address to pin comes from the trust anchor.
        return verifyAccountProofForAddress(
                executionStateRoot32,
                anchor.serviceAddress20(),
                accountProof,
                "contract account is absent from state trie");
    }

    /**
     * Verifies an optional endpoint-manifest advance (spec §4.9). When {@code manifestBytes} is present the
     * manifest commitment ({@code keccak256(manifestBytes)}) is proven at the shared endpoint-manifest commitment
     * slot (slot 18, {@code ProofBytes.endpointManifestCommitmentSlot()}) against the already-authenticated account
     * {@code storageRoot32} — the same root the queue metadata was proven against — and the reconstructed manifest is
     * checked for the spec §4.8 invariants. Returns {@code null} when the advance is absent (null or empty
     * manifest bytes) — e.g. a normal bundle with no manifest advance.
     *
     * @throws ProofException if the storage proof fails, the commitment does not match, or a §4.8 invariant is violated
     */
    @Nullable
    private ClprEndpointManifest verifyEndpointManifestProof(
            @Nullable final byte[] manifestBytes,
            @Nullable final StorageProofEntry entry,
            @NonNull final EvmAccount provenAccount,
            @NonNull final byte[] serviceAddress20) {
        // Absent OR empty manifest bytes both mean "no manifest advance". An empty (non-null) array must not
        // reach parseStrict below, which would yield ClprEndpointManifest.DEFAULT (version 0) and fail the
        // whole bundle — dropping its messages with it.
        if (manifestBytes == null || manifestBytes.length == 0) {
            return null;
        }
        if (entry == null) {
            throw EthProofs.fail("manifest advance present but its commitment-slot storage proof is missing");
        }
        // Pin the proof to the designated commitment slot so a peer cannot substitute an unrelated slot.
        final byte[] slot32 = leftPad32(entry.key(), "manifestStorageProof.key");
        if (!Arrays.equals(slot32, endpointManifestCommitmentSlot())) {
            throw EthProofs.fail("manifest storage proof is not for the endpoint-manifest commitment slot");
        }
        // Prove the committed value at that slot against the account's storage root.
        final byte[] storageKey = keccak256(slot32);
        final byte[] provenCommitment = RlpDecoder.decodeMerklePatriciaTrie(
                        provenAccount.storageRoot32(), entry.proofNodes())
                .provenValue(storageKey)
                .map(Rlp::decodeTrieStorageValueAsBytes32)
                .orElseThrow(() -> EthProofs.fail("endpoint-manifest commitment slot absent from the storage trie"));
        // The on-chain commitment is keccak256 of the manifest protobuf preimage.
        if (!Arrays.equals(provenCommitment, keccak256(manifestBytes))) {
            throw EthProofs.fail("endpoint-manifest preimage does not match the proven commitment");
        }
        // Reconstruct the manifest and enforce spec §4.8 invariants.
        final ClprEndpointManifest manifest;
        try {
            // Spec §1: reject a manifest carrying unrecognized fields.
            manifest = ClprEndpointManifest.PROTOBUF.parseStrict(
                    Bytes.wrap(manifestBytes).toReadableSequentialData());
        } catch (final ParseException | RuntimeException e) {
            throw EthProofs.fail("manifest preimage is not a valid ClprEndpointManifest", e);
        }
        if (manifest.version() == 0L) {
            throw EthProofs.fail("endpoint-manifest version is 0 (must be >= 1)");
        }
        if (!Arrays.equals(manifest.serviceAddress().toByteArray(), serviceAddress20)) {
            throw EthProofs.fail("endpoint-manifest service_address does not match the trusted service address");
        }
        return manifest;
    }

    /**
     * Decode a sync-committee config payload, returning the advertised {@link ClprLedgerConfiguration} with the initial
     * trust anchor derived from the self-described committee.
     *
     * <p>The config payload is self-describing: no proof is checked and no signature is verified. It supplies the
     * initial sync committee plus the chain-pinning {@code genesisValidatorsRoot} and {@code forkVersion}, which are
     * RLP-encoded into the initial trust anchor; the {@code ledgerConfiguration} bytes are parsed and returned with
     * that anchor attached.
     *
     * <p>Trust model: nothing here is cryptographically authenticated against Ethereum mainnet — the committee vouches
     * only for itself. The party approving the configuration update must check that the committee,
     * {@code genesisValidatorsRoot} and {@code forkVersion} match Ethereum mainnet. Steady-state authentication begins
     * with the first bundle, whose sync-aggregate signature is verified against this now-trusted committee.
     */
    @NonNull
    public VerifiedConfig verifyConfigPayload(@NonNull final byte[] configPayload) {
        Objects.requireNonNull(configPayload, "configPayload");
        log.info("EthSyncCommitteeProofVerifier.verifyConfigPayload ENTER: {} bytes", configPayload.length);

        // --- 1. Decode the payload -----
        final PeerLedgerConfigPayload payload = PayloadDecoder.decodeConfig(configPayload);
        final ClprLedgerConfiguration ledgerConfiguration;
        try {
            // Spec §1: "Implementations MUST reject messages containing unrecognized fields."
            ledgerConfiguration = ClprLedgerConfiguration.PROTOBUF.parseStrict(
                    Bytes.wrap(payload.ledgerConfigBytes()).toReadableSequentialData());
        } catch (final ParseException | RuntimeException e) {
            throw EthProofs.fail("ledgerConfiguration is not a valid ClprLedgerConfiguration", e);
        }

        // --- 2. Derive the initial trust anchor from the self-described committee -----
        final SyncCommittee committee = payload.committee();
        final byte[] genesisValidatorsRoot32 = payload.genesisValidatorsRoot32();
        final byte[] forkVersion4 = payload.forkVersion4();
        final byte[] serviceAddress20 = ledgerConfiguration.serviceAddress().toByteArray();
        if (serviceAddress20.length != TrustAnchor.SERVICE_ADDRESS_LENGTH) {
            throw EthProofs.fail("ledgerConfiguration.serviceAddress must be " + TrustAnchor.SERVICE_ADDRESS_LENGTH
                    + " bytes, got " + serviceAddress20.length);
        }
        final var initialTrustAnchor =
                Bytes.wrap(TrustAnchor.encode(committee, genesisValidatorsRoot32, forkVersion4, serviceAddress20));
        final var ledgerCfg = ledgerConfiguration
                .copyBuilder()
                .initialTrustAnchor(initialTrustAnchor)
                .initialTrustAnchorId(initialTrustAnchor)
                .build();
        log.info(
                "EthSyncCommitteeProofVerifier.verifyConfigPayload EXIT: SUCCESS chainId={} endpoints={} slot={}",
                ledgerCfg::chainId,
                () -> ledgerCfg.endpoints().size(),
                payload::slot);
        return new VerifiedConfig(ledgerCfg, payload.slot());
    }

    /**
     * Checks supermajority participation and verifies the BLS aggregate signature of the participating committee
     * members over the attested header's sync-committee signing root.
     *
     * @throws ProofException if participation is below 2/3 or the signature does not verify
     */
    private void verifySupermajorityBlsSignature(
            @NonNull final byte[][] pubkeys,
            @NonNull final SyncAggregate aggregate,
            @NonNull final byte[] beaconBlockRoot32,
            @NonNull final byte[] forkVersion4,
            @NonNull final byte[] genesisValidatorsRoot32) {
        final List<byte[]> participants = selectParticipants(pubkeys, aggregate.bits64());
        if (3 * participants.size() < 2 * Ssz.SYNC_COMMITTEE_SIZE) {
            throw EthProofs.fail("sync committee participation " + participants.size() + "/" + Ssz.SYNC_COMMITTEE_SIZE
                    + " is below the 2/3 supermajority");
        }
        final byte[] domain32 = Ssz.computeSyncCommitteeDomain(forkVersion4, genesisValidatorsRoot32);
        final byte[] signingRoot32 = Ssz.computeSigningRoot(beaconBlockRoot32, domain32);
        if (!blsVerifier.fastAggregateVerify(participants, signingRoot32, aggregate.signature96())) {
            throw EthProofs.fail("sync aggregate BLS signature verification failed");
        }
        log.debug(
                "EthSyncCommitteeProofVerifier: BLS aggregate verified: participants={}/{}",
                participants.size(),
                Ssz.SYNC_COMMITTEE_SIZE);
    }

    /**
     * Selects the pubkeys whose bit is set in the SSZ {@code Bitvector[512]} (bit {@code i} is bit {@code i % 8} — LSB
     * first — of byte {@code i / 8}), in committee order.
     */
    @NonNull
    private static List<byte[]> selectParticipants(@NonNull final byte[][] pubkeys, @NonNull final byte[] bits64) {
        final List<byte[]> participants = new ArrayList<>();
        for (int i = 0; i < Ssz.SYNC_COMMITTEE_SIZE; i++) {
            if (((bits64[i / 8] >>> (i % 8)) & 1) == 1) {
                participants.add(pubkeys[i]);
            }
        }
        return participants;
    }

    /**
     * Verifies the optional next-sync-committee rotation proof. When {@code nextCommittee} is present, its SSZ root's
     * Merkle branch is verified against the attested header's {@code stateRoot} and the successor trust anchor —
     * same genesis validators root, fork version, and service address, embedding the rotated committee — is returned.
     * Returns {@code null} when no rotation proof is present.
     */
    @Nullable
    private static byte[] verifyNextSyncCommittee(
            @Nullable final SyncCommittee nextCommittee,
            @Nullable final byte[][] nextCommitteeBranch,
            @NonNull final byte[] stateRoot32,
            @NonNull final TrustAnchor anchor) {
        if (nextCommittee == null || nextCommitteeBranch == null) {
            return null;
        }
        final SszMerkleBranch sszMerkleBranch = new SszMerkleBranch(
                nextCommitteeBranch, Ssz.NEXT_SYNC_COMMITTEE_BRANCH_DEPTH, Ssz.NEXT_SYNC_COMMITTEE_LEAF_INDEX);
        if (!sszMerkleBranch.proves(nextCommittee.hashTreeRoot(), stateRoot32)) {
            throw EthProofs.fail("nextSyncCommittee branch does not verify against the attested header stateRoot");
        }
        // The service contract address and chain-pinning fields are stable across rotations.
        return TrustAnchor.encode(
                nextCommittee, anchor.genesisValidatorsRoot32(), anchor.forkVersion4(), anchor.serviceAddress20());
    }

    /**
     * Verifies the MPT account proof for {@code contractAddress20} against {@code stateRoot32} and returns the proven
     * account.
     */
    @NonNull
    private EvmAccount verifyAccountProofForAddress(
            @NonNull final byte[] stateRoot32,
            @NonNull final byte[] contractAddress20,
            @NonNull final byte[][] accountProof,
            @NonNull final String absentMessage) {
        final byte[] accountKey = keccak256(contractAddress20);
        final byte[] accountRlp = RlpDecoder.decodeMerklePatriciaTrie(stateRoot32, accountProof)
                .provenValue(accountKey)
                .orElseThrow(() -> EthProofs.fail(absentMessage));
        return RlpDecoder.decodeAccount(accountRlp);
    }
}
