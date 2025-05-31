// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.EntityType.TOPIC;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Translates a consensus topic create transaction into a {@link SingleTransactionRecord}.
 */
public class TopicCreateTranslator implements BlockTransactionPartsTranslator {
    private static final Logger log = LogManager.getLogger(TopicCreateTranslator.class);

    @Override
    public SingleTransactionRecord translate(
            @NonNull final BlockTransactionParts parts,
            @NonNull final BaseTranslator baseTranslator,
            @NonNull final List<StateChange> remainingStateChanges,
            @NonNull final List<TraceData> followingUnitTraces) {
        requireNonNull(parts);
        requireNonNull(baseTranslator);
        requireNonNull(remainingStateChanges);
        return baseTranslator.recordFrom(
                parts,
                (receiptBuilder, recordBuilder) -> {
                    if (parts.status() == SUCCESS) {
                        final var createdNum = baseTranslator.nextCreatedNum(TOPIC);
                        final var iter = remainingStateChanges.listIterator();
                        while (iter.hasNext()) {
                            final var stateChange = iter.next();
                            if (stateChange.hasMapUpdate()
                                    && stateChange
                                            .mapUpdateOrThrow()
                                            .keyOrThrow()
                                            .hasTopicIdKey()) {
                                final var topicId = stateChange
                                        .mapUpdateOrThrow()
                                        .keyOrThrow()
                                        .topicIdKeyOrThrow();
                                if (topicId.topicNum() == createdNum) {
                                    receiptBuilder.topicID(topicId);
                                    iter.remove();
                                    return;
                                }
                            }
                        }
                        log.error(
                                "No matching state change found for successful topic create with id {}",
                                parts.transactionIdOrThrow());
                    }
                },
                remainingStateChanges,
                followingUnitTraces);
    }
}
