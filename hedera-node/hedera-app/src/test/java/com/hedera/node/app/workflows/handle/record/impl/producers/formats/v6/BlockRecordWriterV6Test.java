// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers.formats.v6;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static com.hedera.node.app.records.RecordTestData.BLOCK_NUM;
import static com.hedera.node.app.records.RecordTestData.ENDING_RUNNING_HASH_OBJ;
import static com.hedera.node.app.records.RecordTestData.SIGNER;
import static com.hedera.node.app.records.RecordTestData.STARTING_RUNNING_HASH_OBJ;
import static com.hedera.node.app.records.RecordTestData.TEST_BLOCKS;
import static com.hedera.node.app.records.RecordTestData.VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.records.impl.producers.SerializedSingleTransactionRecord;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.apache.logging.log4j.LogManager;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Signer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class BlockRecordWriterV6Test extends AppTestBase {
    /** This build is pre-configured with standard settings for the record stream tests. */
    private TestAppBuilder appBuilder;

    private Signer signer;
    private FileSystem fileSystem;
    private App app;
    private BlockRecordStreamConfig config;
    private BlockRecordWriterV6 writer;
    private Instant consensusTime;
    private long blockNumber;
    private SemanticVersion hapiVersion;
    private Path recordPath;
    private Path sigPath;

    @BeforeEach
    void setUp() {
        fileSystem = Jimfs.newFileSystem(Configuration.unix());
        final var tempDir = fileSystem.getPath("/temp");

        signer = SIGNER;
        consensusTime = Instant.ofEpochSecond(1_535_127_942L, 890);
        blockNumber = BLOCK_NUM;

        appBuilder = appBuilder()
                .withHapiVersion(VERSION)
                .withConfigValue("hedera.recordStream.logDir", tempDir.toString())
                .withConfigValue("hedera.recordStream.sidecarDir", "sidecar")
                .withConfigValue("hedera.recordStream.recordFileVersion", 6)
                .withConfigValue("hedera.recordStream.signatureFileVersion", 6)
                .withConfigValue("hedera.recordStream.sidecarMaxSizeMb", 256);
    }

    void createApp() throws IOException {
        app = appBuilder.build();
        config = app.configProvider().getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        hapiVersion = app.hapiVersion();
        writer = new BlockRecordWriterV6(config, selfNodeInfo, SIGNER, fileSystem);
        final var ext = ".rcd.gz";
        final var recordDir =
                fileSystem.getPath(config.logDir(), "record" + asAccountString(selfNodeInfo.accountId()) + "/");
        recordPath = recordDir.resolve("2018-08-24T16_25_42.000000890Z" + ext);
        sigPath = recordDir.resolve("2018-08-24T16_25_42.000000890Z.rcd_sig");
    }

    @Nested
    @DisplayName("Constructor Tests")
    final class ConstructorTests {
        BlockRecordStreamConfig buildAndGetConfig() {
            return appBuilder.build().configProvider().getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        }

        @Test
        @DisplayName("Cannot pass null args to constructor")
        @SuppressWarnings("DataFlowIssue")
        void nullArgsToConstructorThrows() {
            final var config = buildAndGetConfig();
            assertThatThrownBy(() -> new BlockRecordWriterV6(null, selfNodeInfo, signer, fileSystem))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new BlockRecordWriterV6(config, null, signer, fileSystem))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new BlockRecordWriterV6(config, selfNodeInfo, null, fileSystem))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new BlockRecordWriterV6(config, selfNodeInfo, signer, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Record File Version must be V6")
        void recordFileVersionMustBeV6() {
            appBuilder.withConfigValue("hedera.recordStream.recordFileVersion", 5);
            final var config = buildAndGetConfig();
            assertThatThrownBy(() -> new BlockRecordWriterV6(config, selfNodeInfo, SIGNER, fileSystem))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("record file version");
        }

        @Test
        @DisplayName("Signature File Version must be V6")
        void signatureFileVersionMustBeV6() {
            appBuilder.withConfigValue("hedera.recordStream.signatureFileVersion", 5);
            final var config = buildAndGetConfig();
            assertThatThrownBy(() -> new BlockRecordWriterV6(config, selfNodeInfo, SIGNER, fileSystem))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("signature file version");
        }

        @Test
        @DisplayName("Bad record paths is fatal")
        void badRecordPath() {
            // A path cannot have the null character in it
            appBuilder.withConfigValue("hedera.recordStream.logDir", "\0IllegalPath/records");
            final var config = buildAndGetConfig();
            assertThatThrownBy(() -> new BlockRecordWriterV6(config, selfNodeInfo, SIGNER, fileSystem))
                    .isInstanceOf(InvalidPathException.class);
        }

        @Test
        @DisplayName("Fail to create record directory is fatal")
        @Disabled("This test succeeds individually, but fails when run with the rest of the tests.")
        void recordDirectoryCouldNotBeCreated() throws IOException {
            // Given a "logDir" in the config that points not to a directory, but to a pre-existing FILE (!!!)
            final var config = buildAndGetConfig();
            final var recordDir =
                    fileSystem.getPath(config.logDir(), "record" + asAccountString(selfNodeInfo.accountId()) + "/");
            Files.createDirectories(recordDir.getParent());
            Files.createFile(recordDir);

            // When we attempt to create the writer, then it fails AND logs!
            final var logCaptor = new LogCaptor(LogManager.getLogger(BlockRecordWriterV6.class));
            assertThatThrownBy(() -> new BlockRecordWriterV6(config, selfNodeInfo, SIGNER, fileSystem))
                    .isInstanceOf(UncheckedIOException.class);
            assertThat(logCaptor.fatalLogs()).hasSize(1);
            assertThat(logCaptor.fatalLogs()).allMatch(msg -> msg.contains("Could not create record directory"));
        }
    }

    @Nested
    @DisplayName("Initialization Tests")
    final class InitializationTests {
        @Test
        @DisplayName("Check Args to init()")
        @SuppressWarnings("DataFlowIssue")
        void checkArgsToInit() throws IOException {
            createApp();
            assertThatThrownBy(() -> writer.init(null, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> writer.init(hapiVersion, null, consensusTime, blockNumber))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, null, blockNumber))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Verify that the filesystem contains the directory based on the config (logDir) and the memo of the node info
         * and all the other criteria for the record file path.
         */
        @Test
        @DisplayName("Record file path is based on block number, consensus time, configuration, and node memo")
        void recordFileInExpectedLocation() throws IOException {
            createApp();
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            assertThat(Files.exists(recordPath)).isTrue();
        }

        @Test
        @DisplayName("Writing records into an existing directory works")
        void recordFileInExistingLocation() throws IOException {
            // Given a configuration and a pre-existing record directory
            app = appBuilder.build();
            config = app.configProvider().getConfiguration().getConfigData(BlockRecordStreamConfig.class);
            hapiVersion = app.hapiVersion();
            final var recordDir =
                    fileSystem.getPath(config.logDir(), "record" + asAccountString(selfNodeInfo.accountId()) + "/");
            Files.createDirectories(recordDir);

            // When we create a new writer and initialize it
            writer = new BlockRecordWriterV6(config, selfNodeInfo, SIGNER, fileSystem);
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);

            // Then it didn't throw, and the record file exists
            recordPath = recordDir.resolve("2018-08-24T16_25_42.000000890Z.rcd.gz");
            assertThat(Files.exists(recordPath)).isTrue();
        }

        @Test
        @DisplayName("Cannot call init() twice")
        void cannotCallInitTwice() throws IOException {
            createApp();
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            assertThatThrownBy(() -> writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Cannot call init() after close()")
        void cannotCallInitAfterClose() throws IOException {
            createApp();
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            writer.close(ENDING_RUNNING_HASH_OBJ);
            assertThatThrownBy(() -> writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Cannot call init() after writeItem()")
        void cannotCallInitAfterWriteItem() throws IOException {
            createApp();
            final var testItem = TEST_BLOCKS.get(0).get(0);
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            writer.writeItem(BlockRecordFormatV6.INSTANCE.serialize(testItem, blockNumber, hapiVersion));
            assertThatThrownBy(() -> writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Fail to write to disk")
        @Disabled("This test succeeds individually, but fails when run with the rest of the tests.")
        void couldNotWriteToDisk() throws IOException {
            // Given a record file that already exists but won't work (it is a directory instead of a file!!)
            createApp();
            Files.createDirectory(recordPath);

            // When we attempt to initialize the writer, it fails horribly!
            final var logCaptor = new LogCaptor(LogManager.getLogger(BlockRecordWriterV6.class));
            assertThatThrownBy(() -> writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber))
                    .isInstanceOf(UncheckedIOException.class);
            assertThat(logCaptor.warnLogs()).hasSize(1);
            assertThat(logCaptor.warnLogs()).allMatch(msg -> msg.contains("Error initializing record file"));
        }
    }

    @Nested
    @DisplayName("Writing Tests")
    final class WritingTests {
        public static Stream<Arguments> provideRecordStreamItems() {
            return Stream.of(
                    Arguments.of(TEST_BLOCKS.get(0), true),
                    Arguments.of(TEST_BLOCKS.get(1), false),
                    Arguments.of(TEST_BLOCKS.get(2), true),
                    Arguments.of(TEST_BLOCKS.get(3), false));
        }

        @Test
        @DisplayName("Cannot write a null record item")
        @SuppressWarnings("DataFlowIssue")
        void cannotWriteNullRecordItem() throws IOException {
            createApp();
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            assertThatThrownBy(() -> writer.writeItem(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Cannot write without calling init first")
        void cannotWriteWithoutCallingInit() throws IOException {
            createApp();
            final var testItem = TEST_BLOCKS.get(0).get(0);
            final var testSerItem = BlockRecordFormatV6.INSTANCE.serialize(testItem, blockNumber, hapiVersion);
            assertThatThrownBy(() -> writer.writeItem(testSerItem)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Cannot write after calling close")
        void cannotWriteAfterCallingClose() throws IOException {
            createApp();
            final var testItem = TEST_BLOCKS.get(0).get(0);
            final var testSerItem = BlockRecordFormatV6.INSTANCE.serialize(testItem, blockNumber, hapiVersion);
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            writer.close(ENDING_RUNNING_HASH_OBJ);
            assertThatThrownBy(() -> writer.writeItem(testSerItem)).isInstanceOf(IllegalStateException.class);
        }

        @ParameterizedTest
        @MethodSource("provideRecordStreamItems")
        @DisplayName("Write a list of record stream items including sidecars")
        void writingTest(final List<SingleTransactionRecord> singleTransactionRecords) throws Exception {
            createApp();

            // For each of the transaction records in the block, convert them into serialized records, and then write
            // them using the writer.
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            var previousHash = STARTING_RUNNING_HASH_OBJ.hash();
            boolean hasSidecars = false;
            for (final var rec : singleTransactionRecords) {
                final var serializedRec = BlockRecordFormatV6.INSTANCE.serialize(rec, blockNumber, hapiVersion);
                previousHash = BlockRecordFormatV6.INSTANCE.computeNewRunningHash(previousHash, List.of(serializedRec));
                writer.writeItem(serializedRec);
                if (!rec.transactionSidecarRecords().isEmpty()) hasSidecars = true;
            }
            final var endRunningHash = new HashObject(HashAlgorithm.SHA_384, (int) previousHash.length(), previousHash);
            writer.close(endRunningHash);

            // read written file and validate hashes
            final var readRecordStreamFile =
                    com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordReaderV6.read(recordPath);
            assertThat(readRecordStreamFile).isNotNull();
            assertThat(readRecordStreamFile.hapiProtoVersion()).isEqualTo(VERSION);
            assertThat(readRecordStreamFile.blockNumber()).isEqualTo(BLOCK_NUM);
            assertThat(readRecordStreamFile.startObjectRunningHash()).isEqualTo(STARTING_RUNNING_HASH_OBJ);
            final var readRecordStreamItems = readRecordStreamFile.recordStreamItems();
            assertThat(readRecordStreamItems).isNotNull();
            for (int i = 0; i < singleTransactionRecords.size(); i++) {
                final var singleTransactionRecord = singleTransactionRecords.get(i);
                final var recordStreamItem = readRecordStreamItems.get(i);
                assertThat(recordStreamItem.transaction()).isEqualTo(singleTransactionRecord.transaction());
                assertThat(recordStreamItem.record()).isEqualTo(singleTransactionRecord.transactionRecord());
            }
            assertThat(readRecordStreamFile.endObjectRunningHash()).isEqualTo(endRunningHash);
            com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordReaderV6.validateHashes(
                    readRecordStreamFile);

            // Check that the signature file was created.
            assertThat(Files.exists(sigPath)).isTrue();
            final var messageDigest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
            final var fileBytes = new GZIPInputStream(Files.newInputStream(recordPath)).readAllBytes();
            byte[] fileHash = messageDigest.digest(fileBytes);
            final var dupe = fileSystem.getPath("/sigCheck.rcd");
            SignatureWriterV6.writeSignatureFile(
                    dupe,
                    Bytes.wrap(fileHash),
                    SIGNER,
                    true,
                    6,
                    hapiVersion,
                    blockNumber,
                    STARTING_RUNNING_HASH_OBJ.hash(),
                    previousHash);
            final var sigBytes = Files.readAllBytes(sigPath);
            final var dupeBytes = Files.readAllBytes(fileSystem.getPath("/sigCheck.rcd_sig"));
            assertThat(sigBytes).isEqualTo(dupeBytes);

            // Check that the sidecar file exists (if the block records produced any sidecars)
            final var sidecarPath = recordPath.getParent().resolve("sidecar/2018-08-24T16_25_42.000000890Z_01.rcd.gz");
            final var sidecarMarker = recordPath.getParent().resolve("sidecar/2018-08-24T16_25_42.000000890Z_01.mf");
            assertThat(Files.exists(sidecarPath)).isEqualTo(hasSidecars);
            assertThat(Files.exists(sidecarMarker)).isEqualTo(hasSidecars);
            assertThat(anyMarkerFilesExist(recordPath.getParent().resolve("sidecar")))
                    .isEqualTo(hasSidecars);
        }

        @Test
        @DisplayName("Cannot write to record file leads to major error")
        void cannotWriteToRecordFile() throws IOException {
            createApp();
            final var singleTransactionRecords = TEST_BLOCKS.get(2);

            // Write the first record to the file, then delete the file so that the next write will fail
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            var rec = singleTransactionRecords.get(0);
            var serializedRec = BlockRecordFormatV6.INSTANCE.serialize(rec, blockNumber, hapiVersion);
            writer.writeItem(serializedRec);
            fileSystem.close();

            // This "fake" serialized record is big enough to cause any buffers to flush and actually try to write to
            // the underlying filesystem, which will fail since we closed it above
            final var bigRec = new SerializedSingleTransactionRecord(
                    randomBytes(1024 * 1024), randomBytes(1024 * 1024), List.of(), List.of());
            assertThatThrownBy(() -> writer.writeItem(bigRec)).isInstanceOf(UncheckedIOException.class);
        }

        @Test
        @DisplayName("Failure to write to sidecars is not fatal")
        @Disabled("This test succeeds individually, but fails when run with the rest of the tests.")
        void badSidecarsIsNotFatal() throws IOException {
            // Given a directory for sidecars that has already been created AS A FILE (!!)
            createApp();
            final var sidecarDir = recordPath.getParent().resolve("sidecar");
            Files.createDirectories(sidecarDir.getParent());
            Files.createFile(sidecarDir);

            // When we attempt to write a block with sidecars, everything else succeeds, but the side car files are
            // not actually created. But we do log the warnings.

            final var singleTransactionRecords = TEST_BLOCKS.get(2);
            final var logCaptor = new LogCaptor(LogManager.getLogger(BlockRecordWriterV6.class));

            // For each of the transaction records in the block, convert them into serialized records, and then write
            // them using the writer.
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            var previousHash = STARTING_RUNNING_HASH_OBJ.hash();
            for (final var rec : singleTransactionRecords) {
                final var serializedRec = BlockRecordFormatV6.INSTANCE.serialize(rec, blockNumber, hapiVersion);
                previousHash = BlockRecordFormatV6.INSTANCE.computeNewRunningHash(previousHash, List.of(serializedRec));
                writer.writeItem(serializedRec);
            }
            final var endRunningHash = new HashObject(HashAlgorithm.SHA_384, (int) previousHash.length(), previousHash);
            writer.close(endRunningHash);

            assertThat(logCaptor.warnLogs()).hasSizeGreaterThan(0);
            assertThat(logCaptor.warnLogs()).allMatch(msg -> msg.contains("sidecar"));
            assertThat(anyMarkerFilesExist(recordPath.getParent().resolve("sidecar")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Closing Tests")
    final class ClosingTests {
        @Test
        @DisplayName("Check Args to close()")
        @SuppressWarnings("DataFlowIssue")
        void checkArgsToInit() throws IOException {
            createApp();
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            assertThatThrownBy(() -> writer.close(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Cannot close() before init()")
        void cannotCloseBeforeInit() throws IOException {
            createApp();
            assertThatThrownBy(() -> writer.close(ENDING_RUNNING_HASH_OBJ)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Cannot close() twice")
        void cannotCloseTwice() throws IOException {
            createApp();
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            writer.close(ENDING_RUNNING_HASH_OBJ);
            assertThatThrownBy(() -> writer.close(ENDING_RUNNING_HASH_OBJ)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Close may throw a horrible exception")
        void cannotWriteToRecordFile() throws IOException {
            createApp();
            final var singleTransactionRecords = TEST_BLOCKS.get(2);

            // Write the first record to the file, then delete the file so that the next write will fail
            writer.init(hapiVersion, STARTING_RUNNING_HASH_OBJ, consensusTime, blockNumber);
            var rec = singleTransactionRecords.get(0);
            var serializedRec = BlockRecordFormatV6.INSTANCE.serialize(rec, blockNumber, hapiVersion);
            writer.writeItem(serializedRec);
            fileSystem.close();

            final var logCaptor = new LogCaptor(LogManager.getLogger(BlockRecordWriterV6.class));
            assertThatThrownBy(() -> writer.close(ENDING_RUNNING_HASH_OBJ)).isInstanceOf(UncheckedIOException.class);
            assertThat(logCaptor.warnLogs()).hasSize(2);
            assertThat(logCaptor.warnLogs())
                    .matches(logs -> logs.getFirst().contains("Error closing sidecar file")
                            && logs.getLast().contains("Error closing record file"));
            assertThat(anyMarkerFilesExist(recordPath.getParent().resolve("sidecar")))
                    .isFalse();
        }
    }

    private boolean anyMarkerFilesExist(Path dir) {
        if (!dir.getFileSystem().isOpen() || Files.notExists(dir)) return false;
        try (Stream<Path> paths = Files.walk(dir, 2)) {
            return paths.anyMatch(p -> p.toString().endsWith(".mf"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
