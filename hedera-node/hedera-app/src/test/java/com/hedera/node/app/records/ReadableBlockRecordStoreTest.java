// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static com.hedera.hapi.node.base.Timestamp.newBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ReadableBlockRecordStoreTest {

    @Test
    void constructorThrowsOnNullParam() {
        //noinspection DataFlowIssue
        Assertions.assertThatThrownBy(() -> new ReadableBlockRecordStore(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void lastBlockInfoRetrieved() {
        // Given
        final var timestamp1 = newBuilder().seconds(1_234_567L).nanos(23456).build();
        final var timestamp2 = newBuilder()
                .seconds(1_234_568L) // 1 second later
                .nanos(13579)
                .build();

        final var expectedBlockInfo = BlockInfo.newBuilder()
                .firstConsTimeOfLastBlock(timestamp1)
                .lastBlockNumber(25)
                .blockHashes(Bytes.wrap("12345"))
                .consTimeOfLastHandledTxn(timestamp2)
                .migrationRecordsStreamed(true)
                .build();

        final var blockState = new MapReadableStates(Map.of(
                V0490BlockRecordSchema.BLOCK_INFO_STATE_KEY,
                new FunctionReadableSingletonState<>(
                        BlockRecordService.NAME,
                        V0490BlockRecordSchema.BLOCK_INFO_STATE_KEY,
                        () -> expectedBlockInfo)));
        final var subject = new ReadableBlockRecordStore(blockState);

        // When
        final var result = subject.getLastBlockInfo();

        // Then
        assertThat(result).isEqualTo(expectedBlockInfo);
    }
}
