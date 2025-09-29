// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.recordcache.BlockRecordSource;
import com.hedera.node.app.state.recordcache.LegacyListRecordSource;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.steps.ParentTxn;
import com.hedera.node.config.types.StreamMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;

/**
 * A temporary wrapper class as we transition from the V6 record stream to the block stream;
 * includes at least one of the V6 record stream and/or the block stream output from a user transaction.
 *
 * @param blockRecordSource maybe the block stream output items
 * @param recordSource maybe record source derived from the V6 record stream items
 * @param lastAssignedConsensusTime the last consensus time assigned to a transaction in the output
 */
public record HandleOutput(
        @Nullable BlockRecordSource blockRecordSource,
        @Nullable RecordSource recordSource,
        @NonNull Instant lastAssignedConsensusTime) {
    public HandleOutput {
        if (blockRecordSource == null) {
            requireNonNull(recordSource);
        }
        requireNonNull(lastAssignedConsensusTime);
    }

    /**
     * Returns a stream of a single {@link ResponseCodeEnum#FAIL_INVALID} record
     * for the given user transaction.
     *
     * @param parentTxn the user transaction to fail
     * @param exchangeRates the active exchange rate set
     * @param streamMode the stream mode
     * @param recordCache the cache to track the generated records in
     * @return the failure record
     */
    public static HandleOutput failInvalidStreamItems(
            @NonNull final ParentTxn parentTxn,
            @NonNull final ExchangeRateSet exchangeRates,
            @NonNull final StreamMode streamMode,
            @NonNull final HederaRecordCache recordCache) {
        requireNonNull(parentTxn);
        requireNonNull(exchangeRates);
        requireNonNull(streamMode);

        // The stack for the user txn should never be committed
        parentTxn.stack().rollbackFullStack();

        RecordSource cacheableRecordSource = null;
        final RecordSource recordSource;
        if (streamMode != BLOCKS) {
            final var failInvalidBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);
            HandleWorkflow.initializeBuilderInfo(failInvalidBuilder, parentTxn.txnInfo(), exchangeRates)
                    .status(FAIL_INVALID)
                    .consensusTimestamp(parentTxn.consensusNow());
            final var failInvalidRecord = failInvalidBuilder.build();
            cacheableRecordSource = recordSource = new LegacyListRecordSource(
                    List.of(failInvalidRecord),
                    List.of(new RecordSource.IdentifiedReceipt(
                            failInvalidRecord.transactionRecord().transactionIDOrThrow(),
                            failInvalidRecord.transactionRecord().receiptOrThrow())));
        } else {
            recordSource = null;
        }
        final BlockRecordSource blockRecordSource;
        if (streamMode != RECORDS) {
            final List<BlockStreamBuilder.Output> outputs = new LinkedList<>();
            final var failInvalidBuilder = new BlockStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);
            HandleWorkflow.initializeBuilderInfo(failInvalidBuilder, parentTxn.txnInfo(), exchangeRates)
                    .status(FAIL_INVALID)
                    .consensusTimestamp(parentTxn.consensusNow());
            outputs.add(failInvalidBuilder.build(true, null));
            cacheableRecordSource = blockRecordSource = new BlockRecordSource(outputs);
        } else {
            blockRecordSource = null;
        }

        recordCache.addRecordSource(
                parentTxn.creatorInfo().nodeId(),
                requireNonNull(parentTxn.txnInfo().transactionID()),
                HederaRecordCache.DueDiligenceFailure.NO,
                requireNonNull(cacheableRecordSource));
        return new HandleOutput(blockRecordSource, recordSource, parentTxn.consensusNow());
    }

    public @NonNull RecordSource recordSourceOrThrow() {
        return requireNonNull(recordSource);
    }

    public @NonNull BlockRecordSource blockRecordSourceOrThrow() {
        return requireNonNull(blockRecordSource);
    }

    public @NonNull RecordSource preferringBlockRecordSource() {
        return blockRecordSource != null ? blockRecordSource : requireNonNull(recordSource);
    }
}
