// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream.internal;

import static com.swirlds.common.stream.internal.StreamValidationResult.PARSE_STREAM_FILE_FAIL;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.OBJECT_STREAM;
import static org.hiero.consensus.model.utility.CommonUtils.hex;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.stream.LinkedObjectStreamUtilities;
import com.swirlds.common.stream.StreamType;
import com.swirlds.logging.legacy.payload.StreamParseErrorPayload;
import java.io.File;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.util.Iterator;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.SelfSerializable;

/**
 * Utilities methods for validating stream files and stream signature files
 */
public final class LinkedObjectStreamValidateUtils {
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(LinkedObjectStreamValidateUtils.class);

    /** used for hashing */
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    private LinkedObjectStreamValidateUtils() {}

    /**
     * 1. validates if the stream file is valid, i.e., saved endRunningHash matches calculated endRunningHash;
     * 2. if the stream file is valid, then validates if the entireHash saved in the signature file matches the hash
     * calculated from the stream file;
     * 3. if yes, then validates if the signature file contains valid signatures of the entireHash and metaHash
     *
     * @param streamFile
     * 		a stream file
     * @param sigFile
     * 		a stream signature file
     * @param publicKey
     * 		the public key required to validate the signature
     * @param streamType
     * 		type of the stream file
     * @return validation result
     */
    public static StreamValidationResult validateFileAndSignature(
            final File streamFile, final File sigFile, final PublicKey publicKey, final StreamType streamType) {
        StreamValidationResult result;
        try {
            Pair<StreamValidationResult, Hash> objectResult = validateDirOrFile(streamFile, streamType);
            if (objectResult.left() != StreamValidationResult.OK) {
                return objectResult.left();
            }
            Hash entireHash = LinkedObjectStreamUtilities.computeEntireHash(streamFile);
            result = validateSignature(entireHash, sigFile, publicKey, streamType);
        } catch (InvalidStreamFileException ex) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new StreamParseErrorPayload(String.format(
                            "validateFileAndSignature : failed to validate file %s", streamFile.getName())),
                    ex);
            result = PARSE_STREAM_FILE_FAIL;
        } catch (IOException | NoSuchAlgorithmException ex) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new StreamParseErrorPayload(String.format(
                            "validateFileAndSignature : failed to calculate entireHash for %s", streamFile.getName())),
                    ex);
            result = StreamValidationResult.FAIL_TO_CALCULATE_ENTIRE_HASH;
        }

        return result;
    }

    /**
     * validates if the sigFile contains given entireHash, and contains valid signatures of the entireHash and metaHash
     *
     * @param entireHash
     * 		entireHash calculated from a stream file
     * @param sigFile
     * 		a stream signature file
     * @param publicKey
     * 		the public key required to validate the signature
     * @param streamType
     * 		type of the stream file
     * @return validation result
     */
    public static StreamValidationResult validateSignature(
            final Hash entireHash, final File sigFile, final PublicKey publicKey, final StreamType streamType) {
        Pair<Pair<Hash, Signature>, Pair<Hash, Signature>> parsedPairs;
        try {
            parsedPairs = LinkedObjectStreamUtilities.parseSigFile(sigFile, streamType);
        } catch (IllegalArgumentException | IOException | InvalidStreamFileException ex) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new StreamParseErrorPayload(
                            String.format("parseSigFile : fail to read signature from File %s", sigFile.getName())),
                    ex);
            return StreamValidationResult.PARSE_SIG_FILE_FAIL;
        }
        StreamValidationResult result;
        Hash entireHashInSig = parsedPairs.left().left();
        if (!entireHash.equals(entireHashInSig)) {
            result = StreamValidationResult.SIG_HASH_NOT_MATCH_FILE;
        } else {
            Signature entireSignature = parsedPairs.left().right();
            Hash metaHashInSig = parsedPairs.right().left();
            Signature metaSignature = parsedPairs.right().right();

            if (!verifySignature(entireHash, entireSignature, publicKey)) {
                result = StreamValidationResult.INVALID_ENTIRE_SIGNATURE;
            } else if (!verifySignature(metaHashInSig, metaSignature, publicKey)) {
                result = StreamValidationResult.INVALID_META_SIGNATURE;
            } else {
                result = StreamValidationResult.OK;
            }
        }
        return result;
    }

    /**
     * for a single object stream file, validates if it is valid, i.e., saved endRunningHash matches calculated
     * endRunningHash;
     * for a directory of object stream files, validates if all files in it are valid and chained
     *
     * @param objectDirOrFile
     * 		a directory or a single object stream file
     * @param streamType
     * 		type of stream file(s) to be validated
     * @return a pair of StreamValidationResult and endRunningHash
     * @throws InvalidStreamFileException
     * 		when the file doesn't match given streamType
     */
    public static Pair<StreamValidationResult, Hash> validateDirOrFile(
            final File objectDirOrFile, final StreamType streamType) throws InvalidStreamFileException {
        return validateIterator(LinkedObjectStreamUtilities.parseStreamDirOrFile(objectDirOrFile, streamType));
    }

    /**
     * validate a list of stream object files
     *
     * @param fileList
     * 		a list of stream object files
     * @param streamType
     * 		type of stream file(s) to be validated
     * @return a Pair of StreamValidationResult and last RunningHash
     */
    public static Pair<StreamValidationResult, Hash> validateFileList(
            final List<File> fileList, final StreamType streamType) {
        return validateIterator(LinkedObjectStreamUtilities.parseStreamFileList(fileList, streamType));
    }

    /**
     * Calculates a runningHash for given startRunningHash and objects in the iterator
     * Verifies if the endRunningHash in the Iterator matches the calculated RunningHash
     *
     * @param iterator
     * 		an iterator parsed from stream file
     * @param <T>
     * 		extends SelfSerializable
     * @return a pair of validation result and last running hash
     */
    public static <T extends SelfSerializable> Pair<StreamValidationResult, Hash> validateIterator(
            final Iterator<T> iterator) {
        if (iterator == null) {
            return Pair.of(PARSE_STREAM_FILE_FAIL, null);
        }
        if (!iterator.hasNext()) {
            return Pair.of(StreamValidationResult.STREAM_FILE_EMPTY, null);
        }
        T first = iterator.next();
        if (!(first instanceof Hash)) {
            return Pair.of(StreamValidationResult.STREAM_FILE_MISS_START_HASH, null);
        }
        // initialize a Hash with startRunningHash
        Hash runningHash = (Hash) first;

        T selfSerializable = null;
        int objectsCount = 0;
        while (iterator.hasNext()) {
            selfSerializable = iterator.next();
            if (!iterator.hasNext() && selfSerializable instanceof Hash) {
                break;
            }
            objectsCount++;
            Hash objectHash = CRYPTOGRAPHY.digestSync(selfSerializable);
            runningHash = CRYPTOGRAPHY.calcRunningHash(runningHash, objectHash);
            logger.info(
                    OBJECT_STREAM.getMarker(),
                    "validateIterator :: after consuming object {}," + "hash: {}, updated runningHash: {}",
                    selfSerializable,
                    objectHash,
                    runningHash);
        }
        if (objectsCount == 0) {
            return Pair.of(StreamValidationResult.STREAM_FILE_MISS_OBJECTS, null);
        }
        if (!(selfSerializable instanceof Hash)) {
            return Pair.of(StreamValidationResult.STREAM_FILE_MISS_END_HASH, null);
        }
        Hash endHash = (Hash) selfSerializable;
        // check if calculated endRunningHash matches the endHash read from file
        if (!runningHash.equals(endHash)) {
            logger.info(EXCEPTION.getMarker(), "calculated: {}, read: {}", runningHash, endHash);
            return Pair.of(StreamValidationResult.CALCULATED_END_HASH_NOT_MATCH, endHash);
        }
        return Pair.of(StreamValidationResult.OK, endHash);
    }

    // Code duplicated in order to avoid wasting time on the event stream which will be removed soon
    public static boolean verifySignature(final Hash hash, final Signature signature, final PublicKey publicKey) {
        try {
            final java.security.Signature sig = java.security.Signature.getInstance(
                    SignatureType.RSA.signingAlgorithm(), SignatureType.RSA.provider());
            sig.initVerify(publicKey);
            hash.getBytes().updateSignature(sig);
            return signature.getBytes().verifySignature(sig);
        } catch (final NoSuchAlgorithmException
                | NoSuchProviderException
                | InvalidKeyException
                | SignatureException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> "Failed to verify Signature: %s, PublicKey: %s"
                            .formatted(signature, hex(publicKey.getEncoded())),
                    e);
        }
        return false;
    }
}
