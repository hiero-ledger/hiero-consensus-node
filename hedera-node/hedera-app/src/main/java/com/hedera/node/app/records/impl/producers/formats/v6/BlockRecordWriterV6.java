// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats.v6;

import static com.hedera.hapi.streams.schema.RecordStreamFileSchema.BLOCK_NUMBER;
import static com.hedera.hapi.streams.schema.RecordStreamFileSchema.END_OBJECT_RUNNING_HASH;
import static com.hedera.hapi.streams.schema.RecordStreamFileSchema.HAPI_PROTO_VERSION;
import static com.hedera.hapi.streams.schema.RecordStreamFileSchema.RECORD_STREAM_ITEMS;
import static com.hedera.hapi.streams.schema.RecordStreamFileSchema.SIDECARS;
import static com.hedera.hapi.streams.schema.RecordStreamFileSchema.START_OBJECT_RUNNING_HASH;
import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.TAG_TYPE_BITS;
import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.WIRE_TYPE_DELIMITED;
import static com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordFormatV6.VERSION_6;
import static com.hedera.node.app.records.impl.producers.formats.v6.SignatureWriterV6.writeSignatureFile;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeLong;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeMessage;
import static com.swirlds.common.stream.LinkedObjectStreamUtilities.convertInstantToStringWithPadding;
import static com.swirlds.state.lifecycle.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.hapi.streams.SidecarMetadata;
import com.hedera.node.app.records.impl.producers.BlockRecordWriter;
import com.hedera.node.app.records.impl.producers.SerializedSingleTransactionRecord;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.base.crypto.Signer;

/**
 * An incremental file-based {@link BlockRecordWriter} that writes a single {@link RecordStreamItem} at a time. It also
 * writes to sidecars if needed, and when closed, creates and writes a signature file.
 *
 * <p>All methods are expected to be called on a single thread other than those specified.
 */
public final class BlockRecordWriterV6 implements BlockRecordWriter {
    private static final Logger logger = LogManager.getLogger(BlockRecordWriterV6.class);

    /** The file extension for record files as per the v6 specification */
    public static final String RECORD_EXTENSION = "rcd";
    /** The suffix added to RECORD_EXTENSION when they are compressed as per the v6 specification */
    public static final String COMPRESSION_ALGORITHM_EXTENSION = ".gz";

    private enum State {
        UNINITIALIZED,
        OPEN,
        CLOSED
    }

    /** The {@link Signer} used to sign the hashed bytes of the record file to write as the signature file */
    private final Signer signer;
    /** The maximum size of a sidecar file in bytes. */
    private final int maxSideCarSizeInBytes;
    /** Whether to compress the record file and sidecar files. */
    /** The node-specific path to the directory where record files are written */
    private final Path nodeScopedRecordDir;
    /**
     * The node-specific path to the directory where sidecar files are written. Relative to
     * {@link #nodeScopedRecordDir}
     */
    private final Path nodeScopedSidecarDir;
    /**
     * The block number for the file we are writing. Each file corresponds to one, and only one, block. Once it is
     * set in {@link #init(SemanticVersion, HashObject, Instant, long)}, it is never changed.
     */
    private long blockNumber;
    /**
     * The starting running hash before any items in this file. This was the end hash of the previous file. Once it is
     * set in {@link #init(SemanticVersion, HashObject, Instant, long)}, it is never changed.
     */
    private HashObject startObjectRunningHash;
    /**
     * The consensus time of the first <b>user transaction</b> recorded in this block. Once it is set in
     * {@link #init(SemanticVersion, HashObject, Instant, long)}, it is never changed.
     */
    private Instant startConsensusTime;
    /**
     * The version of the HAPI protobuf schema used to record transactions. This never changes throughout the execution
     * of the program, but is set in {@link #init(SemanticVersion, HashObject, Instant, long)}. It could be moved to
     * be set in the constructor, which would make a little more sense.
     */
    private SemanticVersion hapiProtoVersion;
    /**
     * The list of all {@link SidecarMetadata}s for this block. These are included in the footer of the record file.
     */
    private List<SidecarMetadata> sidecarMetadata;
    /**
     * The current {@link SidecarWriterV6} for writing sidecar file data. This is null if no sidecar records have been
     * handled, such that we only create a sidecar file if we have at least one sidecar record to write. These files
     * are configured with a maximum length. If needed, an existing file is closed and a new one created. The metadata
     * for these are stored in {@link #sidecarMetadata} so that they can be included in the footer of the record file.
     */
    private SidecarWriterV6 sidecarFileWriter;
    /** The path to the record file we are writing */
    private Path recordFilePath;
    /** The file output stream we are writing to, which writes to {@link #recordFilePath} */
    private OutputStream fileOutputStream;
    /** The gzip output stream we are writing to, wraps {@link #fileOutputStream} */
    private GZIPOutputStream gzipOutputStream = null;
    /** HashingOutputStream for hashing the file contents, wraps {@link #gzipOutputStream} or {@link #fileOutputStream} */
    private HashingOutputStream hashingOutputStream;
    /** The buffered output stream we are writing to, wraps {@link #hashingOutputStream} */
    private BufferedOutputStream bufferedOutputStream;
    /** WritableStreamingData we are writing to, wraps {@link #bufferedOutputStream} */
    private WritableStreamingData outputStream;
    /** The state of this writer */
    private State state;

    /**
     * Creates a new incremental record file writer on a new file.
     *
     * @param config The configuration to be used for writing this block. Since this cannot change in the middle of
     *               writing a file, we just need the config, not a config provider.
     * @param nodeInfo The node info for the node writing this file. This is used to get the node-specific directory
     *                 where the file will be written.
     * @param signer The signer to use to sign the file bytes to produce the signature file
     * @param fileSystem The file system to use to write the file
     */
    public BlockRecordWriterV6(
            @NonNull final BlockRecordStreamConfig config,
            @NonNull final NodeInfo nodeInfo,
            @NonNull final Signer signer,
            @NonNull final FileSystem fileSystem) {

        if (config.recordFileVersion() != 6) {
            logger.fatal(
                    "Bad configuration: BlockRecordWriterV6 used with record file version {}",
                    config.recordFileVersion());
            throw new IllegalArgumentException("Configuration record file version is not 6!");
        }

        if (config.signatureFileVersion() != 6) {
            logger.fatal(
                    "Bad configuration: BlockRecordWriterV6 used with signature file version {}",
                    config.recordFileVersion());
            throw new IllegalArgumentException("Configuration signature file version is not 6!");
        }

        this.state = State.UNINITIALIZED;
        this.signer = requireNonNull(signer);
        this.maxSideCarSizeInBytes = config.sidecarMaxSizeMb() * 1024 * 1024;

        // Compute directories for record and sidecar files
        final Path recordDir = fileSystem.getPath(config.logDir());
        nodeScopedRecordDir = recordDir.resolve("record" + asAccountString(nodeInfo.accountId()));
        nodeScopedSidecarDir = nodeScopedRecordDir.resolve(config.sidecarDir());

        // Create parent directories if needed for the record file itself
        try {
            Files.createDirectories(nodeScopedRecordDir);
        } catch (final IOException e) {
            logger.fatal("Could not create record directory {}", nodeScopedRecordDir, e);
            throw new UncheckedIOException(e);
        }
    }

    // =================================================================================================================
    // Implementation of methods in BlockRecordWriter

    /** {@inheritDoc} */
    @Override
    public void init(
            @NonNull final SemanticVersion hapiProtoVersion,
            @NonNull final HashObject startRunningHash,
            @NonNull Instant startConsensusTime,
            long blockNumber) {

        if (state != State.UNINITIALIZED) {
            throw new IllegalStateException("Cannot initialize a BlockRecordWriterV6 twice");
        }

        this.startObjectRunningHash = requireNonNull(startRunningHash);
        this.startConsensusTime = requireNonNull(startConsensusTime);
        this.hapiProtoVersion = requireNonNull(hapiProtoVersion);
        this.blockNumber = blockNumber;
        if (blockNumber < 0) {
            throw new IllegalArgumentException("Block number must be non-negative");
        }

        // Create the chain of streams. In general, closing the "outermost" stream should close all the others, the
        // same as flushing should. However, the HashingOutputStream does not behave correctly in this case, so we
        // need to close all these streams individually, which means we have to maintain references to them.
        this.recordFilePath = getRecordFilePath(startConsensusTime);
        try {
            fileOutputStream = Files.newOutputStream(recordFilePath);
            gzipOutputStream = new GZIPOutputStream(fileOutputStream);
            hashingOutputStream = new HashingOutputStream(createWholeFileMessageDigest(), gzipOutputStream);
            bufferedOutputStream = new BufferedOutputStream(hashingOutputStream);
            outputStream = new WritableStreamingData(bufferedOutputStream);

            // Write the header
            writeHeader(hapiProtoVersion);

            state = State.OPEN;
        } catch (final IOException e) {
            logger.warn("Error initializing record file {}", recordFilePath, e);
            throw new UncheckedIOException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("java:S125")
    public void writeItem(@NonNull final SerializedSingleTransactionRecord rec) {
        if (state != State.OPEN) {
            throw new IllegalStateException("Cannot write to a BlockRecordWriterV6 that is not open");
        }

        final var itemBytes = rec.protobufSerializedRecordStreamItem();
        // [3] - record_stream_items
        // FUTURE can change once https://github.com/hashgraph/pbj/issues/44 is fixed to:
        // ProtoWriterTools.writeTag(outputStream, RECORD_STREAM_ITEMS, ProtoConstants.WIRE_TYPE_DELIMITED);
        outputStream.writeVarInt((RECORD_STREAM_ITEMS.number() << TAG_TYPE_BITS) | WIRE_TYPE_DELIMITED, false);
        outputStream.writeVarInt((int) itemBytes.length(), false);
        outputStream.writeBytes(itemBytes);
        handleSidecarItems(rec);
    }

    /** {@inheritDoc} */
    @Override
    public void close(@NonNull final HashObject endRunningHash) {
        if (state != State.OPEN) {
            throw new IllegalStateException("Cannot close a BlockRecordWriterV6 that is not open");
        }

        try {
            // There are a lot of flushes and closes here, but unfortunately it is not guaranteed that a OutputStream
            // will propagate though a chain of streams. So we have to flush and close each one individually.
            bufferedOutputStream.flush();
            if (gzipOutputStream != null) gzipOutputStream.flush();
            fileOutputStream.flush();

            closeSidecarFileWriter();
            writeFooter(endRunningHash);

            outputStream.close();
            bufferedOutputStream.close();
            if (gzipOutputStream != null) gzipOutputStream.close();
            fileOutputStream.close();

            // write signature file, this tells the uploader that this record file set is complete
            writeSignatureFile(
                    recordFilePath,
                    Bytes.wrap(hashingOutputStream.getDigest()),
                    signer,
                    true,
                    6,
                    hapiProtoVersion,
                    blockNumber,
                    startObjectRunningHash.hash(),
                    endRunningHash.hash());

            this.state = State.CLOSED;
        } catch (final IOException e) {
            logger.warn("Error closing record file {}", recordFilePath, e);
            throw new UncheckedIOException(e);
        }
    }

    // =================================================================================================================
    // Private implementation methods

    private void writeHeader(@NonNull final SemanticVersion hapiProtoVersion) throws UncheckedIOException {
        try {
            // Write the record file version int first to start of file
            outputStream.writeInt(VERSION_6);
            // [1] - hapi_proto_version
            writeMessage(outputStream, HAPI_PROTO_VERSION, hapiProtoVersion, SemanticVersion.PROTOBUF);
            // [2] - start_object_running_hash
            writeMessage(outputStream, START_OBJECT_RUNNING_HASH, startObjectRunningHash, HashObject.PROTOBUF);
        } catch (final IOException e) {
            logger.warn("Error writing header to record file {}", recordFilePath, e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Write the footer to the file
     *
     * @param endRunningHash the ending running hash after the last record stream item
     */
    private void writeFooter(@NonNull final HashObject endRunningHash) throws UncheckedIOException {
        try {
            // [4] - end_object_running_hash
            writeMessage(outputStream, END_OBJECT_RUNNING_HASH, endRunningHash, HashObject.PROTOBUF);
            // [5] - block_number
            writeLong(outputStream, BLOCK_NUMBER, blockNumber);
            // [6] - sidecars
            ProtoWriterTools.writeMessageList(
                    outputStream,
                    SIDECARS,
                    sidecarMetadata == null ? Collections.emptyList() : sidecarMetadata,
                    SidecarMetadata.PROTOBUF);
        } catch (IOException e) {
            logger.warn("Error writing footer to record file {}", recordFilePath, e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     *
     * @param rec Current item to write to the sidecar(s)
     */
    private void handleSidecarItems(@NonNull final SerializedSingleTransactionRecord rec) {
        try {
            final var sideCarItemsBytesList = rec.sideCarItemsBytes();
            final int numOfSidecarItems = sideCarItemsBytesList.size();
            for (int i = 0; i < numOfSidecarItems; i++) {
                // get the sidecar record
                final var sidecarRecordBytes = sideCarItemsBytesList.get(i);
                // get the serialized bytes
                final var sideCarItem = rec.sideCarItems().get(i);
                // get the kind of the sidecar record
                final var kind = sideCarItem.sidecarRecords().kind();
                // check if we have a latestSidecarWriter, if not create one
                if (sidecarFileWriter == null) sidecarFileWriter = createSidecarFileWriter(1);
                // try writing to the sidecar file
                final boolean wasWritten = sidecarFileWriter.writeTransactionSidecarRecord(kind, sidecarRecordBytes);
                // if it was not written then the file is full, so create a new one and write to it
                if (!wasWritten) {
                    // close existing sidecar file writer
                    closeSidecarFileWriter();

                    // create a new writer
                    sidecarFileWriter = createSidecarFileWriter(sidecarFileWriter.id() + 1);

                    // now write item to the new writer
                    if (!sidecarFileWriter.writeTransactionSidecarRecord(kind, sidecarRecordBytes)) {
                        // If it failed to write a second time, then the sidecar file is too small to hold this item.
                        // However, since sidecars are not mandatory, we can just log a warning and move on.
                        logger.warn(
                                "Sidecar file is too large and cannot be written. Sidecar size: {} bytes",
                                sidecarRecordBytes.length());
                    }
                }
            }
        } catch (final IOException e) {
            // NOTE: Writing sidecar files really is best-effort, if it doesn't happen, we're OK with just logging the
            // warning and moving on.
            logger.warn("Error writing sidecar file", e);
        }
    }

    @NonNull
    private SidecarWriterV6 createSidecarFileWriter(final int id) throws IOException {
        return new SidecarWriterV6(getSidecarFilePath(id), maxSideCarSizeInBytes, id);
    }

    private void closeSidecarFileWriter() {
        try {
            if (sidecarFileWriter != null) {
                // close existing sidecar file writer
                sidecarFileWriter.close();
                // get the sidecar hash
                final Bytes sidecarHash = sidecarFileWriter.fileHash();
                // create and add sidecar metadata to the record file
                if (sidecarMetadata == null) sidecarMetadata = new ArrayList<>();
                sidecarMetadata.add(new SidecarMetadata(
                        new HashObject(HashAlgorithm.SHA_384, (int) sidecarHash.length(), sidecarHash),
                        sidecarFileWriter.id(),
                        sidecarFileWriter.types()));
                // Add a marker file for the sidecar when it is closed
                writeSidecarMarkerFile();
            }
        } catch (final IOException e) {
            // NOTE: Writing sidecar files really is best-effort, if it doesn't happen, we're OK with just logging the
            // warning and moving on.
            logger.warn("Error closing sidecar file", e);
        }
    }

    /**
     * Write a marker file for the sidecar file.
     * This is used to indicate that the sidecar file has been closed and is ready to be uploaded.
     * The marker file is named the same as the sidecar file, but with a .mf extension.
     * The contents of the marker file are the hash of the sidecar file.
     *
     * @throws IOException if there was a problem writing the marker file
     */
    private void writeSidecarMarkerFile() throws IOException {
        final Path sidecarPath = getSidecarFilePath(sidecarFileWriter.id());
        final Path markerPath =
                sidecarPath.resolveSibling(sidecarPath.getFileName().toString().replace(".rcd.gz", ".mf"));
        if (Files.exists(markerPath)) {
            logger.debug("Side‑car marker already exists: {}", markerPath);
        } else {
            Files.createFile(markerPath);
        }
    }

    /**
     * Get the record file path for a record file with the given consensus time
     *
     * @param consensusTime  a consensus timestamp of the first object to be written in the file
     * @return Path to a record file for that consensus time
     */
    @NonNull
    private Path getRecordFilePath(final Instant consensusTime) {
        return nodeScopedRecordDir.resolve(convertInstantToStringWithPadding(consensusTime) + "." + RECORD_EXTENSION
                + COMPRESSION_ALGORITHM_EXTENSION);
    }

    /**
     * Get full sidecar file path from given Instant object
     *
     * @param sidecarId                                           the sidecar id of this sidecar file
     * @return the new sidecar file path
     */
    @NonNull
    private Path getSidecarFilePath(final int sidecarId) {
        return nodeScopedSidecarDir.resolve(convertInstantToStringWithPadding(startConsensusTime)
                + "_"
                + String.format("%02d", sidecarId)
                + "."
                + RECORD_EXTENSION
                + COMPRESSION_ALGORITHM_EXTENSION);
    }

    /**
     * Create the digest for hashing the file contents. The record file V6 format requires the hash of ALL the
     * bytes of the file. These bytes are then used to sign the file and submit as a signature file. This is
     * distinct from the running hash which is the hash of the bytes of the record item.
     *
     * @return a new message digest
     * @throws RuntimeException if the digest algorithm is not found
     */
    @NonNull
    private MessageDigest createWholeFileMessageDigest() {
        try {
            return MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
        } catch (NoSuchAlgorithmException e) {
            // If we are unable to create the message digest, then we have a serious problem. This should never be
            // possible because the node should never have been able to start up in this case. But should it ever
            // happen, it would mean that the node was unable to create a new record file writer, and it needs to stop
            // processing new transactions.
            logger.fatal("Unable to create message digest", e);
            throw new RuntimeException(e);
        }
    }
}
