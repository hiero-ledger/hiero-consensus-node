// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionRecordParityValidatorTest {

    private static com.hedera.services.stream.proto.RecordStreamFile protoRecordFileWithTimestamp(
            long seconds, int nanos) {
        final var record = TransactionRecord.newBuilder()
                .setConsensusTimestamp(com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                        .setSeconds(seconds)
                        .setNanos(nanos)
                        .build())
                .build();
        final var item = com.hedera.services.stream.proto.RecordStreamItem.newBuilder()
                .setRecord(record)
                .build();
        return com.hedera.services.stream.proto.RecordStreamFile.newBuilder()
                .addRecordStreamItems(item)
                .build();
    }

    @Test
    void wrbCrossValidationPassesWhenTimestampsMatch() {
        final var ts = new Timestamp(1_000_000L, 0);
        final var wrbBlock = BlockTestHelpers.wrbBlockWithTimestamp(1, ts);

        final var diskRecord = protoRecordFileWithTimestamp(1_000_000L, 0);
        final var data = new StreamFileAccess.RecordStreamData(
                List.of(new RecordWithSidecars(diskRecord, List.of())), List.of(diskRecord));

        final var validator = new TransactionRecordParityValidator().withTargetNetwork(0L, 0L);
        assertDoesNotThrow(() -> validator.validateBlockVsRecords(List.of(wrbBlock), data));
    }

    @Test
    void wrbCrossValidationFailsOnItemCountMismatch() {
        final var ts = new Timestamp(1_000_000L, 0);
        final var wrbBlock = BlockTestHelpers.wrbBlockWithTimestamp(1, ts);

        final var record1 = TransactionRecord.newBuilder()
                .setConsensusTimestamp(com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                        .setSeconds(1_000_000L)
                        .setNanos(0)
                        .build())
                .build();
        final var record2 = TransactionRecord.newBuilder()
                .setConsensusTimestamp(com.hederahashgraph.api.proto.java.Timestamp.newBuilder()
                        .setSeconds(1_000_001L)
                        .setNanos(0)
                        .build())
                .build();
        final var diskRecord = com.hedera.services.stream.proto.RecordStreamFile.newBuilder()
                .addRecordStreamItems(com.hedera.services.stream.proto.RecordStreamItem.newBuilder()
                        .setRecord(record1)
                        .build())
                .addRecordStreamItems(com.hedera.services.stream.proto.RecordStreamItem.newBuilder()
                        .setRecord(record2)
                        .build())
                .build();
        final var data = new StreamFileAccess.RecordStreamData(
                List.of(new RecordWithSidecars(diskRecord, List.of())), List.of(diskRecord));

        final var validator = new TransactionRecordParityValidator().withTargetNetwork(0L, 0L);
        final var err =
                assertThrows(AssertionError.class, () -> validator.validateBlockVsRecords(List.of(wrbBlock), data));
        assertTrue(err.getMessage().contains("record stream items"));
    }

    @Test
    void wrbWithNoMatchingDiskRecordIsSkipped() {
        final var ts = new Timestamp(1_000_000L, 0);
        final var wrbBlock = BlockTestHelpers.wrbBlockWithTimestamp(1, ts);

        final var diskRecord = protoRecordFileWithTimestamp(9_999_999L, 0);
        final var data = new StreamFileAccess.RecordStreamData(
                List.of(new RecordWithSidecars(diskRecord, List.of())), List.of(diskRecord));

        final var validator = new TransactionRecordParityValidator().withTargetNetwork(0L, 0L);
        assertDoesNotThrow(() -> validator.validateBlockVsRecords(List.of(wrbBlock), data));
    }

    @Test
    void wrbOnlyBlocksWithEmptyRecordStreamData() {
        final var wrbBlock = BlockTestHelpers.wrbBlock(1);
        final var data = new StreamFileAccess.RecordStreamData(List.of(), List.of());

        final var validator = new TransactionRecordParityValidator().withTargetNetwork(0L, 0L);
        assertDoesNotThrow(() -> validator.validateBlockVsRecords(List.of(wrbBlock), data));
    }

    @Test
    void normalBlocksStillValidate() {
        final var data = new StreamFileAccess.RecordStreamData(List.of(), List.of());
        final var validator = new TransactionRecordParityValidator().withTargetNetwork(0L, 0L);
        assertDoesNotThrow(() -> validator.validateBlockVsRecords(List.of(BlockTestHelpers.normalBlock(1)), data));
    }
}
