// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats.v6;

import static com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordWriterV6.COMPRESSION_ALGORITHM_EXTENSION;
import static com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordWriterV6.RECORD_EXTENSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.hapi.streams.SidecarFile;
import com.hedera.hapi.streams.SidecarMetadata;
import com.hedera.hapi.streams.SidecarType;
import com.hedera.hapi.streams.SignatureFile;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.crypto.HashingOutputStream;
import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

@SuppressWarnings({"DataFlowIssue", "removal"})
public class RecordStreamV6Verifier {

    /** Main method to test verifier with known good record stream files from main net */
    static void main(String[] args) throws Exception {
        // directory containing downloaded record stream files and sidecar files
        Path recordFilesPath =
                Path.of(System.getProperty("user.home")).resolve("Desktop/Downloaded Record Stream Files");
        // node public key from https://hashscan.io
        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(HexFormat.of()
                .parseHex("308201a2300d06092a864886f70d01010105000382018f003082018a02820181009b18967c838877f85a64"
                        + "71ce9f164cef939b01830b9eb22c02cc6b72a907020b3e4c014dc711ea8e095b88d0b4858ec86b05a8c3a5"
                        + "9ac12d2b9fabcac282ceb618db00eb59716611d6706c81aa32d9dddc6a6c7b396ba202fedeb33f289a8872"
                        + "84ebfc07d166d02c2c6ed32c7324c3ec8ae22112854e18ab5ea07a615c5ef8004ec68ac70dc03003a47f7e"
                        + "fe103edce257d28e7961f428f1cfa2e6cf71bf45c564b82ccbda14a183f30c2c3d5a7afba7a004079e8770"
                        + "2c249e96a7b2fdd562fc16759efe75abe6a23d0d2f906a2df1d4b64cb2117a7304449c75319a7620c219a4"
                        + "ffc982e822b6e1a07be1cf98be9265d086dc271aa406310f8a846fd331239fe303bd5616c89080fb88639b"
                        + "7c0ceb14009381823e0433db6f9156e2bda1873d4aa9a3a639604bfbd11a6dd6ce03b4b0ceef95601c7d88"
                        + "a840397cdbca3ff214fbf58c9d9dbd79d39ea767e9ae5f6eab9fca05fc4800f557657c90c12c60e0216482"
                        + "5d4c33af4737374ea235b50313ed0b75bf89a6b79001fba74cc1319e55150203010001"));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPublicKey pubKey = (RSAPublicKey) kf.generatePublic(x509EncodedKeySpec);
        // read first block number from first file as we do not know it
        long firstBLockNumber = 0;
        try (var files = Files.list(recordFilesPath)) {
            final var firstRecordFile = files.filter(
                            path -> path.toString().endsWith(RECORD_EXTENSION + COMPRESSION_ALGORITHM_EXTENSION))
                    .sorted()
                    .findFirst();
            if (firstRecordFile.isPresent()) {
                firstBLockNumber =
                        BlockRecordReaderV6.read(firstRecordFile.get()).blockNumber();
            }
        }
        //        BlockRecordStreamConfig recordStreamConfig = new BlockRecordStreamConfig(
        //                true, "", "sidecar", 2, 5000, false, 256, 6, 6, true, true, 256, "concurrent");
        //        validateRecordStreamFiles(recordFilesPath, recordStreamConfig, pubKey, null, firstBLockNumber);
    }

    private record RecordFileSet(Path recordFile, Path signatureFile, Path[] sidecarFiles) {}

    /**
     * Validate record stream generated with the test data in this file
     *
     * @param recordsDir Path to directory containing record stream files
     * @param recordStreamConfig Config for record stream
     * @param userPublicKey The public key that is part of key pair used for signing record files
     * @param expectedDataBlocks OPTIONAL if null will not check contents
     */
    public static void validateRecordStreamFiles(
            final Path recordsDir,
            final BlockRecordStreamConfig recordStreamConfig,
            final PublicKey userPublicKey,
            final List<List<SingleTransactionRecord>> expectedDataBlocks,
            final long firstBlockNumber)
            throws Exception {
        final Path sidecarsDir = recordsDir.resolve(recordStreamConfig.sidecarDir());
        final String extension = RECORD_EXTENSION + COMPRESSION_ALGORITHM_EXTENSION;
        List<RecordFileSet> recordFileSets = scanForRecordFiles(recordsDir, sidecarsDir, extension);
        // start running hashes
        Bytes runningHash = null;
        // now check the generated record files
        for (int i = 0; i < recordFileSets.size(); i++) {
            RecordFileSet recordFileSet = recordFileSets.get(i);
            List<SingleTransactionRecord> expectedData = expectedDataBlocks == null ? null : expectedDataBlocks.get(i);
            runningHash = validateRecordAndSignatureFile(
                    i,
                    runningHash,
                    recordFileSet,
                    expectedData,
                    userPublicKey,
                    recordStreamConfig,
                    firstBlockNumber,
                    extension);
        }
    }

    /**
     * Scan a directory of record files and sidecar files
     */
    private static List<RecordFileSet> scanForRecordFiles(
            final Path recordFilesDir, final Path sidecarDir, final String extension) throws IOException {
        try (var files = Files.list(recordFilesDir)) {
            return files.filter(path -> path.toString().endsWith(extension))
                    .sorted()
                    .map(recordFile -> {
                        final String recordFileName = recordFile.getFileName().toString();
                        String recordFileNamePrefix =
                                recordFileName.substring(0, recordFileName.length() - extension.length());
                        Path signatureFile = recordFilesDir.resolve(recordFileNamePrefix + "rcd_sig");
                        try (var sidecarDirFiles = Files.list(sidecarDir)) {
                            Path[] sidecarFiles = sidecarDirFiles
                                    .filter(path -> path.toString().startsWith(recordFileNamePrefix))
                                    .sorted()
                                    .toArray(Path[]::new);
                            return new RecordFileSet(recordFile, signatureFile, sidecarFiles);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        }
    }

    /**
     * Check the record file, sidecar file and signature file exist and are not empty. Validate their contents and
     * return the running hash at the end of the file.
     */
    static Bytes validateRecordAndSignatureFile(
            final int blockIndex,
            final Bytes startingRunningHash,
            final RecordFileSet recordFileSet,
            final List<SingleTransactionRecord> transactionRecordList,
            PublicKey userPublicKey,
            BlockRecordStreamConfig recordStreamConfig,
            final long firstBlockNumber,
            final String extension)
            throws Exception {
        final var recordFilePath = recordFileSet.recordFile;
        // check record file
        final byte[] recordFileBytes = readRecordFile(recordFilePath);
        final RecordStreamFile recordFile = BlockRecordReaderV6.read(recordFileBytes);
        validateRecordFile(blockIndex, startingRunningHash, recordFilePath, recordFile, transactionRecordList);
        assertEquals(firstBlockNumber + blockIndex, recordFile.blockNumber());
        // check sideCar file
        final Path[] sidecarFiles = recordFileSet.sidecarFiles;
        final List<TransactionSidecarRecord> sideCarItems = transactionRecordList == null
                ? null
                : transactionRecordList.stream()
                        .map(SingleTransactionRecord::transactionSidecarRecords)
                        .flatMap(List::stream)
                        .toList();
        int sideCarItemsOffset = 0;
        for (int i = 0; i < sidecarFiles.length; i++) {
            var sidecarFilePath = sidecarFiles[i];
            assertTrue(
                    recordFile.sidecars().contains(i),
                    "Sidecar file [" + sidecarFilePath + "] not referenced in record file sidecar metadata");
            final int numReadSidecarItems = validateSidecarFile(
                    i, sidecarFilePath, recordFile.sidecars().get(i), extension, sideCarItemsOffset, sideCarItems);
            sideCarItemsOffset += numReadSidecarItems;
        }
        // check signature file
        validateSignatureFile(
                recordFileSet.signatureFile, recordFileBytes, recordFile, userPublicKey, recordStreamConfig);
        // return running hash
        return recordFile.endObjectRunningHash().hash();
    }

    private static int validateSidecarFile(
            final int id,
            final Path sidecarFilePath,
            final SidecarMetadata sidecarMetadata,
            final String extension,
            final int sideCarItemsOffset,
            final List<TransactionSidecarRecord> expectedSideCarItems)
            throws IOException, ParseException, NoSuchAlgorithmException {
        assertEquals(id, sidecarMetadata.id(), "Sidecar file id does not match id in record file metadata");
        assertTrue(
                sidecarFilePath.getFileName().toString().endsWith(id + extension),
                "Sidecar file name does not match id");
        final byte[] sidecarFileBytes = new GZIPInputStream(Files.newInputStream(sidecarFilePath)).readAllBytes();
        MessageDigest messageDigest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
        final byte[] sidecarFileHash = messageDigest.digest(sidecarFileBytes);
        assertEquals(
                HexFormat.of().formatHex(sidecarFileHash),
                sidecarMetadata.hash().hash().toHex(),
                "Sidecar file hash does not match hash in record file metadata");
        // parse sidecar file
        SidecarFile sidecarFile = SidecarFile.PROTOBUF.parse(BufferedData.wrap(sidecarFileBytes));
        // check metadata types list
        Set<SidecarType> sidecarTypeSet = new HashSet<>();
        for (var sidecarRecord : sidecarFile.sidecarRecords()) {
            switch (sidecarRecord.sidecarRecords().kind()) {
                case STATE_CHANGES -> sidecarTypeSet.add(SidecarType.CONTRACT_STATE_CHANGE);
                case ACTIONS -> sidecarTypeSet.add(SidecarType.CONTRACT_ACTION);
                case BYTECODE -> sidecarTypeSet.add(SidecarType.CONTRACT_BYTECODE);
            }
        }
        assertEquals(
                new ArrayList<>(sidecarTypeSet),
                sidecarMetadata.types(),
                "Sidecar file metadata types does not match types in sidecar file");
        // if we have expected items then validate them
        if (expectedSideCarItems != null) {
            List<TransactionSidecarRecord> sidecarRecords = sidecarFile.sidecarRecords();
            for (int i = 0; i < sidecarRecords.size(); i++) {
                var sidecarRecord = sidecarRecords.get(i);
                assertEquals(
                        expectedSideCarItems.get(sideCarItemsOffset + i),
                        sidecarRecord,
                        "Sidecar file item does not match expected");
            }
        }
        return sideCarItemsOffset + sidecarFile.sidecarRecords().size();
    }

    private static void validateRecordFile(
            final int blockIndex,
            final Bytes startingRunningHash,
            final Path recordFilePath,
            final RecordStreamFile recordFile,
            final List<SingleTransactionRecord> transactionRecordList)
            throws Exception {
        // check starting running hash
        if (startingRunningHash != null) {
            // validate staring running hash is what we expect
            assertEquals(
                    startingRunningHash.toHex(),
                    recordFile.startObjectRunningHash().hash().toHex(),
                    "expected record file start running hash to be correct, blockIndex[" + blockIndex
                            + "], recordFilePath=[" + recordFilePath + "]");
        }
        // validate running hashes in file
        BlockRecordReaderV6.validateHashes(recordFile);
        // check data is what we expect
        if (transactionRecordList != null) {
            List<RecordStreamItem> recordStreamItems = recordFile.recordStreamItems();
            for (int i = 0; i < recordStreamItems.size(); i++) {
                var item = recordStreamItems.get(i);
                var expectedItem = transactionRecordList.get(i);
                assertEquals(expectedItem.transactionRecord(), item.record());
                assertEquals(expectedItem.transaction(), item.transaction());
            }
        }
    }

    /** Validate all data in a signature file is correct */
    private static void validateSignatureFile(
            Path recordFileSigPath,
            byte[] recordFileBytes,
            RecordStreamFile recordFile,
            PublicKey userPublicKey,
            BlockRecordStreamConfig recordStreamConfig)
            throws Exception {
        // read signature file
        SignatureFile signatureFile;
        try (ReadableStreamingData in = new ReadableStreamingData(Files.newInputStream(recordFileSigPath))) {
            int version = in.readByte();
            assertEquals(recordStreamConfig.signatureFileVersion(), version);
            signatureFile = SignatureFile.PROTOBUF.parse(in);
        }
        // compute hash of record file
        MessageDigest digest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
        final byte[] recordFileHash = digest.digest(recordFileBytes);
        // compare hash
        assertEquals(
                signatureFile.fileSignature().hashObject().hash().toHex(),
                HexFormat.of().formatHex(recordFileHash));
        // validate signature
        assertEquals(
                com.hedera.hapi.streams.SignatureType.SHA_384_WITH_RSA,
                signatureFile.fileSignature().type());
        assertEquals(
                signatureFile.fileSignature().signature().length(),
                signatureFile.fileSignature().length(),
                "Bad fileSignature length in signature file");
        assertEquals(
                101 - (int) signatureFile.fileSignature().signature().length(),
                signatureFile.fileSignature().checksum(),
                "Bad fileSignature checksum in signature file");
        Signature sig = Signature.getInstance(SignatureType.RSA.signingAlgorithm(), SignatureType.RSA.provider());
        sig.initVerify(userPublicKey);
        sig.update(signatureFile.fileSignature().hashObject().hash().toByteArray());
        assertTrue(sig.verify(signatureFile.fileSignature().signature().toByteArray()));
        // create metadata hash
        HashingOutputStream hashingOutputStream =
                new HashingOutputStream(MessageDigest.getInstance(DigestType.SHA_384.algorithmName()));
        SerializableDataOutputStream dataOutputStream = new SerializableDataOutputStreamImpl(hashingOutputStream);
        dataOutputStream.writeInt(recordStreamConfig.recordFileVersion());
        dataOutputStream.writeInt(recordFile.hapiProtoVersion().major());
        dataOutputStream.writeInt(recordFile.hapiProtoVersion().minor());
        dataOutputStream.writeInt(recordFile.hapiProtoVersion().patch());
        recordFile.startObjectRunningHash().hash().writeTo(dataOutputStream);
        recordFile.endObjectRunningHash().hash().writeTo(dataOutputStream);
        dataOutputStream.writeLong(recordFile.blockNumber());
        dataOutputStream.close();
        final byte[] metadataHash = hashingOutputStream.getDigest();
        // compare metadata hash
        assertEquals(
                HexFormat.of().formatHex(metadataHash),
                signatureFile.metadataSignature().hashObject().hash().toHex());
        // validate metadata signature
        assertEquals(
                com.hedera.hapi.streams.SignatureType.SHA_384_WITH_RSA,
                signatureFile.metadataSignature().type());
        assertEquals(
                signatureFile.metadataSignature().signature().length(),
                signatureFile.metadataSignature().length(),
                "Bad metadataSignature length in signature file");
        assertEquals(
                101 - (int) signatureFile.metadataSignature().signature().length(),
                signatureFile.metadataSignature().checksum(),
                "Bad metadataSignature checksum in signature file");
        Signature sig2 = Signature.getInstance(SignatureType.RSA.signingAlgorithm(), SignatureType.RSA.provider());
        sig2.initVerify(userPublicKey);
        sig2.update(metadataHash);
        assertTrue(sig2.verify(signatureFile.metadataSignature().signature().toByteArray()));
    }

    private static byte[] readRecordFile(final Path recordFilePath) throws IOException {
        if (recordFilePath.getFileName().toString().endsWith(".rcd.gz")) {
            try (final GZIPInputStream in = new GZIPInputStream(Files.newInputStream(recordFilePath))) {
                return in.readAllBytes();
            }
        } else if (recordFilePath.getFileName().toString().endsWith(".rcd")) {
            return Files.readAllBytes(recordFilePath);
        } else {
            fail("Unknown file type: " + recordFilePath.getFileName());
            return null;
        }
    }
}
