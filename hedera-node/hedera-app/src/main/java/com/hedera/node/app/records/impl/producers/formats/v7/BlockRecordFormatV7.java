// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats.v7;

import static com.hedera.hapi.streams.schema.RecordStreamFileSchema.RECORD_STREAM_ITEMS;
import static com.hedera.hapi.streams.schema.RecordStreamFileSchema.START_OBJECT_RUNNING_HASH;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeMessage;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.HashObject;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.node.app.records.impl.producers.BlockRecordFormat;
import com.hedera.node.app.records.impl.producers.SerializedSingleTransactionRecord;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.hiero.base.crypto.DigestType;

/**
 * This is a prototype for a RecordFileWriter for a cleaned up version of V6 format, going to true protobuf and
 * no SelfSerializable formatting.
 * <p>
 * It is only here so that the APIs further up can be designed in a way that is compatible with the new format.
 */
public final class BlockRecordFormatV7 implements BlockRecordFormat {
    /** The version of this format */
    public static final int VERSION_7 = 7;

    public static final BlockRecordFormat INSTANCE = new BlockRecordFormatV7();

    /**
     * True once the first item has been created
     * TODO remove this, find another way. Because this is not a singleton format it can not have state, so
     *      file writer or StreamFileProducer will need to maintain state.
     */
    private boolean hasFirstItemBeenSerializedYet = false;

    private BlockRecordFormatV7() {
        // Prohibit instantiation
    }

    /** {@inheritDoc} */
    @Override
    public SerializedSingleTransactionRecord serialize(
            @NonNull final SingleTransactionRecord singleTransactionRecord,
            final long blockNumber,
            @NonNull final SemanticVersion hapiVersion) {
        try {
            // first serialize sidecar items to protobuf
            final List<Bytes> sideCarItemsBytes = singleTransactionRecord.transactionSidecarRecords().stream()
                    .map(TransactionSidecarRecord.PROTOBUF::toBytes)
                    .toList();
            // now hash sidecar items
            Bytes sidecarHash = null;
            if (!sideCarItemsBytes.isEmpty()) {
                final MessageDigest sidecarMessageDigest =
                        MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
                for (Bytes sideCarItem : sideCarItemsBytes) {
                    sideCarItem.writeTo(sidecarMessageDigest);
                }
                sidecarHash = Bytes.wrap(sidecarMessageDigest.digest());
            }
            // now create a RecordStreamItemV7
            RecordStreamItemV7 recordStreamItemV7 = hasFirstItemBeenSerializedYet
                    ? new RecordStreamItemV7(
                            singleTransactionRecord.transaction(),
                            singleTransactionRecord.transactionRecord(),
                            sidecarHash,
                            null,
                            0,
                            0)
                    : new RecordStreamItemV7(
                            singleTransactionRecord.transaction(),
                            singleTransactionRecord.transactionRecord(),
                            sidecarHash,
                            hapiVersion,
                            blockNumber,
                            VERSION_7);
            // we have created an item so hasFirstItemBeenSerializedYet is now true
            hasFirstItemBeenSerializedYet = true;
            return new SerializedSingleTransactionRecord(
                    null,
                    RecordStreamItemV7.PROTOBUF.toBytes(recordStreamItemV7),
                    sideCarItemsBytes,
                    singleTransactionRecord.transactionSidecarRecords());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Bytes computeNewRunningHash(
            @NonNull final Bytes startRunningHash,
            @NonNull final List<SerializedSingleTransactionRecord> serializedItems) {
        try {
            byte[] previousHash = startRunningHash.toByteArray();
            final MessageDigest messageDigest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
            // Use for-i loop because it is faster, and we know List is a ArrayList
            //noinspection ForLoopReplaceableByForEach
            for (int i = 0; i < serializedItems.size(); i++) {
                final Bytes serializedItem = serializedItems.get(i).protobufSerializedRecordStreamItem();
                // first hash the item
                serializedItem.writeTo(messageDigest);
                final byte[] serializedItemHash = messageDigest.digest();
                // now hash the previous hash and the item hash
                messageDigest.update(previousHash);
                messageDigest.update(serializedItemHash);
                previousHash = messageDigest.digest();
            }
            return Bytes.wrap(previousHash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeHeader(
            @NonNull final WritableStreamingData outputStream,
            @NonNull final SemanticVersion hapiProtoVersion,
            @NonNull final HashObject startObjectRunningHash) {
        try {
            // Write the record file version int first to start of file
            outputStream.writeInt(VERSION_7);
            // [2] - start_object_running_hash
            writeMessage(
                    outputStream,
                    START_OBJECT_RUNNING_HASH,
                    startObjectRunningHash,
                    com.hedera.hapi.streams.HashObject.PROTOBUF);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Write a {@link SerializedSingleTransactionRecord}.
     *
     * @param outputStream the output stream to write to
     * @param item the item to extract/convert
     */
    private void writeRecordStreamItem(
            @NonNull final WritableStreamingData outputStream, @NonNull final SerializedSingleTransactionRecord item) {
        final Bytes itemBytes = item.protobufSerializedRecordStreamItem();
        // [3] - record_stream_items
        // FUTURE can change once https://github.com/hashgraph/pbj/issues/44 is fixed to:
        // ProtoWriterTools.writeTag(outputStream, RECORD_STREAM_ITEMS, ProtoConstants.WIRE_TYPE_DELIMITED);
        outputStream.writeVarInt((RECORD_STREAM_ITEMS.number() << TAG_TYPE_BITS) | WIRE_TYPE_DELIMITED, false);
        outputStream.writeVarInt((int) itemBytes.length(), false);
        outputStream.writeBytes(itemBytes);
    }

    // =================================================================================================================

    /** Temporary fake record stream item format for V7 */
    public record RecordStreamItemV7(
            @Nullable Transaction transaction,
            @Nullable TransactionRecord transactionRecord,
            @Nullable Bytes hashOfSidecarItems,
            @Nullable SemanticVersion hapiVersion,
            long blockNumber,
            int recordFileVersion) {
        /** Protobuf codec for reading and writing in protobuf format */
        public static final Codec<RecordStreamItemV7> PROTOBUF = new RecordStreamItemV7ProtoCodec();
    }

    public static final class RecordStreamItemV7ProtoCodec implements Codec<RecordStreamItemV7> {

        private static final RecordStreamItemV7 DEFAULT_VALUE = new RecordStreamItemV7(null, null, null, null, 0, 0);

        @Override
        public RecordStreamItemV7 getDefaultInstance() {
            return DEFAULT_VALUE;
        }

        @NonNull
        @Override
        public RecordStreamItemV7 parse(
                @NonNull ReadableSequentialData readableSequentialData,
                boolean strictMode,
                boolean parseUnknownFields,
                int maxDepth)
                throws ParseException {
            return new RecordStreamItemV7(null, null, null, null, 0, 0);
        }

        public @NonNull RecordStreamItemV7 parse(
                @NonNull final ReadableSequentialData input, final boolean strictMode, final int maxDepth) {
            return new RecordStreamItemV7(null, null, null, null, 0, 0);
        }

        public void write(@NonNull RecordStreamItemV7 item, @NonNull WritableSequentialData output) {
            // TBD
        }

        public int measure(@NonNull ReadableSequentialData input) {
            return 0;
        }

        public int measureRecord(RecordStreamItemV7 item) {
            return 0;
        }

        public boolean fastEquals(@NonNull RecordStreamItemV7 item, @NonNull ReadableSequentialData input) {
            return false;
        }
    }
}
