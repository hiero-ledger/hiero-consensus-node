// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.recordstreaming;

import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.parseRecordFileConsensusTime;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordStreamingUtilsTest {

    private static final String PATH_TO_FILES = "src/test/resources/recordstream";

    private static final String V6_RECORD_FILE = "2022-08-15T14_02_51.642641228Z.rcd.gz";
    private static final String V6_RECORD_UNCOMPRESSED_FILE = "2022-08-18T09_37_10.411994657Z.rcd";
    private static final String V6_RECORD_SIGNATURE_FILE = "2022-06-14T14_49_22.456975294Z.rcd_sig";
    private static final String V6_SIDECAR_FILE = "2022-08-15T14_02_58.158861963Z_01.rcd.gz";
    private static final String V6_SIDECAR_UNCOMPRESSED_FILE = "2022-08-18T09_37_10.411994657Z_01.rcd";
    private static final String V5_RECORD_FILE = "V5_2022-05-27T08_27_14.157194938Z.rcd";
    private static final String V5_RECORD_SIGNATURE_FILE = "V5_2022-05-27T08_27_14.157194938Z.rcd_sig";

    @Test
    void parsingV6RecordFilesSucceeds() throws IOException {
        final var recordFilePath = Path.of(PATH_TO_FILES, V6_RECORD_FILE);

        final var recordFilePair = RecordStreamingUtils.readRecordStreamFile(recordFilePath.toString());

        assertEquals(6, recordFilePair.getLeft());
        assertTrue(recordFilePair.getRight().isPresent());
    }

    @Test
    void filteringWorksForOrderedRecordFiles() throws IOException {
        final var filteredFiles =
                RecordStreamingUtils.orderedRecordFilesFrom(
                                "src/test/resources/multipleRecords/", file -> file.contains("3Z"))
                        .stream()
                        .map(f -> Paths.get(f).getFileName().toString())
                        .toList();
        assertEquals(List.of("2023-04-18T14_08_20.465612003Z.rcd.gz"), filteredFiles);
    }

    @Test
    void parsingUncompressedV6RecordFilesSucceeds() throws IOException {
        final var recordFilePath = Path.of(PATH_TO_FILES, V6_RECORD_UNCOMPRESSED_FILE);

        final var recordFilePair = RecordStreamingUtils.readUncompressedRecordStreamFile(recordFilePath.toString());

        assertEquals(6, recordFilePair.getLeft());
        assertTrue(recordFilePair.getRight().isPresent());
    }

    @Test
    void ordersSidecarsAsExpected() {
        final var aSidecar = "2022-08-15T14_02_58.158861963Z_02.rcd.gz";
        final var aSameTimeSidecarLaterSeq = "2022-08-15T14_02_58.158861963Z_03.rcd";
        final var anEarlierSidecar = "2022-08-15T14_02_54.158861963Z_99.rcd.gz";
        final var aLaterSidecar = "2022-08-15T14_03_04.158861963Z_01.rcd";

        final List<String> allSidecars = List.of(aSameTimeSidecarLaterSeq, aSidecar, anEarlierSidecar, aLaterSidecar);

        final List<String> expected = List.of(anEarlierSidecar, aSidecar, aSameTimeSidecarLaterSeq, aLaterSidecar);
        final var actual = allSidecars.stream()
                .sorted(RecordStreamingUtils.SIDECAR_FILE_COMPARATOR)
                .toList();
        assertEquals(expected, actual);
    }

    @Test
    void parsesBothZippedAndUnzippedRecordFileTimestamps() {
        final var expected = Instant.parse("2022-08-15T14:02:58.158861963Z");
        final var notFile = "2022-08-15T14_02_58.158861963Z.rcd.gz.txt";
        final var compressedFile = "2022-08-15T14_02_58.158861963Z.rcd.gz";
        final var uncompressedFile = "2022-08-15T14_02_58.158861963Z.rcd";

        final var actualCompressed = parseRecordFileConsensusTime(compressedFile);
        final var actualUncompressed = parseRecordFileConsensusTime(uncompressedFile);
        assertEquals(expected, actualCompressed);
        assertEquals(expected, actualUncompressed);
        assertThrows(IllegalArgumentException.class, () -> parseRecordFileConsensusTime(notFile));
    }

    @Test
    void parsingV6SignatureRecordFilesSucceeds() throws IOException {
        final var signatureFilePath = Path.of(PATH_TO_FILES, V6_RECORD_SIGNATURE_FILE);

        final var signatureFilePair = RecordStreamingUtils.readSignatureFile(signatureFilePath.toString());

        assertEquals(6, signatureFilePair.getLeft());
        assertTrue(signatureFilePair.getRight().isPresent());
    }

    @Test
    void parsingV6SidecarRecordFilesSucceeds() throws IOException {
        final var sidecarFilePath = Path.of(PATH_TO_FILES, V6_SIDECAR_FILE);

        final var sidecarFile = RecordStreamingUtils.readSidecarFile(sidecarFilePath.toString());

        assertNotNull(sidecarFile);
    }

    @Test
    void parsingUncompressedV6SidecarRecordFilesSucceeds() throws IOException {
        final var sidecarFilePath = Path.of(PATH_TO_FILES, V6_SIDECAR_UNCOMPRESSED_FILE);

        final var sidecarFile = RecordStreamingUtils.readUncompressedSidecarFile(sidecarFilePath.toString());

        assertNotNull(sidecarFile);
    }

    @Test
    void parsingUnknownRecordFilesReturnsEmptyPair() {
        final var recordFilePath = Path.of(PATH_TO_FILES, V5_RECORD_FILE);

        assertThrows(IOException.class, () -> RecordStreamingUtils.readRecordStreamFile(recordFilePath.toString()));
    }

    @Test
    void parsingUnknownUncompressedRecordFilesReturnsEmptyPair() {
        final var recordFilePath = Path.of(PATH_TO_FILES, V5_RECORD_FILE);

        assertThrows(
                IOException.class,
                () -> RecordStreamingUtils.readUncompressedRecordStreamFile(recordFilePath.toString()));
    }

    @Test
    void parsingUnknownSignatureRecordFilesReturnsEmptyPair() {
        final var signatureFilePath = Path.of(PATH_TO_FILES, V5_RECORD_SIGNATURE_FILE);

        assertThrows(IOException.class, () -> RecordStreamingUtils.readSignatureFile(signatureFilePath.toString()));
    }

    @Test
    void parsingUnknownSidecarFileReturnsEmptyOptional() {
        final var notSidecarFilePath = Path.of(PATH_TO_FILES, V5_RECORD_SIGNATURE_FILE);

        assertThrows(IOException.class, () -> RecordStreamingUtils.readSidecarFile(notSidecarFilePath.toString()));
    }

    @Test
    void parsingUnknownUncompressedSidecarFileReturnsEmptyOptional() {
        final var notSidecarFilePath = Path.of(PATH_TO_FILES, V5_RECORD_SIGNATURE_FILE);

        assertThrows(
                IOException.class,
                () -> RecordStreamingUtils.readUncompressedSidecarFile(notSidecarFilePath.toString()));
    }
}
