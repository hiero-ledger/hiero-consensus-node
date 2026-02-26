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
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.ClprService;
import org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema;

/**
 * Manages access to CLPR state and validates incoming state proofs.
 *
 * <p>The manager relies on a supplied {@link BlockProvenSnapshotProvider} so it can retrieve CLPR-backed stores on
 * demand without holding onto stale references. The provider is implemented by the application layer, keeping this
 * implementation free of direct application-module dependencies.</p>
 *
 * <p><b>Dev Mode Only:</b> All methods in this class require {@code devModeEnabled} to be {@code true}.
 * When dev mode is disabled, methods return {@code null} or throw {@link UnsupportedOperationException}
 * as production mode is not yet implemented.</p>
 */
@Singleton
public class ClprStateProofManager {

    private final BlockProvenSnapshotProvider snapshotProvider;
    private final com.hedera.node.config.data.ClprConfig clprConfig;

    @Inject
    public ClprStateProofManager(
            @NonNull final BlockProvenSnapshotProvider snapshotProvider,
            @NonNull final com.hedera.node.config.data.ClprConfig clprConfig) {
        this.snapshotProvider = requireNonNull(snapshotProvider);
        this.clprConfig = requireNonNull(clprConfig);
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
     * <p><b>Dev Mode Behavior:</b> Returns the ledger ID from the first configuration found in state.
     * If no configuration exists yet an empty {@link ClprLedgerId} is returned so callers can treat the
     * value as “still bootstrapping”. In production this should be replaced with a history-service lookup.</p>
     *
     * @return the local ledger ID, {@code null} when dev mode is disabled, or an empty id while bootstrapping
     */
    @Nullable
    public ClprLedgerId getLocalLedgerId() {
        if (!clprConfig.devModeEnabled()) {
            return null;
        }
        final var snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            return emptyLedgerId();
        }
        final var state = snapshot.state();
        final var readableStates = state.getReadableStates(ClprService.NAME);
        if (!readableStates.contains(V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_ID)) {
            // TODO: Remove this throw once production bootstrapping (HistoryService-provided ledgerId + metadata init)
            // is fully wired; at that point the metadata state should be provisioned by bootstrap flows instead of
            // enforced here.
            throw new IllegalStateException("State is not configured for CLPR");
        }
        final ReadableSingletonState<ClprLocalLedgerMetadata> metadataState =
                readableStates.getSingleton(V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_ID);
        final var metadata = metadataState.get();
        if (metadata != null
                && metadata.ledgerId() != null
                && metadata.ledgerId().ledgerId().length() > 0) {
            return metadata.ledgerId();
        }
        return emptyLedgerId();
    }

    /**
     * Returns all ledger configurations currently stored in state, keyed by ledger id.
     *
     * <p>Dev-mode helper for maintenance loops that need to publish/pull remote configurations.</p>
     */
    public Map<ClprLedgerId, ClprLedgerConfiguration> readAllLedgerConfigurations() {
        final var snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            return Map.of();
        }
        final var state = snapshot.state();
        if (!(state instanceof VirtualMapState virtualMapState)) {
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

    @Deprecated
    private ClprLedgerId emptyLedgerId() {
        if (!clprConfig.devModeEnabled()) {
            throw new IllegalStateException("Can only return empty ledger ids in dev mode");
        }
        return ClprLedgerId.newBuilder().build();
    }

    /**
     * Returns a state proof for the requested ledger configuration, or {@code null} if none exists.
     *
     * <p><b>Dev Mode Behavior:</b> Builds a state proof from the actual Merkle tree containing the configuration.
     * The state root hash is used as the TSS signature. In production mode, this should return a TSS-backed
     * proof from the history service.</p>
     *
     * @param ledgerId the ledger ID to query
     * @return state proof containing the configuration, or {@code null} if not in dev mode or configuration not found
     */
    @Nullable
    public StateProof getLedgerConfiguration(@NonNull final ClprLedgerId ledgerId) {
        requireNonNull(ledgerId);
        if (!clprConfig.devModeEnabled()) {
            return null;
        }
        ClprLedgerId resolvedLedgerId = ledgerId;
        if (ledgerId.ledgerId().length() == 0) {
            final var localLedgerId = getLocalLedgerId();
            if (localLedgerId == null || localLedgerId.ledgerId().length() == 0) {
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
     * Returns a state proof for the requested message queue metadata, or {@code null} if not found.
     *
     * @param ledgerId the ledger ID to query
     * @return state proof containing the message queue metadata, or {@code null} if metadata not found
     */
    @Nullable
    public StateProof getMessageQueueMetadata(@NonNull final ClprLedgerId ledgerId) {
        requireNonNull(ledgerId);
        final var snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            return null;
        }

        final var state = snapshot.state();
        final var readableStates = state.getReadableStates(ClprService.NAME);
        if (!readableStates.contains(V0700ClprSchema.CLPR_MESSAGE_QUEUE_METADATA_STATE_ID)) {
            throw new IllegalStateException(
                    "CLPR message queue metadata state not found - service may not be properly initialized");
        }
        if (!(state instanceof VirtualMapState virtualMapState)) {
            throw new IllegalStateException("Unable to build Merkle proofs from a non-VirtualMap state");
        }

        return buildMerkleStateProof(
                virtualMapState,
                V0700ClprSchema.CLPR_MESSAGE_QUEUE_METADATA_STATE_ID,
                ClprLedgerId.PROTOBUF.toBytes(ledgerId));
    }

    /**
     * Returns the message queue metadata for the local ledger, or {@code null} if not found.
     *
     * <p> Retrieves metadata directly from the local state.
     *
     * @param ledgerId the ledger ID to query
     * @return the message queue metadata, or {@code null} if not found
     */
    @Nullable
    public ClprMessageQueueMetadata getLocalMessageQueueMetadata(@NonNull final ClprLedgerId ledgerId) {
        requireNonNull(ledgerId);
        final var snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            return null;
        }
        final var state = snapshot.state();
        final var readableStates = state.getReadableStates(ClprService.NAME);
        final ReadableKVState<ClprLedgerId, ClprMessageQueueMetadata> messageQueueMetadataReadableState =
                readableStates.get(V0700ClprSchema.CLPR_MESSAGE_QUEUE_METADATA_STATE_ID);
        return messageQueueMetadataReadableState.get(ledgerId);
    }

    /**
     * Retrieves a specific message from the CLPR message queue, or {@code null} if not found.
     *
     * @param messageKey the key identifying the message to retrieve
     * @return the message value, or {@code null} if the message does not exist or state is unavailable
     */
    @Nullable
    public ClprMessageValue getMessage(@NonNull final ClprMessageKey messageKey) {
        requireNonNull(messageKey);
        final var snapshot = latestSnapshot().orElse(null);
        if (snapshot == null) {
            return null;
        }

        final var state = snapshot.state();
        final var readableStates = state.getReadableStates(ClprService.NAME);
        final ReadableKVState<ClprMessageKey, ClprMessageValue> messageQueueMetadataReadableState =
                readableStates.get(V0700ClprSchema.CLPR_MESSAGES_STATE_ID);
        return messageQueueMetadataReadableState.get(messageKey);
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
            return null;
        }
        final var state = snapshot.state();
        final var readableStates = state.getReadableStates(ClprService.NAME);
        if (!readableStates.contains(V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID)) {
            return null;
        }
        final ReadableKVState<ClprLedgerId, ClprLedgerConfiguration> configsState =
                readableStates.get(V0700ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID);
        return configsState.get(ledgerId);
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
     * <p><b>Dev Mode Behavior:</b> Validates proof structure and root hash signature matching.
     * In production mode, validation is not yet implemented.</p>
     *
     * @param configTxn the transaction containing the state proof
     * @return {@code true} if the proof is structurally sound and its signature matches the computed root hash
     * @throws UnsupportedOperationException if dev mode is not enabled
     */
    public boolean validateStateProof(@NonNull final ClprSetLedgerConfigurationTransactionBody configTxn) {
        requireNonNull(configTxn);
        if (!clprConfig.devModeEnabled()) {
            throw new UnsupportedOperationException("State proof validation not available in production mode");
        }
        if (!configTxn.hasLedgerConfigurationProof()) {
            return false;
        }
        try {
            // verify the state proof structure and signature
            return StateProofVerifier.verify(configTxn.ledgerConfigurationProofOrThrow());
        } catch (final Exception e) {
            return false;
        }
    }

    public boolean clprEnabled() {
        return clprConfig.clprEnabled();
    }
}
