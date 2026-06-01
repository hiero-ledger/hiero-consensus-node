// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stateful translator that converts a stream of {@link Block}s into {@link SingleTransactionRecord}s,
 * handling genesis detection automatically.
 *
 * <p>One instance should be used per subscription so that genesis state and alias/nonce tracking
 * inside {@link BlockTransactionalUnitTranslator} persist across blocks.
 */
public class BlockRecordTranslator {
    private final BlockTransactionalUnitTranslator translator;
    private final RoleFreeBlockUnitSplit splitter;
    private boolean foundGenesis = false;

    public BlockRecordTranslator(final long shard, final long realm) {
        this.translator = new BlockTransactionalUnitTranslator(shard, realm);
        this.splitter = new RoleFreeBlockUnitSplit();
    }

    /**
     * Translates {@code block} into {@link SingleTransactionRecord}s — scanning for genesis on the
     * first call — and feeds each record to {@code consumer}.
     */
    public void forEachRecord(@NonNull final Block block, @NonNull final Consumer<SingleTransactionRecord> consumer) {
        if (!foundGenesis) {
            foundGenesis = translator.scanBlockForGenesis(block);
        }
        for (final var unit : splitter.split(block)) {
            for (final var record : translator.translate(unit.withBatchTransactionParts())) {
                consumer.accept(record);
            }
        }
    }

    /**
     * Converts a {@link SingleTransactionRecord} into a proto {@link RecordStreamItem}.
     */
    public static RecordStreamItem toRecordStreamItem(@NonNull final SingleTransactionRecord record) {
        return RecordStreamItem.newBuilder()
                .setTransaction(fromPbj(record.transaction()))
                .setRecord(fromPbj(record.transactionRecord()))
                .build();
    }

    /**
     * Converts the PBJ sidecar records carried by {@code record} into proto-typed
     * {@link TransactionSidecarRecord}s.
     */
    public static List<TransactionSidecarRecord> protoSidecarsOf(@NonNull final SingleTransactionRecord record) {
        return record.transactionSidecarRecords().stream()
                .map(s -> pbjToProto(
                        s, com.hedera.hapi.streams.TransactionSidecarRecord.class, TransactionSidecarRecord.class))
                .toList();
    }
}
