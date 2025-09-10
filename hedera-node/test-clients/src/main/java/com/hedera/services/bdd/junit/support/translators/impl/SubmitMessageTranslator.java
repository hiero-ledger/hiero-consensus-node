// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.SubmitMessageTraceData;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Translates a consensus submit message into a {@link SingleTransactionRecord}.
 */
public class SubmitMessageTranslator implements BlockTransactionPartsTranslator {
    private static final Logger log = LogManager.getLogger(SubmitMessageTranslator.class);

    // For explanation about this constant value, see
    // https://github.com/hashgraph/hedera-protobufs/blob/pbj-storage-spec-review/block/stream/output/consensus_service.proto#L6
    private static final long RUNNING_HASH_VERSION = 3L;

    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @Nullable final List<TraceData> tracesSoFar,
            @NonNull final List<TraceData> followingUnitTraces) {
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    if (parts.status() == SUCCESS) {
                        recordBuilder.assessedCustomFees(parts.assessedCustomFees());
                        receiptBuilder.topicRunningHashVersion(RUNNING_HASH_VERSION);

                        // Within batch transactions (inner or their children), state changes from earlier transactions
                        // can be overwritten by subsequent transactions in the same batch (e.g., multiple msg submits).
                        // Therefore, construct the record from trace data when available.
                        if (parts.isInnerBatchTxn()) {
                            final var maybeTraceData = maybeSubmitMessageTraceData(tracesSoFar);
                            if (maybeTraceData != null) {
                                receiptBuilder.topicSequenceNumber(maybeTraceData.sequenceNumber());
                                receiptBuilder.topicRunningHash(maybeTraceData.runningHash());
                            }
                            return;
                        }

                        final var iter = remainingStateChanges.listIterator();
                        while (iter.hasNext()) {
                            final var stateChange = iter.next();
                            if (stateChange.hasMapUpdate()
                                    && stateChange
                                            .mapUpdateOrThrow()
                                            .valueOrThrow()
                                            .hasTopicValue()) {
                                final var topic = stateChange
                                        .mapUpdateOrThrow()
                                        .valueOrThrow()
                                        .topicValueOrThrow();
                                receiptBuilder.topicSequenceNumber(topic.sequenceNumber());
                                receiptBuilder.topicRunningHash(topic.runningHash());
                                iter.remove();
                                return;
                            }
                        }
                        log.error(
                                "No topic state change found for successful submit message with id {}",
                                parts.transactionIdOrThrow());
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }

    private SubmitMessageTraceData maybeSubmitMessageTraceData(final List<TraceData> tracesSoFar) {
        SubmitMessageTraceData result = null;
        if (tracesSoFar != null) {
            // Start from the end of the list
            for (int i = tracesSoFar.size() - 1; i >= 0; i--) {
                final var trace = tracesSoFar.get(i);
                if (trace.hasSubmitMessageTraceData()) {
                    result = trace.submitMessageTraceData();
                    tracesSoFar.remove(i);
                    break;
                }
            }
        }
        return result;
    }
}
