// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream.internal;

import static com.swirlds.common.crypto.SignatureType.RSA;
import static com.swirlds.common.stream.LinkedObjectStreamUtilities.generateSigFilePath;
import static com.swirlds.common.stream.LinkedObjectStreamUtilities.generateStreamFileNameFromInstant;
import static com.swirlds.common.stream.LinkedObjectStreamUtilities.getPeriod;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.FREEZE;
import static com.swirlds.logging.legacy.LogMarker.OBJECT_STREAM;
import static com.swirlds.logging.legacy.LogMarker.OBJECT_STREAM_FILE;
import static org.hiero.consensus.model.crypto.DigestType.SHA_384;
import static org.hiero.consensus.model.stream.StreamAligned.NO_ALIGNMENT;

import com.swirlds.common.crypto.HashingOutputStream;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.common.stream.Signer;
import com.swirlds.common.stream.StreamType;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.RunningHash;
import org.hiero.consensus.model.crypto.RunningHashable;
import org.hiero.consensus.model.crypto.SerializableHashable;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.stream.StreamAligned;
import org.hiero.consensus.model.stream.Timestamped;

/**
 * <p>
 * Objects that pass through this stream are written to a file. Depending on the object's timestamp,
 * it may be written to an existing file, or a new file may be created to hold the object. This object
 * also generates signature files.
 * </p>
 *
 * <p>
 * An important property of this stream is determinism. If two similarly configured instances of this stream are
 * fed the same sequence of objects, then both instances will produce the exact same files.
 * </p>
 *
 * @param <T>
 * 		the type of the object being streamed
 */
public class TimestampStreamFileWriter<T extends StreamAligned & RunningHashable & SerializableHashable & Timestamped>
        implements LinkedObjectStream<T> {

    /** a unique class type identifier */
    private static final long SIGNATURE_CLASS_ID = 0x13dc4b399b245c69L;

    /** the current serialization version */
    private static final int CLASS_VERSION = 1;

    /**
     * The serialization format of the stream files.
     */
    public static final int OBJECT_STREAM_VERSION = 1;
    /**
     * The serialization format of the signature files.
     */
    public static final int OBJECT_STREAM_SIG_VERSION = 1;

    private static final Logger logger = LogManager.getLogger(TimestampStreamFileWriter.class);
    private static final SignatureType SIGNATURE_TYPE = RSA;
    /**
     * Describes the type of object being streamed. (e.g. record stream / event stream).
     */
    private final StreamType streamType;
    /**
     * The location where files are written.
     */
    private final String directory;
    /**
     * An object capable of signing things.
     */
    private final Signer signer;
    /**
     * The desired amount of time that data should be written into a file before starting a new file.
     */
    private final long windowSizeMs;
    /**
     * A message digest used to hash entire stream files.
     */
    private final MessageDigest streamDigest;
    /**
     * A message digest used to hash metadata in the stream file and generating the metadata hash.
     * Metadata contains bytes before startRunningHash + startRunningHash + endRunningHash (+ denotes concatenation).
     */
    private final MessageDigest metadataStreamDigest;
    /**
     * The file stream for the current file.
     */
    private FileOutputStream fileStream = null;
    /**
     * Data destined for the output file should be written to this stream.
     */
    private SerializableDataOutputStream out = null;
    /**
     * Metadata should be written to this stream. Any data written to this stream is used to generate a running
     * metadata hash.
     */
    private SerializableDataOutputStream metadataOut = null;
    /**
     * The current file being written.
     */
    private File currentFile;
    /**
     * The current running hash of objects added to the stream.
     */
    private RunningHash runningHash;
    /**
     * The previous object that was passed to the stream.
     */
    private T previousObject;
    /**
     * Tracks if the previous object was held back in the previous file due to its alignment.
     */
    private boolean previousHeldBackByAlignment;
    /**
     * If true, then only start writing when it can be guaranteed only complete files are written. (This may cause
     * several of the first objects that pass through the stream to be ignored.) If false then immediately start writing
     * when the first object is received.
     */
    private boolean startWriteAtCompleteWindow;

    /**
     * Create a new stream.
     *
     * @param directory
     * 		the directory where files will be written
     * @param windowSizeMs
     * 		the desired time window for a single file
     * @param signer
     * 		an object that can sign things
     * @param startWriteAtCompleteWindow
     * 		if true, then only start writing files when it can be guaranteed
     * 		that partial files will not be written
     * @param streamType
     * 		describes the type of object being passed through this stream
     */
    public TimestampStreamFileWriter(
            final String directory,
            final long windowSizeMs,
            final Signer signer,
            final boolean startWriteAtCompleteWindow,
            final StreamType streamType) {

        this.directory = directory;
        this.windowSizeMs = windowSizeMs;
        this.signer = signer;
        this.startWriteAtCompleteWindow = startWriteAtCompleteWindow;
        this.streamType = streamType;

        try {
            streamDigest = MessageDigest.getInstance(SHA_384.algorithmName());
            metadataStreamDigest = MessageDigest.getInstance(SHA_384.algorithmName());
        } catch (NoSuchAlgorithmException e) {
            // This is unrecoverable. No need to force the caller to catch this exception.
            throw new RuntimeException(e);
        }
    }

    /**
     * Generate a signature file for the current object stream file.
     * The signature bytes are generated by signing the endRunningHash.
     *
     * @param entireHash
     * 		a Hash calculated with all bytes in the entire stream file
     * @param entireSignature
     * 		a Signature which is generated by signing the value of entireHash
     * @param metaHash
     * 		a Hash calculated with metadata bytes in the stream file
     * @param metaSignature
     * 		a Signature which is generated by signing the value of metaHash
     * @param sigFilePath
     * 		path of the signature file to be written
     * @param streamType
     * 		type of this stream file
     * @throws IOException
     * 		thrown if any I/O related errors occur
     */
    public static void writeSignatureFile(
            final Hash entireHash,
            final Signature entireSignature,
            final Hash metaHash,
            final Signature metaSignature,
            final String sigFilePath,
            final StreamType streamType)
            throws IOException {

        try (final SerializableDataOutputStream output =
                new SerializableDataOutputStreamImpl(new BufferedOutputStream(new FileOutputStream(sigFilePath)))) {

            // write signature file header
            for (final byte num : streamType.getSigFileHeader()) {
                output.writeByte(num);
            }

            output.writeInt(OBJECT_STREAM_SIG_VERSION);
            output.writeSerializable(entireHash, true);
            entireSignature.serialize(output, true);
            output.writeSerializable(metaHash, true);
            metaSignature.serialize(output, true);

            logger.info(OBJECT_STREAM_FILE.getMarker(), "signature file saved: {}", sigFilePath);
        }
    }

    /**
     * Serialize an object.
     */
    private void serialize(final T object) {
        try {
            out.writeSerializable(object, true);
            out.flush();
        } catch (IOException e) {
            logger.warn(EXCEPTION.getMarker(), "IOException when serializing {}", object, e);
        }
    }

    /**
     * Create a new file which will eventually contain the provided object.
     *
     * @param object
     * 		the first object that will eventually be written to the new file
     */
    private void startNewFile(final T object) {
        currentFile = new File(generateStreamFilePath(object));
        try {
            if (currentFile.exists() && !currentFile.isDirectory()) {
                logger.info(OBJECT_STREAM.getMarker(), "Stream file already exists {}", currentFile::getName);
            } else {
                fileStream = new FileOutputStream(currentFile, false);
                out = new SerializableDataOutputStreamImpl(
                        new BufferedOutputStream(new HashingOutputStream(streamDigest, fileStream)));
                metadataOut = new SerializableDataOutputStreamImpl(new HashingOutputStream(metadataStreamDigest));
                logger.info(OBJECT_STREAM_FILE.getMarker(), "Stream file created {}", currentFile::getName);
            }
        } catch (final FileNotFoundException e) {
            logger.error(EXCEPTION.getMarker(), "startNewFile :: FileNotFound: ", e);
        }
    }

    /**
     * write the beginning part of the file:
     * File Version ID, and initial runningHash
     */
    private void begin() {
        try {
            // write file header
            for (int num : streamType.getFileHeader()) {
                out.writeInt(num);
                metadataOut.writeInt(num);
            }
            // write file version
            out.writeInt(OBJECT_STREAM_VERSION);
            metadataOut.writeInt(OBJECT_STREAM_VERSION);

            logger.info(
                    OBJECT_STREAM_FILE.getMarker(), "begin :: write OBJECT_STREAM_VERSION {}", OBJECT_STREAM_VERSION);
            // write startRunningHash
            Hash startRunningHash = runningHash.getFutureHash().getAndRethrow();
            out.writeSerializable(startRunningHash, true);
            metadataOut.writeSerializable(startRunningHash, true);
            logger.info(OBJECT_STREAM_FILE.getMarker(), "begin :: write startRunningHash {}", startRunningHash);
        } catch (final IOException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "begin :: Got IOException when writing startRunningHash to {}",
                    currentFile.getName(),
                    e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error(
                    EXCEPTION.getMarker(),
                    "begin :: Got interrupted when getting startRunningHash for writing {}",
                    currentFile.getName(),
                    e);
        }
    }

    /**
     * Write the running hash to the current file and close it, and generate a signature file.
     * Does nothing if there is no file currently open.
     */
    public void closeCurrentAndSign() {
        if (fileStream != null) {
            try {
                final Hash finalRunningHash = runningHash.getFutureHash().getAndRethrow();
                out.writeSerializable(finalRunningHash, true);
                metadataOut.writeSerializable(finalRunningHash, true);
                logger.info(
                        OBJECT_STREAM_FILE.getMarker(),
                        "closeCurrentAndSign {} :: write endRunningHash {}",
                        currentFile,
                        finalRunningHash);
            } catch (final IOException e) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "closeCurrentAndSign :: Got Exception when writing endRunningHash to {}",
                        currentFile.getName(),
                        e);
                return;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error(
                        EXCEPTION.getMarker(),
                        "closeCurrentAndSign :: Got interrupted when getting endRunningHash for writing {}",
                        currentFile.getName(),
                        e);
                return;
            }
            final File closedFile = currentFile;
            // close current file
            closeFile();

            // get entire Hash for this stream file
            final Hash entireHash = new Hash(streamDigest.digest(), SHA_384);
            // get metaData Hash for this stream file
            final Hash metaHash = new Hash(metadataStreamDigest.digest(), SHA_384);

            // generate signature for entire Hash
            final Signature entireSignature = new Signature(
                    SIGNATURE_TYPE, signer.sign(entireHash.copyToByteArray()).getBytes());
            // generate signature for metaData Hash
            final Signature metaSignature = new Signature(
                    SIGNATURE_TYPE, signer.sign(metaHash.copyToByteArray()).getBytes());
            try {
                writeSignatureFile(
                        entireHash,
                        entireSignature,
                        metaHash,
                        metaSignature,
                        generateSigFilePath(closedFile),
                        streamType);
            } catch (final IOException e) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "closeCurrentAndSign ::  :: Fail to generate signature file for {}",
                        closedFile.getName(),
                        e);
            }
        }
    }

    /**
     * If stream is not null, close current file and save it to disk.
     */
    private void closeFile() {
        final String fileName = currentFile == null ? "null" : currentFile.getName();
        if (fileStream != null) {
            try {
                out.flush();
                fileStream.flush();

                fileStream.getChannel().force(true);
                fileStream.getFD().sync();

                out.close();
                fileStream.close();
                metadataOut.close();

                currentFile = null;
                fileStream = null;
                out = null;
                metadataOut = null;
            } catch (final IOException e) {
                logger.warn(EXCEPTION.getMarker(), "Exception in close file", e);
            }
            logger.info(OBJECT_STREAM_FILE.getMarker(), "File {} is closed at {}", () -> fileName, Instant::now);
        }
    }

    /**
     * <p>
     * Check whether the provided object needs to be written into a new file, or if it should be written
     * into the current file.
     * </p>
     *
     * <p>
     * Time is divided into windows determined by provided configuration. An object is chosen to start a new file
     * if it is the first encountered object with a timestamp in the next window -- as long as the object has
     * a different stream alignment than the previous object. If an object has a matching stream alignment as the
     * previous object then it is always placed in the same file as the previous object. Alignment is ignored for
     * objects with {@link StreamAligned#NO_ALIGNMENT NO_ALIGNMENT}.
     * </p>
     *
     * <p>
     * This method is public to allow direct access for unit testing. This method has side effects, and should
     * not be called without an understanding of those side effects.
     * </p>
     *
     * @param nextObject
     * 		the object currently being added to the stream
     * @return whether the object should be written into a new file
     */
    public boolean shouldStartNewFile(final T nextObject) {
        try {

            if (previousObject == null) {
                // This is the first object. It may be the first thing in a file, but it is impossible
                // to make that determination at this point in time.
                return !startWriteAtCompleteWindow;
            } else {
                // Check if this object is in a different period than the previous object.
                final long previousPeriod = getPeriod(previousObject.getTimestamp(), windowSizeMs);
                final long currentPeriod = getPeriod(nextObject.getTimestamp(), windowSizeMs);
                final boolean differentPeriod = previousPeriod != currentPeriod;

                // Check if this object has a different alignment than the previous object. Objects with NO_ALIGNMENT
                // are always considered to be unaligned with any other object.
                final boolean differentAlignment =
                        previousObject.getStreamAlignment() != nextObject.getStreamAlignment()
                                || nextObject.getStreamAlignment() == NO_ALIGNMENT;

                // If this object is in a new period with respect to the current file, and no
                // objects have yet been written to the next file.
                final boolean timestampIsEligibleForNextFile = previousHeldBackByAlignment || differentPeriod;

                if (timestampIsEligibleForNextFile && !differentAlignment) {
                    // This object has the same alignment as the one that came before it, so we must hold it back.
                    previousHeldBackByAlignment = true;
                    return false;
                }

                previousHeldBackByAlignment = false;
                return timestampIsEligibleForNextFile;
            }

        } finally {
            previousObject = nextObject;
        }
    }

    /**
     * Generate the full file path from the object that will be the first thing in the file.
     */
    private String generateStreamFilePath(final T object) {
        return directory + File.separator + generateStreamFileNameFromInstant(object.getTimestamp(), streamType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRunningHash(final Hash hash) {
        runningHash = new RunningHash(hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addObject(final T object) {
        if (shouldStartNewFile(object)) {
            // if we have a current file,
            // should write endRunningHash, close current file, and generate signature file
            closeCurrentAndSign();
            // start new file
            startNewFile(object);

            // if the file already exists, it will not be opened, so we don't write anything to it. begin() would
            // previously throw an NPE before this check was added
            if (fileStream != null) {
                // write the beginning of new file
                begin();
            }
        }

        // if stream is null, it means startWriteAtCompleteWindow is true and we are still in the first
        // incomplete
        // window, so we don't serialize this object;
        // so we only serialize the object when stream is not null
        if (fileStream != null) {
            serialize(object);
        }
        // update runningHash
        runningHash = object.getRunningHash();
    }

    /**
     * This method is called when the node falls behind.
     * This method will delete any partially written files.
     */
    @Override
    public void clear() {
        if (fileStream != null) {
            final File closedFile = currentFile;
            // close current file
            closeFile();
            try {
                // delete this file since it is half written
                Files.delete(closedFile.toPath());
                logger.info(
                        OBJECT_STREAM.getMarker(), "TimestampStreamFileWriter::clear deleted {}", closedFile::getName);
            } catch (final IOException ex) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "TimestampStreamFileWriter::clear got IOException " + "when deleting file {}",
                        closedFile.getName(),
                        ex);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        closeCurrentAndSign();
        logger.info(FREEZE.getMarker(), "TimestampStreamFileWriter finished writing the last object, is stopped");
    }

    /**
     * Get the value of startWriteAtCompleteWindow.
     *
     * @return whether we should write object stream file until the first complete window
     */
    public boolean getStartWriteAtCompleteWindow() {
        return startWriteAtCompleteWindow;
    }

    /**
     * Set if files should only be written in their entirety (as opposed to allowing files to be partially created
     * if the initial objects are not present). Should be set to be true after reconnect, or at state recovering,
     * and should be set to be false at restart
     */
    public void setStartWriteAtCompleteWindow(final boolean startWriteAtCompleteWindow) {
        this.startWriteAtCompleteWindow = startWriteAtCompleteWindow;
        logger.info(
                OBJECT_STREAM.getMarker(),
                "TimestampStreamFileWriter::setStartWriteAtCompleteWindow: {}",
                startWriteAtCompleteWindow);
    }
}
