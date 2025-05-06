// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats.v6;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.hapi.streams.SignatureFile;
import com.hedera.hapi.streams.SignatureObject;
import com.hedera.hapi.streams.SignatureType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.base.crypto.Signer;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * Simple stateless class with static methods to write signature files. It cleanly separates out the code for generating
 * signature files.
 */
final class SignatureWriterV6 {
    /** Logger to use */
    private static final Logger logger = LogManager.getLogger(SignatureWriterV6.class);
    /** The suffix added to RECORD_EXTENSION for the record signature files */
    private static final String RECORD_SIG_EXTENSION_SUFFIX = "_sig";

    private SignatureWriterV6() {
        // prohibit instantiation
    }

    /**
     * Write a signature file for a record file
     *
     * @param recordFilePath the path to the record file
     * @param recordFileHash the hash of the record file
     * @param signer the signer
     * @param writeMetadataSignature whether to write the metadata signature
     * @param recordFileVersion the record file version
     * @param hapiProtoVersion the hapi proto version
     * @param blockNumber the block number
     * @param startRunningHash the start running hash
     * @param endRunningHash the end running hash
     */
    static void writeSignatureFile(
            @NonNull final Path recordFilePath,
            @NonNull Bytes recordFileHash,
            @NonNull final Signer signer,
            final boolean writeMetadataSignature,
            final int recordFileVersion,
            final SemanticVersion hapiProtoVersion,
            final long blockNumber,
            @NonNull final Bytes startRunningHash,
            @NonNull final Bytes endRunningHash) {
        // write signature file
        final var sigFilePath = getSigFilePath(recordFilePath);
        try (final var fileOut = Files.newOutputStream(sigFilePath, StandardOpenOption.CREATE_NEW)) {
            final var streamingData = new WritableStreamingData(fileOut);
            Bytes metadataHash = null;
            if (writeMetadataSignature) {
                // create metadata hash
                HashingOutputStream hashingOutputStream =
                        new HashingOutputStream(MessageDigest.getInstance(DigestType.SHA_384.algorithmName()));
                SerializableDataOutputStream dataOutputStream = new SerializableDataOutputStream(hashingOutputStream);
                dataOutputStream.writeInt(recordFileVersion);
                dataOutputStream.writeInt(hapiProtoVersion.major());
                dataOutputStream.writeInt(hapiProtoVersion.minor());
                dataOutputStream.writeInt(hapiProtoVersion.patch());
                startRunningHash.writeTo(dataOutputStream);
                endRunningHash.writeTo(dataOutputStream);
                dataOutputStream.writeLong(blockNumber);
                dataOutputStream.close();
                metadataHash = Bytes.wrap(hashingOutputStream.getDigest());
            }
            // create signature file
            final var signatureFile = new SignatureFile(
                    generateSignatureObject(signer, recordFileHash),
                    writeMetadataSignature ? generateSignatureObject(signer, metadataHash) : null);
            // write version in signature file. It is only 1 byte, compared to 4 in record files
            streamingData.writeByte((byte) 6);
            // write protobuf SignatureFile
            SignatureFile.PROTOBUF.write(signatureFile, streamingData);
            logger.debug("signature file saved: {}", sigFilePath);
            // flush
            fileOut.flush();
        } catch (final FileAlreadyExistsException ignore) {
            // This is part of normal operations, as a reconnected node will very commonly
            // re-create an existing record stream file while REPLAYING_EVENTS
            logger.info("Skipping signature file for {} as it already exists", recordFilePath);
        } catch (final IOException e) {
            logger.error("Fail to generate signature file for {}", recordFilePath, e);
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Given a hash produce the HAPI signature object for that hash
     *
     * @param signer The signer to use
     * @param hash The hash to sign
     * @return Signature object
     */
    private static SignatureObject generateSignatureObject(@NonNull final Signer signer, @NonNull final Bytes hash) {
        final Bytes signature = signer.sign(hash.toByteArray()).getBytes();
        return SignatureObject.newBuilder()
                .type(SignatureType.SHA_384_WITH_RSA)
                .length((int) signature.length())
                .checksum(101 - (int) signature.length()) // simple checksum to detect if at wrong place in
                .signature(signature)
                .hashObject(new HashObject(HashAlgorithm.SHA_384, (int) hash.length(), hash))
                .build();
    }

    /**
     * Get the signature file path for a record file
     *
     * @param recordFilePath the path to the record file
     * @return the path to the signature file
     */
    private static Path getSigFilePath(@NonNull final Path recordFilePath) {
        String recordFileName = recordFilePath.getFileName().toString();
        if (recordFileName.endsWith(BlockRecordWriterV6.COMPRESSION_ALGORITHM_EXTENSION)) {
            recordFileName = recordFileName.substring(
                    0, recordFileName.length() - BlockRecordWriterV6.COMPRESSION_ALGORITHM_EXTENSION.length());
        }
        return recordFilePath.resolveSibling(recordFileName + RECORD_SIG_EXTENSION_SUFFIX);
    }
}
