// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats.v6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A Record File Version 6 Reader that can be used in tests to read record files and validate then and return the contents
 */
@SuppressWarnings("DataFlowIssue")
public class BlockRecordReaderV6 {
    private static final long RECORD_STREAM_OBJECT_CLASS_ID = 0xe370929ba5429d8bL;
    public static final int RECORD_STREAM_OBJECT_CLASS_VERSION = 1;

    /** The version of this format */
    public static final int VERSION = 6;
    /** The header bytes to hash before each item in stream */
    private static final byte[] RECORD_STREAM_OBJECT_HEADER;
    /** The header bytes to hash before each hash */
    private static final byte[] HASH_HEADER;

    static {
        try {
            // compute Hash object header, the hash header is not the usual SelfSerializable Hash object.
            // @see org.hiero.base.crypto.engine.RunningHashProvider.updateForHash
            // @see org.hiero.base.crypto.HashBuilder.update(long)
            // @see org.hiero.base.crypto.HashBuilder.update(int)
            ByteBuffer buf = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(Hash.CLASS_ID);
            buf.putInt(new Hash().getVersion());
            HASH_HEADER = buf.array();
            if (!Arrays.equals(HASH_HEADER, HexFormat.of().parseHex("1e7451a283da22f401000000"))) {
                throw new IllegalStateException("Hash object header is not the expected 1e7451a283da22f401000000");
            }
            // compute RecordStreamObject header
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            SerializableDataOutputStream sout = new SerializableDataOutputStream(bout);
            bout.reset();
            sout.writeLong(RECORD_STREAM_OBJECT_CLASS_ID);
            sout.writeInt(RECORD_STREAM_OBJECT_CLASS_VERSION);
            RECORD_STREAM_OBJECT_HEADER = bout.toByteArray();
            if (!Arrays.equals(RECORD_STREAM_OBJECT_HEADER, HexFormat.of().parseHex("e370929ba5429d8b00000001"))) {
                throw new IllegalStateException(
                        "RecordStreamObject header is not the expected e370929ba5429d8b00000001");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Read and parse a record stream file and validate the file version.
     *
     * @param filePath The path to the record stream file
     * @return The parsed record stream file
     * @throws Exception If there is an error reading the file
     */
    public static RecordStreamFile read(@NonNull final Path filePath) throws Exception {
        if (filePath.getFileName().toString().endsWith(".rcd.gz")) {
            try (final ReadableStreamingData in =
                    new ReadableStreamingData(new GZIPInputStream(Files.newInputStream(filePath)))) {
                int version = in.readInt();
                assertEquals(VERSION, version, "File version does not match, on file " + filePath);
                return RecordStreamFile.PROTOBUF.parse(in);
            }
        } else if (filePath.getFileName().toString().endsWith(".rcd")) {
            try (final ReadableStreamingData in = new ReadableStreamingData(Files.newInputStream(filePath))) {
                int version = in.readInt();
                assertEquals(VERSION, version);
                return RecordStreamFile.PROTOBUF.parse(in);
            }
        } else {
            fail("Unknown file type: " + filePath.getFileName());
            return null;
        }
    }

    /**
     * Read and parse a record stream file and validate the file version.
     *
     * @param uncompressedData The uncompressed record file data
     * @return The parsed record stream file
     * @throws Exception If there is an error reading the file
     */
    public static RecordStreamFile read(byte[] uncompressedData) throws Exception {
        final BufferedData in = BufferedData.wrap(uncompressedData);
        int version = in.readInt();
        assertEquals(VERSION, version, "File version does not match");
        return RecordStreamFile.PROTOBUF.parse(in);
    }

    /**
     * Compute the running hashes for a record stream file and validate that the final running hash matches
     * the one written in the file.
     *
     * @param recordStreamFile The parsed record stream file
     * @throws Exception If there is an error validating hashes
     */
    public static void validateHashes(@NonNull final RecordStreamFile recordStreamFile) throws Exception {
        final var startRunningHash = recordStreamFile.startObjectRunningHash().hash();
        var previousHash = startRunningHash.toByteArray();
        final var messageDigest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
        final var bout = new ByteArrayOutputStream();
        for (final var recordStreamItem : recordStreamFile.recordStreamItems()) {
            // serialize in format for hashing
            // the format is SelfSerializable header and then protobuf fields in reverse order with no protobuf tag
            bout.reset();
            bout.write(RECORD_STREAM_OBJECT_HEADER);
            final var out = new WritableStreamingData(bout);
            // [field 2] - record
            if (recordStreamItem.record() != null) {
                Bytes transactionRecordProtobufBytes = TransactionRecord.PROTOBUF.toBytes(recordStreamItem.record());
                out.writeInt((int) transactionRecordProtobufBytes.length());
                transactionRecordProtobufBytes.writeTo(out);
            }
            // [field 1] - transaction
            if (recordStreamItem.transaction() != null) {
                Bytes transactionProtobufBytes = Transaction.PROTOBUF.toBytes(recordStreamItem.transaction());
                out.writeInt((int) transactionProtobufBytes.length());
                transactionProtobufBytes.writeTo(out);
            }
            // hash the serialized item
            final var serializedItemHash = messageDigest.digest(bout.toByteArray());
            // now hash the previous hash and the item hash
            messageDigest.update(HASH_HEADER);
            messageDigest.update(previousHash);
            messageDigest.update(HASH_HEADER);
            messageDigest.update(serializedItemHash);
            previousHash = messageDigest.digest();
        }
        final var newEndRunningHash = Bytes.wrap(previousHash);
        final var endRunningHash = recordStreamFile.endObjectRunningHash().hash();
        assertEquals(endRunningHash.toHex(), newEndRunningHash.toHex(), "End running hash does not match");
    }
}
