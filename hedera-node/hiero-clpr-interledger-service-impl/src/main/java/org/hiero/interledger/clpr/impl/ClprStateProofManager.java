// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.hapi.utils.blocks.HashUtils;
import com.hedera.node.app.hapi.utils.blocks.MerklePathBuilder;
import com.hedera.node.app.hapi.utils.blocks.StateProofBuilder;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssSignatureVerifierFactory;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.virtualmap.VirtualMapIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;
import org.hiero.interledger.clpr.ClprService;
import org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema;

/**
 * Manages access to CLPR state and validates incoming state proofs.
 *
 * <p>The manager relies on a supplied {@link BlockProvenSnapshotProvider} so it can retrieve CLPR-backed stores on
 * demand without holding onto stale references. The provider is implemented by the application layer, keeping this
 * implementation free of direct application-module dependencies.</p>
 *
 * <p>All public methods work in both dev and production modes. State proof building and
 * validation use the injected {@link TssSignatureVerifierFactory} for TSS signature verification.</p>
 */
@Singleton
public class ClprStateProofManager {
    private static final Logger log = LogManager.getLogger(ClprStateProofManager.class);

    /**
     * Minimum effective signature size that indicates a SNARK-based chain-of-trust proof.
     * The effective signature is composed of: VK (1480 bytes) + hinTS aggregate (1632 bytes) +
     * chain-of-trust proof. A SNARK proof is 704 bytes (self-contained, no external state needed),
     * while a Schnorr aggregate is only 192 bytes (requires TSS.setAddressBook() to be called first,
     * which is not available in the application code path). We only build state proofs when the
     * SNARK proof is available, since Schnorr proofs cannot be verified without the address book.
     */
    private static final int MINIMUM_SNARK_EFFECTIVE_SIGNATURE_SIZE = 1480 + 1632 + 704;

    private final BlockProvenSnapshotProvider snapshotProvider;
    private final com.hedera.node.config.data.ClprConfig clprConfig;
    private final TssSignatureVerifierFactory tssVerifierFactory;

    @Inject
    public ClprStateProofManager(
            @NonNull final BlockProvenSnapshotProvider snapshotProvider,
            @NonNull final com.hedera.node.config.data.ClprConfig clprConfig,
            @NonNull final TssSignatureVerifierFactory tssVerifierFactory) {
        this.snapshotProvider = requireNonNull(snapshotProvider);
        this.clprConfig = requireNonNull(clprConfig);
        this.tssVerifierFactory = requireNonNull(tssVerifierFactory);
    }

    /**
     * Indicates whether dev mode shortcuts are enabled.
     *
     * @return {@code true} when dev mode is active
     */
    public boolean isDevModeEnabled() {
        return clprConfig.devModeEnabled();
    }

    /**
     * Returns the most recent local ledger id, or {@code null} if it is not yet known.
     *
     * <p>The authoritative ledgerId (genesis address book hash) is written to
     * {@link ClprLocalLedgerMetadata} by the HandleWorkflow bridge after the history service
     * completes its genesis proof. Until that bridge fires, this method returns {@code null}
     * to signal that the node is still bootstrapping.</p>
     *
     * @return the local ledger ID, or {@code null} when not yet available
     */
    @Nullable
    public ClprLedgerId getLocalLedgerId() {
        final var snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            return null;
        }
        final var state = snapshot.state();
        final var readableStates = state.getReadableStates(ClprService.NAME);
        if (!readableStates.contains(V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_ID)) {
            return null;
        }
        final ReadableSingletonState<ClprLocalLedgerMetadata> metadataState =
                readableStates.getSingleton(V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_ID);
        final var metadata = metadataState.get();
        if (metadata != null
                && metadata.ledgerId() != null
                && metadata.ledgerId().ledgerId() != Bytes.EMPTY) {
            return metadata.ledgerId();
        }
        return null;
    }

    /**
     * Returns all ledger configurations currently stored in state, keyed by ledger id.
     *
     * <p>Dev-mode helper for maintenance loops that need to publish/pull remote configurations.</p>
     */
    public Map<ClprLedgerId, ClprLedgerConfiguration> readAllLedgerConfigurations() {
        final var snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            log.warn("readAllLedgerConfigurations: no snapshot available");
            return Map.of();
        }
        final var state = snapshot.state();
        if (!(state instanceof VirtualMapState virtualMapState)) {
            log.warn(
                    "readAllLedgerConfigurations: state is not VirtualMapState, type={}",
                    state.getClass().getName());
            return Map.of();
        }
        final var readableStates = state.getReadableStates(ClprService.NAME);
        if (!readableStates.contains(V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID)) {
            return Map.of();
        }
        final Map<ClprLedgerId, ClprLedgerConfiguration> configs = new LinkedHashMap<>();
        final var iterator = new VirtualMapIterator(virtualMapState.getRoot()).setFilter(leaf -> {
            try {
                return StateKeyUtils.extractStateIdFromStateKeyOneOf(leaf.keyBytes())
                        == V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID;
            } catch (final RuntimeException e) {
                return false;
            }
        });
        while (iterator.hasNext()) {
            final var leaf = iterator.next();
            try {
                final var ledgerId = StateKeyUtils.extractKeyFromStateKeyOneOf(leaf.keyBytes(), ClprLedgerId.PROTOBUF);
                final var config = ClprLedgerConfiguration.PROTOBUF.parse(StateUtils.unwrap(leaf.valueBytes()));
                configs.put(ledgerId, config);
            } catch (final ParseException e) {
                throw new IllegalStateException("Unable to decode CLPR ledger configuration from state", e);
            }
        }
        return configs;
    }

    /**
     * Returns a state proof for the requested ledger configuration, or {@code null} if none exists.
     *
     * <p>Builds a state proof from the Merkle tree and signs it with the TSS signature from
     * the latest block-proven snapshot. The proof can be independently verified by any party
     * using {@link #validateStateProof} or {@link #verifyProof}.</p>
     *
     * @param ledgerId the ledger ID to query
     * @return state proof containing the configuration, or {@code null} if state is unavailable or config not found
     */
    @Nullable
    public StateProof getLedgerConfiguration(@NonNull final ClprLedgerId ledgerId) {
        requireNonNull(ledgerId);
        ClprLedgerId resolvedLedgerId = ledgerId;
        if (ledgerId.ledgerId() == Bytes.EMPTY) {
            final var localLedgerId = getLocalLedgerId();
            if (localLedgerId == null || localLedgerId.ledgerId() == Bytes.EMPTY) {
                return null;
            }
            resolvedLedgerId = localLedgerId;
        }
        final var snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            return null;
        }

        final var state = snapshot.state();
        final var readableStates = state.getReadableStates(ClprService.NAME);
        if (!readableStates.contains(V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID)) {
            throw new IllegalStateException(
                    "CLPR ledger configurations state not found - service may not be properly initialized");
        }

        if (!(state instanceof VirtualMapState virtualMapState)) {
            throw new IllegalStateException("Unable to build Merkle proofs from a non-VirtualMap state");
        }

        final var tssSigBytes = snapshot.tssSignature();
        if (tssSigBytes == null || Objects.equals(tssSigBytes, Bytes.EMPTY)) {
            throw new IllegalStateException("TSS signature is missing or invalid");
        }

        // Only build state proofs when the effective signature contains a SNARK chain-of-trust
        // proof. Schnorr aggregate proofs (smaller) require TSS.setAddressBook() which is not
        // available in the application code path, so verification would always fail.
        if (tssSigBytes.length() < MINIMUM_SNARK_EFFECTIVE_SIGNATURE_SIZE) {
            log.fatal(
                    "Deferring state proof construction: effective signature is {} bytes "
                            + "(minimum {} for SNARK proof); SNARK prover may still be running",
                    tssSigBytes.length(),
                    MINIMUM_SNARK_EFFECTIVE_SIGNATURE_SIZE);
            return null;
        }

        final var blockTimestamp = snapshot.blockTimestamp();
        if (blockTimestamp == null || Objects.equals(blockTimestamp, Timestamp.DEFAULT)) {
            throw new IllegalStateException("Block timestamp is missing or invalid");
        }

        final var path = snapshot.path();
        if (path == null || Objects.equals(path, MerklePath.DEFAULT) || !path.hasHash()) {
            throw new IllegalStateException("Merkle path is missing or invalid");
        }

        return buildMerkleStateProof(
                virtualMapState,
                V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID,
                ClprLedgerId.PROTOBUF.toBytes(resolvedLedgerId),
                tssSigBytes,
                blockTimestamp,
                path);
    }

    /**
     * Returns the current ledger configuration from state without constructing a state proof.
     *
     * <p>This helper is available in both dev and non-dev modes so that server-side handlers can
     * enforce monotonic timestamp rules before applying state updates.</p>
     *
     * @param ledgerId the ledger identifier to resolve
     * @return the stored configuration, or {@code null} when none exists or state is unavailable
     */
    @Nullable
    public ClprLedgerConfiguration readLedgerConfiguration(@NonNull final ClprLedgerId ledgerId) {
        requireNonNull(ledgerId);
        final var snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            log.warn("readLedgerConfiguration: no snapshot for key={}", ledgerId);
            return null;
        }
        final var state = snapshot.state();
        final var readableStates = state.getReadableStates(ClprService.NAME);
        if (!readableStates.contains(V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID)) {
            log.warn("readLedgerConfiguration: state does not contain config state ID");
            return null;
        }
        final ReadableKVState<ClprLedgerId, ClprLedgerConfiguration> configsState =
                readableStates.get(V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID);
        final var result = configsState.get(ledgerId);
        log.warn(
                "readLedgerConfiguration: key={} keyBytes={} found={} stateType={}",
                ledgerId,
                ledgerId.ledgerId(),
                result != null,
                state.getClass().getName());
        return result;
    }

    @NonNull
    private Optional<BlockProvenSnapshot> latestSnapshot() {
        return snapshotProvider.latestSnapshot();
    }

    /**
     * Builds a proper Merkle state proof from the actual state tree to the containing block's root.
     */
    @Nullable
    private StateProof buildMerkleStateProof(
            @NonNull final VirtualMapState merkleState,
            final int STATE_ID,
            @NonNull final Bytes STATE_KEY,
            @NonNull final Bytes tssSignature,
            @NonNull final Timestamp blockTimestamp,
            @NonNull final MerklePath stateSubrootToBlockRightChildRoot) {
        // Ensure state is hashed before getting Merkle proof
        if (!merkleState.isHashed()) {
            merkleState.computeHash();
        }

        // Get the Merkle path for this KV entry
        final long path = merkleState.getKvPath(STATE_ID, STATE_KEY);
        if (path < 0) { // INVALID_PATH is -1L
            return null;
        }

        // Get the Merkle proof from the state
        final var merkleProof = merkleState.getMerkleProof(path);
        if (merkleProof == null) {
            return null;
        }

        // Begin constructing the full block proof
        final var blockRootProof = StateProofBuilder.newBuilder().addProof(merkleProof);

        // Represent the block timestamp as a (left) sibling node
        final var tsBytes = Timestamp.PROTOBUF.toBytes(blockTimestamp);
        // Method 1 (as MerkleLeaf):
        final Bytes hashedTsBytes = Bytes.wrap(HashUtils.computeTimestampLeafHash(sha384DigestOrThrow(), tsBytes));
        final var timestampLeafAsSibling =
                SiblingNode.newBuilder().hash(hashedTsBytes).isLeft(true).build();

        // Copy the path to a new list and path, adding the timestamp as the left child at the end/top
        final var sibs = stateSubrootToBlockRightChildRoot.siblings();
        final var extendedSibs = new ArrayList<SiblingNode>(sibs.size() + 1);
        extendedSibs.addAll(sibs);
        extendedSibs.add(SiblingNode.newBuilder().hash(Bytes.EMPTY).build()); // single-child node indicator
        extendedSibs.add(timestampLeafAsSibling);

        // Extend the proof fully to the block root (now includes the timestamp as a hashed leaf)
        final var sibsMpb = new MerklePathBuilder().appendSiblingNodes(extendedSibs);
        blockRootProof.extendRoot(sibsMpb);

        // Add the proof terminator
        final var rootMbp = new MerklePathBuilder().setNextPathIndex(-1);
        blockRootProof.extendRoot(rootMbp);

        // Add the TSS signature and build the object
        return blockRootProof.withTssSignature(tssSignature).build();
    }

    /**
     * Validates the state proof embedded in the supplied transaction.
     *
     * <p>Extracts the remote ledger's self-asserted {@code ledgerId} from the configuration
     * inside the proof, then verifies the TSS signature against that ledger identity.
     * This is cryptographically safe: if the remote lies about its ledgerId, the SNARK
     * proof anchored to the real genesis address book hash will not validate.</p>
     *
     * @param configTxn the transaction containing the state proof
     * @return {@code true} if the proof is structurally sound and its signature matches the computed root hash
     */
    public boolean validateStateProof(@NonNull final ClprSetLedgerConfigurationTransactionBody configTxn) {
        requireNonNull(configTxn);
        if (!configTxn.hasLedgerConfigurationProof()) {
            return false;
        }
        try {
            final var proof = configTxn.ledgerConfigurationProofOrThrow();
            // Extract the remote ledger's self-asserted ledgerId from the config inside the proof.
            // This is cryptographically safe: TSS.verifyTSS with a SNARK proof will fail if
            // the ledgerId doesn't match the genesis embedded in the chain-of-trust proof.
            final var config = org.hiero.interledger.clpr.ClprStateProofUtils.extractConfiguration(proof);
            final var ledgerId = config.ledgerIdOrThrow().ledgerId();
            final var verifier = tssVerifierFactory.forLedger(ledgerId);
            return StateProofVerifier.verify(proof, verifier);
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Verifies a state proof against the given ledger identity using the TSS verifier factory.
     *
     * <p>This is the general-purpose verification entry point for callers that already have the
     * {@code ledgerId} and state proof. Both local and remote proofs can be verified this way.
     *
     * @param proof    the state proof to verify
     * @param ledgerId the ledger identity (genesis address book hash) to verify against
     * @return {@code true} if the proof's TSS signature is valid for the given ledger
     */
    public boolean verifyProof(@NonNull final StateProof proof, @NonNull final Bytes ledgerId) {
        requireNonNull(proof);
        requireNonNull(ledgerId);
        try {
//            log.warn(
//                    "TSS_DEBUG verifyProof: entering for ledger {}, paths={}",
//                    ledgerId,
//                    proof.paths().size());
            final var verifier = tssVerifierFactory.forLedger(ledgerId);
            final boolean result = StateProofVerifier.verify(proof, verifier);
//            log.warn("TSS_DEBUG verifyProof: result={} for ledger {}", result, ledgerId);
            if (!result) {
                log.warn(
                        "State proof TSS verification returned false for ledger {}; "
                                + "proof has {} paths, signedBlockProof present={}",
                        ledgerId,
                        proof.paths().size(),
                        proof.hasSignedBlockProof());
            }
            return result;
        } catch (final Exception e) {
            log.warn("TSS_DEBUG verifyProof: EXCEPTION for ledger {}: {}", ledgerId, e.toString());
            log.error("State proof verification threw exception for ledger {}", ledgerId, e);
            return false;
        }
    }

    public boolean clprEnabled() {
        return clprConfig.clprEnabled();
    }
}
