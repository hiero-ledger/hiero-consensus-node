// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.hapi.streams.schema.SidecarFileSchema.SIDECAR_RECORDS;
import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.TAG_TYPE_BITS;
import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.WIRE_TYPE_DELIMITED;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.hapi.streams.SidecarFile;
import com.hedera.hapi.streams.SidecarMetadata;
import com.hedera.hapi.streams.SidecarType;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.HashingOutputStream;

/**
 * Utilities that must match v6 sidecar writer semantics.
 */
public final class WrappedRecordSidecarUtils {
    private WrappedRecordSidecarUtils() {}

    public record SidecarBundles(List<SidecarFile> sidecarFiles, List<SidecarMetadata> sidecarMetadata) {}

    public static SidecarBundles buildSidecarBundles(
            @NonNull final List<TransactionSidecarRecord> records, final int maxSideCarSizeInBytes) {
        requireNonNull(records);
        final List<SidecarFile> sidecarFiles = new ArrayList<>();
        final List<SidecarMetadata> sidecarMetadata = new ArrayList<>();

        int id = 1;
        int bytesWritten = 0;
        final List<TransactionSidecarRecord> currentFileRecords = new ArrayList<>();
        EnumSet<SidecarType> currentTypes = EnumSet.noneOf(SidecarType.class);

        for (final var record : records) {
            final var recordBytes = TransactionSidecarRecord.PROTOBUF.toBytes(record);
            final int recordLen = (int) recordBytes.length();
            // SidecarWriterV6 counts only the record bytes length (not tag/len overhead) for rollover decisions
            if (!currentFileRecords.isEmpty() && (bytesWritten + recordLen) > maxSideCarSizeInBytes) {
                emitSidecarBundle(sidecarFiles, sidecarMetadata, id, currentFileRecords, currentTypes);
                // reset
                id++;
                bytesWritten = 0;
                currentFileRecords.clear();
                currentTypes = EnumSet.noneOf(SidecarType.class);
            }

            currentFileRecords.add(record);
            bytesWritten += recordLen;
            // Mirror SidecarWriterV6 type tracking
            switch (record.sidecarRecords().kind()) {
                case ACTIONS -> currentTypes.add(SidecarType.CONTRACT_ACTION);
                case BYTECODE -> currentTypes.add(SidecarType.CONTRACT_BYTECODE);
                case STATE_CHANGES -> currentTypes.add(SidecarType.CONTRACT_STATE_CHANGE);
                case UNSET -> {
                    // no-op
                }
            }
        }

        if (!currentFileRecords.isEmpty()) {
            emitSidecarBundle(sidecarFiles, sidecarMetadata, id, currentFileRecords, currentTypes);
        }

        return new SidecarBundles(sidecarFiles, sidecarMetadata);
    }

    private static void emitSidecarBundle(
            final List<SidecarFile> sidecarFiles,
            final List<SidecarMetadata> sidecarMetadata,
            final int id,
            final List<TransactionSidecarRecord> currentFileRecords,
            final Set<SidecarType> currentTypes) {
        final var file = new SidecarFile(new ArrayList<>(currentFileRecords));
        final Bytes fileHash = sidecarFileHashAsWrittenByV6(currentFileRecords);
        sidecarFiles.add(file);
        sidecarMetadata.add(new SidecarMetadata(
                new HashObject(HashAlgorithm.SHA_384, (int) fileHash.length(), fileHash),
                id,
                List.copyOf(currentTypes)));
    }

    /**
     * Computes the sidecar file hash in the same way {@code SidecarWriterV6} does:
     * hash the protobuf-framed {@link TransactionSidecarRecord} bytes that are written into the gzip stream.
     */
    static Bytes sidecarFileHashAsWrittenByV6(@NonNull final List<TransactionSidecarRecord> records) {
        requireNonNull(records);
        try {
            final var digest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
            final var gzip = new GZIPOutputStream(OutputStream.nullOutputStream());
            final var hashingOutputStream = new HashingOutputStream(digest, gzip);
            final var outputStream = new WritableStreamingData(new BufferedOutputStream(hashingOutputStream));
            for (final var record : records) {
                final var recordBytes = TransactionSidecarRecord.PROTOBUF.toBytes(record);
                outputStream.writeVarInt((SIDECAR_RECORDS.number() << TAG_TYPE_BITS) | WIRE_TYPE_DELIMITED, false);
                outputStream.writeVarInt((int) recordBytes.length(), false);
                outputStream.writeBytes(recordBytes);
            }
            outputStream.close();
            gzip.close();
            return Bytes.wrap(hashingOutputStream.getDigest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to create SHA-384 message digest", e);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to compute sidecar file hash", e);
        }
    }
}
