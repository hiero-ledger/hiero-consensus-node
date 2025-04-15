// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats.v6;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.node.app.records.impl.producers.BlockRecordFormat;
import com.hedera.node.app.records.impl.producers.SerializedSingleTransactionRecord;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;

/**
 * RecordFileWriter for V6 record file format.
 */
public final class BlockRecordFormatV6 implements BlockRecordFormat {
    private static final long RECORD_STREAM_OBJECT_CLASS_ID = 0xe370929ba5429d8bL;
    public static final int RECORD_STREAM_OBJECT_CLASS_VERSION = 1;
    /** The version of this format */
    public static final int VERSION_6 = 6;
    /** The header bytes to hash before each item in stream */
    private static final byte[] RECORD_STREAM_OBJECT_HEADER;
    /** The header bytes to hash before each hash */
    static final byte[] HASH_HEADER;
    /** A singleton instance */
    public static final BlockRecordFormat INSTANCE = new BlockRecordFormatV6();

    static {
        try {
            // compute Hash object header, the hash header is not the usual SelfSerializable Hash object.
            // @see com.swirlds.common.crypto.engine.RunningHashProvider.updateForHash
            // @see com.swirlds.common.crypto.HashBuilder.update(long)
            // @see com.swirlds.common.crypto.HashBuilder.update(int)
            ByteBuffer buf = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(Hash.CLASS_ID);
            buf.putInt(new Hash().getVersion());
            HASH_HEADER = buf.array();
            assert Arrays.equals(HASH_HEADER, HexFormat.of().parseHex("1e7451a283da22f401000000"))
                    : "Hash object header is not the expected 1e7451a283da22f401000000";
            // compute RecordStreamObject header
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            SerializableDataOutputStream sout = new SerializableDataOutputStream(bout);
            bout.reset();
            sout.writeLong(RECORD_STREAM_OBJECT_CLASS_ID);
            sout.writeInt(RECORD_STREAM_OBJECT_CLASS_VERSION);
            RECORD_STREAM_OBJECT_HEADER = bout.toByteArray();
            assert Arrays.equals(RECORD_STREAM_OBJECT_HEADER, HexFormat.of().parseHex("e370929ba5429d8b00000001"))
                    : "RecordStreamObject header is not the expected e370929ba5429d8b00000001";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BlockRecordFormatV6() {
        // prohibit instantiation
    }

    @Override
    public SerializedSingleTransactionRecord serialize(
            @NonNull final SingleTransactionRecord singleTransactionRecord,
            final long blockNumber,
            @NonNull final SemanticVersion hapiVersion) {
        try {
            // create RecordStreamItem
            final RecordStreamItem recordStreamItem = new RecordStreamItem(
                    singleTransactionRecord.transaction(), singleTransactionRecord.transactionRecord());
            // serialize in format for hashing
            // the format is SelfSerializable header and then protobuf fields in reverse order with no protobuf tag
            final var bout = new ByteArrayOutputStream();
            bout.write(RECORD_STREAM_OBJECT_HEADER);
            WritableStreamingData out = new WritableStreamingData(bout);
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
            // serialize in protobuf format
            final Bytes protobufItemBytes = RecordStreamItem.PROTOBUF.toBytes(recordStreamItem);
            // serialize sidecar items to protobuf
            List<Bytes> sideCarItems = singleTransactionRecord.transactionSidecarRecords().stream()
                    .map(TransactionSidecarRecord.PROTOBUF::toBytes)
                    .toList();
            // return SerializedSingleTransactionRecord
            return new SerializedSingleTransactionRecord(
                    Bytes.wrap(bout.toByteArray()),
                    protobufItemBytes,
                    sideCarItems,
                    singleTransactionRecord.transactionSidecarRecords());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Bytes computeNewRunningHash(
            @NonNull final Bytes startRunningHash,
            @NonNull final List<SerializedSingleTransactionRecord> serializedItems) {
        try {
            byte[] previousHash = startRunningHash.toByteArray();
            final MessageDigest messageDigest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
            // use for-i loop as it is faster
            final var count = serializedItems.size();
            //noinspection ForLoopReplaceableByForEach
            for (int i = 0; i < count; i++) {
                final var serializedItem = serializedItems.get(i);
                // first hash the item
                serializedItem.hashSerializedRecordStreamItem().writeTo(messageDigest);
                final byte[] serializedItemHash = messageDigest.digest();
                // now hash the previous hash and the item hash
                messageDigest.update(HASH_HEADER);
                messageDigest.update(previousHash);
                messageDigest.update(HASH_HEADER);
                messageDigest.update(serializedItemHash);
                previousHash = messageDigest.digest();
            }
            return Bytes.wrap(previousHash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
