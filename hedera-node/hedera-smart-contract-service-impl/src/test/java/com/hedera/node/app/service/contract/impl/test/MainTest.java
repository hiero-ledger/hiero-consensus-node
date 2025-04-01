package com.hedera.node.app.service.contract.impl.test;

import com.hedera.hapi.block.stream.protoc.Block;
import com.hedera.hapi.block.stream.protoc.BlockItem;
import com.hedera.node.app.hapi.utils.exports.FileCompressionUtils;
import com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils;
import com.hedera.services.stream.proto.RecordStreamFile;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

public class MainTest {

    @Test
    void test() throws IOException {
        Path dir = Paths.get("/Users/glibkozyryatskyy/Desktop/");
        // -------------------- record
        // ticket fail
        Pair<Integer, Optional<RecordStreamFile>> recordFile0 = readRecord(
                dir.resolve("2025-03-06T00_14_10.095626000Z.rcd.gz"));
        RecordStreamItem record0 = recordFile0.getRight().get().getRecordStreamItemsList().get(4);
        // test fail
        Pair<Integer, Optional<RecordStreamFile>> recordFile1 = readRecord(
                dir.resolve("2025-04-01T10_04_16.553353000Z.rcd.gz"));
        RecordStreamItem record1 = recordFile1.getRight().get().getRecordStreamItemsList().get(2);
        // test success
        Pair<Integer, Optional<RecordStreamFile>> recordFile2 = readRecord(
                dir.resolve("2025-04-01T10_14_00.004854000Z.rcd.gz"));
        RecordStreamItem record2 = recordFile2.getRight().get().getRecordStreamItemsList().get(2);

        // -------------------- block
        // ticket fail
        final var block0 = readBlock(dir.resolve("000000000000000000000000000036394120.blk.gz"));
        BlockItem item0 = block0.getRight().get().getItems(24);
        // test fail
        final var block1 = readBlockWoVersion(dir.resolve("000000000000000000000000000000000003.blk.gz"));
        BlockItem item1 = block1.getRight().get().getItems(5);
        // test success
        final var block2 = readBlockWoVersion(dir.resolve("000000000000000000000000000000000003 2.blk.gz"));
        BlockItem item2 = block2.getRight().get().getItems(5);

        System.out.println(" DONE -------------------------------------------------------");
    }

    public static void printTicketFiles() throws IOException {
        Path dir = Paths.get("/Users/glibkozyryatskyy/Desktop/");
        // -------------------- record
        Pair<Integer, Optional<RecordStreamFile>> recordFile = readRecord(
                dir.resolve("2025-03-06T00_14_10.095626000Z.rcd.gz"));
        TransactionRecord record = recordFile.getRight().get().getRecordStreamItemsList().get(4).getRecord();
        System.out.println(record);
        final var contractId = record.getContractCreateResult().getContractID();
        System.out.println(contractId);
        // -------------------- block
        final var block = readBlock(dir.resolve("000000000000000000000000000036394120.blk.gz"));
        BlockItem trxResult = block.getRight().get().getItems(23);
        System.out.println(" transaction_result -------------------------------------------------------");
        System.out.println(trxResult);
        BlockItem trxOut = block.getRight().get().getItems(24);
        System.out.println(" transaction_output -------------------------------------------------------");
        System.out.println(trxOut);
        System.out.println(" DONE -------------------------------------------------------");
    }

    public static Pair<Integer, Optional<Block>> readBlock(final Path blockFile)
            throws IOException {
        System.out.println("========================" + Files.exists(blockFile));
        final var uncompressedFileContents = FileCompressionUtils.readUncompressedFileBytes(blockFile.toString());
        final var recordFileVersion =
                ByteBuffer.wrap(uncompressedFileContents, 0, 4).getInt();
        final var file = Block.parseFrom(
                ByteBuffer.wrap(uncompressedFileContents, 4, uncompressedFileContents.length - 4));
        return Pair.of(0, Optional.ofNullable(file));
    }

    public static Pair<Integer, Optional<Block>> readBlockWoVersion(final Path blockFile)
            throws IOException {
        System.out.println("========================" + Files.exists(blockFile));
        final var uncompressedFileContents = FileCompressionUtils.readUncompressedFileBytes(blockFile.toString());
        final var file = Block.parseFrom(uncompressedFileContents);
        return Pair.of(0, Optional.ofNullable(file));
    }

    private static Pair<Integer, Optional<RecordStreamFile>> readRecord(Path recordFile) throws IOException {
        System.out.println("========================" + Files.exists(recordFile));
        return RecordStreamingUtils.readRecordStreamFile(recordFile.toString());
    }
}
