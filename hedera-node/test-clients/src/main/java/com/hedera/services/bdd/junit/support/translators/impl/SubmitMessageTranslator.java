/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionPartsTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import edu.umd.cs.findbugs.annotations.NonNull;
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
            @NonNull final List<StateChange> remainingStateChanges) {
        return baseTranslator.recordFrom(parts, (receiptBuilder, recordBuilder) -> {
            if (parts.status() == SUCCESS) {
                parts.outputIfPresent(TransactionOutput.TransactionOneOfType.SUBMIT_MESSAGE)
                        .ifPresent(output -> recordBuilder.assessedCustomFees(
                                output.submitMessageOrThrow().assessedCustomFees()));
                receiptBuilder.topicRunningHashVersion(RUNNING_HASH_VERSION);
                final var iter = remainingStateChanges.listIterator();
                while (iter.hasNext()) {
                    final var stateChange = iter.next();
                    if (stateChange.hasMapUpdate()
                            && stateChange.mapUpdateOrThrow().valueOrThrow().hasTopicValue()) {
                        final var topic =
                                stateChange.mapUpdateOrThrow().valueOrThrow().topicValueOrThrow();
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
        });
    }
}
