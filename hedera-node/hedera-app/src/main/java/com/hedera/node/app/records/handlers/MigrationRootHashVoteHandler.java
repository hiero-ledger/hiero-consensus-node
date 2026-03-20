// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.handlers;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.WritableBlockRecordStore;
import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.ReadableRosterStore;

/**
 * Handles node votes for wrapped-record migration root-hash agreement.
 */
@Singleton
public class MigrationRootHashVoteHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(MigrationRootHashVoteHandler.class);

    private final BlockRecordManager blockRecordManager;

    @Inject
    public MigrationRootHashVoteHandler(@Nullable final BlockRecordManager blockRecordManager) {
        this.blockRecordManager = blockRecordManager;
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var nodeId = context.creatorInfo().nodeId();
        final var store = context.storeFactory().writableStore(WritableBlockRecordStore.class);
        if (store.isVotingComplete()) {
            log.info("Ignoring migration root hash vote from node{} because voting is already complete", nodeId);
            return;
        }

        final var op = context.body().migrationRootHashVoteOrThrow();
        log.info(
                "Migration root hash vote from node{}: previousWrappedRecordBlockRootHash={},"
                        + " wrappedIntermediatePreviousBlockRootHashes=[{}], wrappedIntermediateBlockRootsLeafCount={}",
                nodeId,
                op.previousWrappedRecordBlockRootHash().toHex(),
                op.wrappedIntermediatePreviousBlockRootHashes().stream()
                        .map(Bytes::toHex)
                        .collect(Collectors.joining(", ")),
                op.wrappedIntermediateBlockRootsLeafCount());
        if (!store.putVoteIfAbsent(nodeId, op)) {
            log.info("Ignoring duplicate migration root hash vote from node{}", nodeId);
            return;
        }

        final var rosterStore = context.storeFactory().readableStore(ReadableRosterStore.class);
        final var activeRoster = rosterStore.getActiveRoster();
        if (activeRoster == null || activeRoster.rosterEntries().isEmpty()) {
            return;
        }
        final var nodeWeight = activeRoster.rosterEntries().stream()
                .filter(entry -> entry.nodeId() == nodeId)
                .mapToLong(entry -> entry.weight())
                .findFirst()
                .orElse(0L);
        if (nodeWeight <= 0) {
            log.info(
                    "Ignoring migration root hash vote from node{} because it has non-positive weight in the active roster",
                    nodeId);
            return;
        }
        final var totalWeight = activeRoster.rosterEntries().stream()
                .mapToLong(RosterEntry::weight)
                .sum();
        if (totalWeight <= 0) {
            log.info(
                    "Ignoring migration root hash vote from node{} because total weight of the active roster is non-positive",
                    nodeId);
            return;
        }

        final var weightByNode = activeRoster.rosterEntries().stream()
                .collect(Collectors.toMap(RosterEntry::nodeId, RosterEntry::weight));
        final var tallyByVote = new HashMap<MigrationRootHashVoteTransactionBody, Long>();
        for (final var vote : store.votes()) {
            final var votingNodeId = vote.nodeIdOrElse(NodeId.DEFAULT).id();
            final var votingWeight = weightByNode.getOrDefault(votingNodeId, 0L);
            if (votingWeight > 0) {
                tallyByVote.merge(vote.voteOrElse(op), votingWeight, Long::sum);
            }
        }
        final var tallyWeight = Optional.ofNullable(tallyByVote.get(op)).orElse(0L);
        log.info(
                "Recorded migration root hash vote from node{} (nodeWeight={}, tallyWeight={}, totalWeight={})",
                nodeId,
                nodeWeight,
                tallyWeight,
                totalWeight);
        if (tallyWeight * 3 <= totalWeight) {
            return;
        }

        var previousWrappedRecordBlockRootHash = op.previousWrappedRecordBlockRootHash();
        final var hasher = new IncrementalStreamingHasher(
                sha384DigestOrThrow(),
                op.wrappedIntermediatePreviousBlockRootHashes().stream()
                        .map(Bytes::toByteArray)
                        .toList(),
                op.wrappedIntermediateBlockRootsLeafCount());
        log.info("store wrapped hashes: {}", store.wrappedHashesInOrder());
        for (final var queuedHashes : store.wrappedHashesInOrder()) {
            final var allPrevBlocksRootHash = Bytes.wrap(hasher.computeRootHash());
            final var blockRootHash = BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(
                    previousWrappedRecordBlockRootHash,
                    allPrevBlocksRootHash,
                    WrappedRecordFileBlockHashes.newBuilder()
                            .consensusTimestampHash(queuedHashes.consensusTimestampHash())
                            .outputItemsTreeRootHash(queuedHashes.outputItemsTreeRootHash())
                            .build());
            log.info(
                    "Applied queued hash for block{}: consensusTimestampHash={}, outputItemsTreeRootHash={},"
                            + " allPrevBlocksRootHash={}, blockRootHash={}",
                    queuedHashes.blockNumber(),
                    queuedHashes.consensusTimestampHash().toHex(),
                    queuedHashes.outputItemsTreeRootHash().toHex(),
                    allPrevBlocksRootHash.toHex(),
                    blockRootHash.toHex());
            hasher.addNodeByHash(blockRootHash.toByteArray());
            previousWrappedRecordBlockRootHash = blockRootHash;
        }
        final var finalizedIntermediateState = hasher.intermediateHashingState();
        final var finalizedLeafCount = hasher.leafCount();
        store.applyFinalizedValuesAndMarkComplete(
                previousWrappedRecordBlockRootHash, finalizedIntermediateState, finalizedLeafCount);
        if (blockRecordManager != null) {
            blockRecordManager.syncFinalizedMigrationHashes(
                    previousWrappedRecordBlockRootHash, finalizedIntermediateState, finalizedLeafCount);
        }
        log.info("Migration root hash voting finalized after node{} vote, >1/3 threshold reached", nodeId);
        log.info(
                "Finalized migration root hash vote values: previousWrappedRecordBlockRootHash={},"
                        + " wrappedIntermediatePreviousBlockRootHashes=[{}], wrappedIntermediateBlockRootsLeafCount={}",
                previousWrappedRecordBlockRootHash.toHex(),
                finalizedIntermediateState.stream().map(Bytes::toHex).collect(Collectors.joining(", ")),
                finalizedLeafCount);
    }
}
