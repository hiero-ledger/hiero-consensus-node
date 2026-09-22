// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class WrappedRecordFileBlockHashesCalculatorTest {

    @Test
    void blockHeaderUsesFirstConsensusTimeAndRecordFileItemUsesBlockCreationTime() {
        final var blockCreationTime = new Timestamp(1_000L, 0);
        final var firstConsensusTime = new Timestamp(2_000L, 0);

        final var firstItem = new RecordStreamItem(
                Transaction.DEFAULT,
                TransactionRecord.newBuilder()
                        .consensusTimestamp(firstConsensusTime)
                        .build());

        final var input = new WrappedRecordFileBlockHashesComputationInput(
                1L,
                blockCreationTime,
                SemanticVersion.DEFAULT,
                Bytes.wrap(new byte[48]),
                Bytes.wrap(new byte[48]),
                List.of(firstItem),
                List.of(),
                1024 * 1024);

        final var result = WrappedRecordFileBlockHashesCalculator.computeWithItems(input);

        final var actualCreationTime =
                result.recordFileItem().recordFileOrThrow().creationTime();
        final var actualBlockTimestamp =
                result.headerItem().blockHeaderOrThrow().blockTimestamp();

        assertEquals(blockCreationTime, actualCreationTime, "RecordFileItem.creationTime must equal blockCreationTime");
        assertEquals(
                firstConsensusTime,
                actualBlockTimestamp,
                "BlockHeader.blockTimestamp must equal first consensus timestamp of the first item");
    }
}
