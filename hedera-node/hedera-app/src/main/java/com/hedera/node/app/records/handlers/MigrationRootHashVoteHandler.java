// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.handlers;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.records.WritableMigrationRootHashStore;
import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
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

    @Inject
    public MigrationRootHashVoteHandler() {
        // Dagger2
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
        final var store = context.storeFactory().writableStore(WritableMigrationRootHashStore.class);
        if (store.isVotingComplete()) {
            return;
        }

        final var op = context.body().migrationRootHashVoteOrThrow();
        final var nodeId = context.creatorInfo().nodeId();
        if (!store.putVoteIfAbsent(nodeId, op)) {
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
            return;
        }
        final var totalWeight =
                activeRoster.rosterEntries().stream().mapToLong(entry -> entry.weight()).sum();
        if (totalWeight <= 0) {
            return;
        }

        final var voteHash = hashOf(op);
        store.addToTally(voteHash, nodeWeight);
        final var tallyWeight = Optional.ofNullable(store.getTally(voteHash))
                .map(tally -> tally.totalWeight())
                .orElse(0L);
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
        for (final var queuedHashes : store.queuedHashesInOrder()) {
            final var allPrevBlocksRootHash = Bytes.wrap(hasher.computeRootHash());
            final var blockRootHash = BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(
                    previousWrappedRecordBlockRootHash,
                    allPrevBlocksRootHash,
                    WrappedRecordFileBlockHashes.newBuilder()
                            .consensusTimestampHash(queuedHashes.consensusTimestampHash())
                            .outputItemsTreeRootHash(queuedHashes.outputItemsTreeRootHash())
                            .build());
            hasher.addNodeByHash(blockRootHash.toByteArray());
            previousWrappedRecordBlockRootHash = blockRootHash;
        }
        final var changed = store.applyFinalizedValuesAndMarkComplete(
                voteHash,
                previousWrappedRecordBlockRootHash,
                hasher.intermediateHashingState(),
                hasher.leafCount());
        log.info(
                "Migration root hash voting finalized after node{} vote, >1/3 threshold reached (changedBlockInfo={})",
                nodeId,
                changed);
    }

    private static Bytes hashOf(@NonNull final com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody vote) {
        requireNonNull(vote);
        final var digest = sha384DigestOrThrow();
        return Bytes.wrap(digest.digest(
                com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody.PROTOBUF
                        .toBytes(vote)
                        .toByteArray()));
    }
}
