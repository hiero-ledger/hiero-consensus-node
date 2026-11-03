// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.MAX_PBJ_RECORD_SIZE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.RecordFileItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.hapi.streams.SidecarFile;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.RecordWithSidecars;
import com.hedera.services.bdd.junit.support.StreamFileAccess.RecordStreamData;
import java.util.List;
import org.junit.jupiter.api.Test;

class WrbRecordFileValidatorTest {
    private static final long BLOCK_NUMBER = 43;
    private final WrbRecordFileValidator subject = new WrbRecordFileValidator();

    @Test
    void matchesRecordAndSidecarWithItemsLargerThanTwoMiB() throws InvalidProtocolBufferException {
        final var payload = Bytes.wrap(new byte[3 * 1024 * 1024]);
        final var recordFile = recordFile(payload);
        final var sidecar = sidecar(payload);
        // Both disk files exceed the default PBJ per-field limit that previously discarded block 43.
        assertThrows(
                ParseException.class,
                () -> RecordStreamFile.PROTOBUF.parse(RecordStreamFile.PROTOBUF.toBytes(recordFile)));
        assertThrows(ParseException.class, () -> SidecarFile.PROTOBUF.parse(SidecarFile.PROTOBUF.toBytes(sidecar)));
        final var disk = diskData(recordFile, sidecar);
        assertDoesNotThrow(() -> subject.validateBlockVsRecords(List.of(block(recordFile, sidecar)), disk));
    }

    @Test
    void reportsRecordNormalizationFailureWithBlockNumberAndOriginalCause() throws InvalidProtocolBufferException {
        final var recordFile = recordFile(Bytes.wrap(new byte[MAX_PBJ_RECORD_SIZE + 1]));
        final var disk = diskData(recordFile, SidecarFile.DEFAULT);
        final var failure = assertThrows(
                IllegalStateException.class,
                () -> subject.validateBlockVsRecords(List.of(block(recordFile, SidecarFile.DEFAULT)), disk));
        assertTrue(failure.getMessage().contains("disk record file for block 43"));
        assertInstanceOf(ParseException.class, failure.getCause());
    }

    @Test
    void reportsSidecarNormalizationFailureInsteadOfSubstitutingEmptyBytes() throws InvalidProtocolBufferException {
        final var recordFile = recordFile(Bytes.EMPTY);
        final var sidecar = sidecar(Bytes.wrap(new byte[MAX_PBJ_RECORD_SIZE + 1]));
        final var disk = diskData(recordFile, sidecar);
        final var failure = assertThrows(
                IllegalStateException.class,
                () -> subject.validateBlockVsRecords(List.of(block(recordFile, sidecar)), disk));
        assertTrue(failure.getMessage().contains("disk sidecar for block 43"));
        assertInstanceOf(ParseException.class, failure.getCause());
    }

    private static RecordStreamFile recordFile(final Bytes payload) {
        return RecordStreamFile.newBuilder()
                .blockNumber(BLOCK_NUMBER)
                .recordStreamItems(RecordStreamItem.newBuilder()
                        .transaction(Transaction.newBuilder()
                                .signedTransactionBytes(payload)
                                .build())
                        .build())
                .build();
    }

    private static SidecarFile sidecar(final Bytes payload) {
        return SidecarFile.newBuilder()
                .sidecarRecords(TransactionSidecarRecord.newBuilder()
                        .bytecode(ContractBytecode.newBuilder()
                                .runtimeBytecode(payload)
                                .build())
                        .build())
                .build();
    }

    private static Block block(final RecordStreamFile recordFile, final SidecarFile sidecar) {
        return Block.newBuilder()
                .items(
                        BlockItem.newBuilder()
                                .blockHeader(BlockHeader.newBuilder()
                                        .number(BLOCK_NUMBER)
                                        .build())
                                .build(),
                        BlockItem.newBuilder()
                                .recordFile(RecordFileItem.newBuilder()
                                        .recordFileContents(recordFile)
                                        .sidecarFileContents(sidecar)
                                        .build())
                                .build())
                .build();
    }

    private static RecordStreamData diskData(final RecordStreamFile recordFile, final SidecarFile sidecar)
            throws InvalidProtocolBufferException {
        final var diskRecord = com.hedera.services.stream.proto.RecordStreamFile.parseFrom(
                RecordStreamFile.PROTOBUF.toBytes(recordFile).toByteArray());
        final var diskSidecar = com.hedera.services.stream.proto.SidecarFile.parseFrom(
                SidecarFile.PROTOBUF.toBytes(sidecar).toByteArray());
        return new RecordStreamData(
                List.of(new RecordWithSidecars(diskRecord, List.of(diskSidecar))), List.of(diskRecord));
    }
}
