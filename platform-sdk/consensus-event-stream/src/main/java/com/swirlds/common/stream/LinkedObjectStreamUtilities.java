// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import static com.swirlds.base.units.UnitConstants.MILLISECONDS_TO_NANOSECONDS;
import static com.swirlds.base.units.UnitConstants.SECONDS_TO_NANOSECONDS;
import static com.swirlds.common.stream.internal.TimestampStreamFileWriter.OBJECT_STREAM_VERSION;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.stream.internal.InvalidStreamFileException;
import com.swirlds.common.stream.internal.SingleStreamIterator;
import com.swirlds.common.stream.internal.StreamFilesIterator;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Iterator;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.base.crypto.Signature;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Utilities methods for: parsing stream files and stream signature files; generating fileName from Instant; calculating
 * period; reading start and end runningHash from a stream file; calculating metaHash and entireHash for a stream file;
 */
public final class LinkedObjectStreamUtilities {

    /**
     * when converting an Instant to a String, nano-of-second part always outputs this many digits
     */
    private static final int NANO_DIGITS_COUNT = 9;

    private LinkedObjectStreamUtilities() {}

    /**
     * generate fileName from given Instant
     *
     * @param timestamp  a timestamp of the first object to be written in the file
     * @param streamType type of this stream file
     * @return the name of the file to be written
     */
    public static String generateStreamFileNameFromInstant(final Instant timestamp, final StreamType streamType) {
        return convertInstantToStringWithPadding(timestamp) + "." + streamType.getExtension();
    }

    /**
     * A string representation of the Instant using ISO-8601 representation, with colons converted to underscores for
     * Windows compatibility. The nano-of-second always outputs nine digits with padding when necessary, to ensure same
     * length filenames and proper sorting. examples: input: 2020-10-19T21:35:39Z output:
     * "2020-10-19T21_35_39.000000000Z"
     * <p>
     * input: 2020-10-19T21:35:39.454265Z output: "2020-10-19T21:35:39.454265000Z"
     *
     * @param timestamp an Instant object
     * @return a string representation of the Instant, to be used as stream file name
     */
    public static String convertInstantToStringWithPadding(final Instant timestamp) {
        String string = timestamp.toString().replace(":", "_");
        StringBuilder stringBuilder = new StringBuilder(string);
        int nanoStartIdx = string.indexOf(".");
        int nanoEndIdx = string.indexOf("Z");
        int numOfNanoDigits = nanoStartIdx == -1 ? 0 : (nanoEndIdx - nanoStartIdx - 1);
        int numOfZeroPadding = NANO_DIGITS_COUNT - numOfNanoDigits;
        if (numOfZeroPadding == 0) {
            return string;
        }
        // remove the last 'Z'
        stringBuilder.setLength(stringBuilder.length() - 1);
        // if string doesn't have nano part, append '.'
        if (nanoStartIdx == -1) {
            stringBuilder.append('.');
        }
        // append numOfZeroPadding '0'(s)
        for (int i = 0; i < numOfZeroPadding; i++) {
            stringBuilder.append('0');
        }
        // append 'Z'
        stringBuilder.append('Z');
        return stringBuilder.toString();
    }

    /**
     * generate signature file name for current stream file
     *
     * @param file a stream file
     * @return path of the signature file
     */
    public static String generateSigFilePath(File file) {
        return file.getAbsolutePath() + "_sig";
    }

    /**
     * get period number with given consensusTimestamp and logPeriodMs Object with different period number should not be
     * written in the same stream file
     *
     * @param consensusTimestamp consensusTimestamp of the object
     * @param logPeriodMs        period of generating object stream files in ms
     * @return period number
     */
    public static long getPeriod(final Instant consensusTimestamp, final long logPeriodMs) {
        final long nanos = consensusTimestamp.getEpochSecond() * SECONDS_TO_NANOSECONDS + consensusTimestamp.getNano();
        return nanos / MILLISECONDS_TO_NANOSECONDS / logPeriodMs;
    }

    /**
     * Extracts the timestamp from event file name and converts it to {@code Instant} representation.
     *
     * @param filename filename such as: 2020-09-21T15_16_56.978420Z.evts
     * @return timestamp extracted from this filename
     */
    public static Instant getTimeStampFromFileName(final String filename) {
        final int indexOfZ = filename.indexOf("Z");
        if (indexOfZ != -1) {
            String dateInfo = filename.substring(0, indexOfZ + 1);
            dateInfo = dateInfo.replace("_", ":");
            return Instant.parse(dateInfo);
        }
        return null;
    }

    /**
     * get file extension name
     *
     * @param file a file
     * @return extension name of the given file
     */
    public static String getFileExtension(File file) {
        int lastIndexOf = file.getName().lastIndexOf(".");
        // empty extension
        if (lastIndexOf == -1) {
            return "";
        }
        return file.getName().substring(lastIndexOf + 1);
    }

    /**
     * Parse a single stream file, return an Iterator from which we can get all SelfSerializable objects in the file the
     * first object is startRunningHash; the last object is endRunningHash; the other objects are stream objects;
     *
     * @param file       a .soc stream file
     * @param streamType type of the stream file
     * @param <T>        type of the SelfSerializable objects written in the stream file
     * @return an Iterator from which we can get all SelfSerializable objects in the file
     */
    public static <T extends SelfSerializable> SingleStreamIterator<T> parseStreamFile(
            final File file, final StreamType streamType) {
        // if this file's extension name doesn't match expected
        if (!streamType.isStreamFile(file)) {
            String msg = String.format(
                    "Fail to parse File %s, its extension doesn't match %s", file.getName(), streamType.getExtension());
            throw new IllegalArgumentException(msg);
        }

        return new SingleStreamIterator<>(file, streamType);
    }

    /**
     * if it is a single stream file of the given type, parse this file, and return an Iterator which contains all
     * SelfSerializables contained in the file.
     * <p>
     * if it is a directory, parse stream files of the given type in this directory in increasing order by fileName,
     * also validate if each file's startRunningHash matches its previous file's endRunningHash. return an Iterator
     * which contains the startRunningHash in the first stream file, all stream objects contained in the directory, and
     * the endRunningHash in the last stream file
     *
     * @param objectStreamDirOrFile a single stream file or a directory which contains stream files
     * @param streamType            type of stream files to be parsed
     * @param <T>                   type of the SelfSerializable objects written in the stream file
     * @throws InvalidStreamFileException when the file doesn't match given streamType
     */
    public static <T extends SelfSerializable> Iterator<T> parseStreamDirOrFile(
            final File objectStreamDirOrFile, final StreamType streamType) throws InvalidStreamFileException {
        if (objectStreamDirOrFile.isDirectory()) {
            return new StreamFilesIterator<>(objectStreamDirOrFile.listFiles(streamType::isStreamFile), streamType);
        } else if (!streamType.isStreamFile(objectStreamDirOrFile)) {
            // throw an exception if it is not a stream file of given type
            throw new InvalidStreamFileException(String.format(
                    "the file %s doesn't match streamType %s", objectStreamDirOrFile.getName(), streamType));
        } else {
            return parseStreamFile(objectStreamDirOrFile, streamType);
        }
    }

    /**
     * Parses a collection of single stream files of given type
     *
     * @param files      a collection of stream files to be parsed
     * @param streamType type of stream files to be parsed
     * @param <T>        type of the SelfSerializable objects written in the stream file
     * @return an Iterator which contains the startRunningHash in the first stream file, all stream objects contained in
     * the directory, and the endRunningHash in the last stream file
     */
    public static <T extends SelfSerializable> Iterator<T> parseStreamFileList(
            final Collection<File> files, final StreamType streamType) {
        return new StreamFilesIterator<>(files.toArray(File[]::new), streamType);
    }

    /**
     * Reads startRunningHash from a stream file
     *
     * @param file       a stream file
     * @param streamType streamType of this file
     * @return startRunningHash saved in this stream file
     */
    public static Hash readStartRunningHashFromStreamFile(final File file, final StreamType streamType) {
        SingleStreamIterator<SelfSerializable> singleStreamIterator = parseStreamFile(file, streamType);
        Hash startRunningHash = (Hash) singleStreamIterator.next();
        singleStreamIterator.closeStream();
        return startRunningHash;
    }

    /**
     * Reads the starting and ending running hashes from the given stream file.
     *
     * @param file       a stream file
     * @param streamType type of this stream file
     * @param <T>        type of the SelfSerializable objects written in the stream file
     * @return the starting and ending running hashes
     * @throws InvalidStreamFileException when the stream file is not valid
     */
    public static <T extends SelfSerializable> Pair<Hash, Hash> readHashesFromStreamFile(
            final File file, final StreamType streamType) throws InvalidStreamFileException {
        SingleStreamIterator<T> iterator = parseStreamFile(file, streamType);
        final Hash startRunningHash;
        try {
            startRunningHash = (Hash) iterator.next();
        } catch (ClassCastException e) {
            throw new InvalidStreamFileException(
                    String.format("File %s doesn't contain startRunningHash", file.getName()), e);
        }

        T object = null;
        while (iterator.hasNext()) {
            object = iterator.next();
        }
        final Hash endRunningHash;
        try {
            endRunningHash = (Hash) object;
        } catch (ClassCastException e) {
            throw new InvalidStreamFileException(
                    String.format("File %s doesn't contain endRunningHash", file.getName()), e);
        }
        iterator.closeStream();
        return Pair.of(startRunningHash, endRunningHash);
    }

    /**
     * read signature byte from a stream signature file
     *
     * @param file       a signature file
     * @param streamType type of this stream file
     * @return a pair of two pairs: the first pair contains entireHash and entireSignature; the second pair contains
     * metaHash and metaSignature
     * @throws IOException                thrown if any I/O related errors occur
     * @throws InvalidStreamFileException thrown if the signature file doesn't match the streamType
     */
    public static Pair<Pair<Hash, Signature>, Pair<Hash, Signature>> parseSigFile(
            final File file, final StreamType streamType) throws IOException, InvalidStreamFileException {
        if (!streamType.isStreamSigFile(file.getName())) {
            throw new InvalidStreamFileException(String.format(
                    "parseSigFile : fail to read signature from File %s, its extension doesn't match %s",
                    file.getName(), streamType.getSigExtension()));
        } else {
            try (SerializableDataInputStream inputStream =
                    new SerializableDataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                // read signature file header
                for (int i = 0; i < streamType.getSigFileHeader().length; i++) {
                    inputStream.readByte();
                }
                // read OBJECT_STREAM_SIG_VERSION
                inputStream.readInt();
                // read entireHash
                final Hash entireHash = inputStream.readSerializable();
                // read entireSignature
                final Signature entireSignature = Signature.deserialize(inputStream, true);
                // read metaHash
                final Hash metaHash = inputStream.readSerializable();
                // read metaSignature
                final Signature metaSignature = Signature.deserialize(inputStream, true);

                return Pair.of(Pair.of(entireHash, entireSignature), Pair.of(metaHash, metaSignature));
            }
        }
    }

    /**
     * Computes the SHA384 {@code Hash} representation of the entire file
     *
     * @param file a file to be hashed
     * @return an entireHash which denotes a SHA384 Hash calculated with all bytes in the given file
     * @throws IOException              if fail to read the file
     * @throws NoSuchAlgorithmException if an implementation of the required algorithm cannot be located or loaded
     */
    public static Hash computeEntireHash(final File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
        try (FileInputStream fis = new FileInputStream(file);
                BufferedInputStream bis = new BufferedInputStream(fis);
                DigestInputStream dis = new DigestInputStream(bis, md)) {
            while (dis.read() != -1) {
                // empty on purpose, read all bytes in this file until reach the end
            }
            // completes the hash computation, creates and returns the Hash instance
            return new Hash(md.digest(), DigestType.SHA_384);
        }
    }

    /**
     * Computes the {@code Hash} representation of metadata of the stream file using the file headers, file version,
     * starting running hash, and ending running hash.
     *
     * @param file       a file to be hashed
     * @param streamType type of this stream file
     * @return a {@code Hash} calculated from the file headers, file version, starting running hash, and ending running
     * hash
     * @throws IOException                if fail to read the given file
     * @throws NoSuchAlgorithmException   if an implementation of the required algorithm cannot be located or loaded
     * @throws InvalidStreamFileException if the stream file has invalid format
     */
    public static Hash computeMetaHash(final File file, final StreamType streamType)
            throws IOException, NoSuchAlgorithmException, InvalidStreamFileException {
        MessageDigest md = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
        try (SerializableDataOutputStream outputStream =
                new SerializableDataOutputStream(new HashingOutputStream(md))) {
            // digest file header
            for (int num : streamType.getFileHeader()) {
                outputStream.writeInt(num);
            }
            // digest Object Stream Version
            outputStream.writeInt(OBJECT_STREAM_VERSION);

            // startRunningHash and endRunningHash
            Pair<Hash, Hash> hashPair = readHashesFromStreamFile(file, streamType);
            // digest startRunningHash
            outputStream.writeSerializable(hashPair.left(), true);
            // digest endRunningHash
            outputStream.writeSerializable(hashPair.right(), true);
        }
        return new Hash(md.digest());
    }

    /**
     * read the first int from file content
     *
     * @param file a file to be read
     * @return the first int in the file
     * @throws IOException thrown if any I/O related errors occur
     */
    public static int readFirstIntFromFile(final File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
                SerializableDataInputStream inputStream = new SerializableDataInputStream(fis)) {
            return inputStream.readInt();
        }
    }
}
