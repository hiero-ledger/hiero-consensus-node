// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.state.State;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import java.io.DataOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link WrappedRecordBlockHashMigration}.
 */
@ExtendWith(MockitoExtension.class)
class WrappedRecordBlockHashMigrationTest {

    public static final String RECORDS = "RECORDS";

    @TempDir
    Path tempDir;

    @Mock
    private State state;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private WritableStates writableStates;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private WritableSingletonStateBase<BlockInfo> blockInfoSingleton;

    private final WrappedRecordBlockHashMigration subject = new WrappedRecordBlockHashMigration();

    @Test
    void skipsWhenStreamModeIsBlocks() {
        final var config = configWith("BLOCKS", true, b -> {});
        assertDoesNotThrow(() -> subject.execute(StreamMode.BLOCKS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void skipsWhenComputeHashesIsFalse() {
        final var config = configWith(RECORDS, false, b -> {});
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartFilePathBlank() {
        final var config = configWith(RECORDS, true, b -> b.withValue("hedera.recordStream.jumpstartFile", ""));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartFileNotFound() {
        final var config = configWith(
                RECORDS,
                true,
                b -> b.withValue(
                        "hedera.recordStream.jumpstartFile",
                        tempDir.resolve("nonexistent.bin").toString()));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenWrappedRecordHashesDirBlank() throws Exception {
        final var jumpstartFile = createJumpstartFile(0, 1, 1);
        final var config = configWith(
                RECORDS, true, b -> b.withValue("hedera.recordStream.jumpstartFile", jumpstartFile.toString())
                        .withValue("hedera.recordStream.wrappedRecordHashesDir", ""));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenRecentHashesFileNotFound() throws Exception {
        final var jumpstartFile = createJumpstartFile(0, 1, 1);
        final var emptyDir = tempDir.resolve("empty-recent-dir");
        Files.createDirectories(emptyDir);
        final var config = configWith(
                RECORDS, true, b -> b.withValue("hedera.recordStream.jumpstartFile", jumpstartFile.toString())
                        .withValue("hedera.recordStream.wrappedRecordHashesDir", emptyDir.toString()));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartFileIsEmpty() throws Exception {
        final var emptyFile = tempDir.resolve("jumpstart.bin");
        Files.createFile(emptyFile);
        final var recentDir = createRecentHashesDir(List.of(entry(100)));
        final var config = enabledConfigWithFiles(emptyFile, recentDir);
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartFileIsTruncatedAfterBlockNumber() throws Exception {
        // File has only the 8-byte block number — prev hash bytes are missing
        final var truncatedFile = tempDir.resolve("jumpstart.bin");
        try (var out = new DataOutputStream(Files.newOutputStream(truncatedFile))) {
            out.writeLong(100L);
        }
        final var recentDir = createRecentHashesDir(List.of(entry(100)));
        final var config = enabledConfigWithFiles(truncatedFile, recentDir);
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartFileIsTruncatedAfterPrevHash() throws Exception {
        // File has block number + prev hash but is missing the hasher state
        final var truncatedFile = tempDir.resolve("jumpstart.bin");
        try (var out = new DataOutputStream(Files.newOutputStream(truncatedFile))) {
            out.writeLong(100L);
            out.write(new byte[HASH_SIZE]); // prev hash present, but nothing after
        }
        final var recentDir = createRecentHashesDir(List.of(entry(100)));
        final var config = enabledConfigWithFiles(truncatedFile, recentDir);
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartHasherIsEmpty() throws Exception {
        final var config =
                enabledConfigWithFiles(createJumpstartFile(0, 0, 0), createRecentHashesDir(List.of(entry(100))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenRecentHashesLogIsEmpty() throws Exception {
        final var config = enabledConfigWithFiles(createJumpstartFile(0, 1, 1), createRecentHashesDir(List.of()));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartBlockNumBeforeFirstRecentBlock() throws Exception {
        // jumpstartBlockNumber 50 < first recent block 100
        final var config = fullMigrationConfig(
                createJumpstartFile(50, 1, 1), createRecentHashesDir(List.of(entry(100), entry(101))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartBlockNumAfterLastRecentBlock() throws Exception {
        // jumpstartBlockNumber 200 > last recent block 101
        final var config = fullMigrationConfig(
                createJumpstartFile(200, 1, 1), createRecentHashesDir(List.of(entry(100), entry(101))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenStateBlockNumMismatchesLastRecentBlock() throws Exception {
        mockStateBlockInfo(999L); // Mismatches last recent block (101)
        final var config = fullMigrationConfig(
                createJumpstartFile(100, 1, 1), createRecentHashesDir(List.of(entry(100), entry(101))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verify(blockInfoSingleton, never()).put(any());
    }

    @Test
    void returnsEarlyWhenNeededRecordsHaveGap() throws Exception {
        mockStateBlockInfo(104L);
        final var config = fullMigrationConfig(
                createJumpstartFile(100, 1, 1), createRecentHashesDir(List.of(entry(100), entry(102), entry(104))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verify(blockInfoSingleton, never()).put(any());
    }

    @Test
    void returnsEarlyWhenNeededRecordsHaveDuplicateBlockNumbers() throws Exception {
        mockStateBlockInfo(103L);
        final var config = fullMigrationConfig(
                createJumpstartFile(100, 4, 1),
                createRecentHashesDir(List.of(entry(100), entry(101), entry(101), entry(103))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
        verify(blockInfoSingleton, never()).put(any());
    }

    @Test
    void successfullyComputesAndWritesWrappedRecordHashes() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 90; i <= 100; i++) {
            entries.add(entry(i));
        }
        mockStateBlockInfo(100L);
        final var config = fullMigrationConfig(createJumpstartFile(98, 4, 1), createRecentHashesDir(entries));

        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));

        final var captor = ArgumentCaptor.forClass(BlockInfo.class);
        verify(blockInfoSingleton).put(captor.capture());
        verify(blockInfoSingleton).commit();
        final var writtenBlockInfo = captor.getValue();
        assertThat(writtenBlockInfo.previousWrappedBlockRootHash()).isNotNull();
        assertThat(writtenBlockInfo.previousWrappedBlockRootHash().length()).isEqualTo(HASH_SIZE);
        assertThat(writtenBlockInfo.wrappedIntermediateBlockRootsLeafCount()).isGreaterThan(0);
    }

    /**
     * Uses the static test resource files generated by JumpstartTestFilesGenerator.
     * Jumpstart block: 45, hasher: 31 leaves. Recent hashes: blocks 10–109.
     * Migration processes blocks 46–109 (64 blocks) using the static files.
     */
    @Test
    void successfullyMigratesWithStaticTestFiles() throws Exception {
        // Block 109 is the last entry in the static recent-hashes file
        mockStateBlockInfo(109L);

        final URI jumpstartUri = getClass()
                .getClassLoader()
                .getResource("records/impl/jumpstart.bin")
                .toURI();
        final Path jumpstartFile = Paths.get(jumpstartUri);
        final Path recentHashesDir = Paths.get(getClass()
                .getClassLoader()
                .getResource("records/impl/recent-hashes")
                .toURI());

        final var config = configWith(
                RECORDS, true, b -> b.withValue("hedera.recordStream.jumpstartFile", jumpstartFile.toString())
                        .withValue("hedera.recordStream.wrappedRecordHashesDir", recentHashesDir.toString())
                        .withValue("hedera.recordStream.numOfBlockHashesInState", 256));

        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));

        final var captor = ArgumentCaptor.forClass(BlockInfo.class);
        verify(blockInfoSingleton).put(captor.capture());
        verify(blockInfoSingleton).commit();
        final var written = captor.getValue();
        assertThat(written.previousWrappedBlockRootHash()).isNotNull();
        assertThat(written.previousWrappedBlockRootHash().length()).isEqualTo(HASH_SIZE);
        // Hasher started with 31 leaves and processed 65 blocks (45–109 inclusive, >= jumpstart block)
        assertThat(written.wrappedIntermediateBlockRootsLeafCount()).isEqualTo(31 + 65);
    }

    @Test
    void handlesEmptyRecentHashesListGracefully() throws Exception {
        final var config = enabledConfigWithFiles(createJumpstartFile(0, 1, 1), createRecentHashesDir(List.of()));
        assertDoesNotThrow(
                () -> subject.execute(StreamMode.RECORDS, config, state),
                "Should handle empty recent hashes list without crashing");
    }

    @Test
    void handlesVeryLargeBlockNumbers() throws Exception {
        final var config = enabledConfigWithFiles(
                createJumpstartFile(0, 1, 1),
                createRecentHashesDir(
                        List.of(entry(Long.MAX_VALUE - 5), entry(Long.MAX_VALUE - 4), entry(Long.MAX_VALUE - 3))));
        assertDoesNotThrow(
                () -> subject.execute(StreamMode.RECORDS, config, state),
                "Should handle large block numbers without overflow");
    }

    @Test
    void handlesSingleEntryInRecentHashes() throws Exception {
        final var config =
                enabledConfigWithFiles(createJumpstartFile(0, 1, 1), createRecentHashesDir(List.of(entry(100))));
        assertDoesNotThrow(
                () -> subject.execute(StreamMode.RECORDS, config, state), "Should handle single-entry recent hashes");
    }

    @Test
    void handlesJumpstartBlockNumEqualsFirstRecentBlock() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 100; i <= 105; i++) {
            entries.add(entry(i));
        }
        mockStateBlockInfo(105L);
        final var config = fullMigrationConfig(createJumpstartFile(100, 1, 1), createRecentHashesDir(entries));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
    }

    @Test
    void handlesJumpstartBlockNumEqualsLastRecentBlock() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 100; i <= 105; i++) {
            entries.add(entry(i));
        }
        mockStateBlockInfo(105L);
        final var config = fullMigrationConfig(createJumpstartFile(105, 1, 1), createRecentHashesDir(entries));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, state));
    }

    /**
     * Creates a jumpstart file in the new binary format:
     * <ul>
     *   <li>8 bytes: block number (long)</li>
     *   <li>48 bytes: previous block root hash (SHA-384)</li>
     *   <li>8 bytes: streaming hasher leaf count (long)</li>
     *   <li>4 bytes: streaming hasher hash count (int)</li>
     *   <li>48 bytes × hash count: streaming hasher pending subtree hashes</li>
     * </ul>
     */
    private Path createJumpstartFile(long blockNumber, long leafCount, int numHashes) throws Exception {
        return createJumpstartFile(blockNumber, new byte[HASH_SIZE], leafCount, numHashes);
    }

    private Path createJumpstartFile(long blockNumber, byte[] prevHash, long leafCount, int numHashes)
            throws Exception {
        final var file = tempDir.resolve("jumpstart.bin");
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeLong(blockNumber);
            out.write(prevHash);
            out.writeLong(leafCount);
            out.writeInt(numHashes);
            for (int i = 0; i < numHashes; i++) {
                out.write(new byte[HASH_SIZE]);
            }
        }
        return file;
    }

    private Path createRecentHashesDir(List<WrappedRecordFileBlockHashes> entries) throws Exception {
        final var dir = tempDir.resolve("recent-hashes");
        Files.createDirectories(dir);
        final var file = dir.resolve(WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME);
        final var log =
                WrappedRecordFileBlockHashesLog.newBuilder().entries(entries).build();
        Files.write(file, WrappedRecordFileBlockHashesLog.PROTOBUF.toBytes(log).toByteArray());
        return dir;
    }

    private WrappedRecordFileBlockHashes entry(long blockNumber) {
        return WrappedRecordFileBlockHashes.newBuilder()
                .blockNumber(blockNumber)
                .outputItemsTreeRootHash(Bytes.wrap(new byte[HASH_SIZE]))
                .consensusTimestampHash(Bytes.wrap(new byte[HASH_SIZE]))
                .build();
    }

    private void mockStateBlockInfo(long lastBlockNumber) {
        final var blockInfo =
                BlockInfo.newBuilder().lastBlockNumber(lastBlockNumber).build();
        given(state.getWritableStates(eq(BlockRecordService.NAME))).willReturn(writableStates);
        doReturn(blockInfoSingleton).when(writableStates).getSingleton(eq(BLOCKS_STATE_ID));
        given(blockInfoSingleton.get()).willReturn(blockInfo);
    }

    @FunctionalInterface
    interface ConfigCustomizer {
        void customize(TestConfigBuilder builder);
    }

    private BlockRecordStreamConfig configWith(String streamMode, boolean computeHashes, ConfigCustomizer customizer) {
        final var builder = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", streamMode)
                .withValue("hedera.recordStream.computeHashesFromWrappedRecordBlocks", computeHashes)
                .withValue("hedera.recordStream.numOfBlockHashesInState", 256);
        customizer.customize(builder);
        return builder.getOrCreateConfig().getConfigData(BlockRecordStreamConfig.class);
    }

    private BlockRecordStreamConfig enabledConfigWithFiles(Path jumpstartFile, Path recentDir) {
        return enabledConfigWithFiles(jumpstartFile, recentDir, b -> {});
    }

    private BlockRecordStreamConfig enabledConfigWithFiles(
            Path jumpstartFile, Path recentDir, ConfigCustomizer customizer) {
        return configWith(RECORDS, true, b -> {
            b.withValue("hedera.recordStream.jumpstartFile", jumpstartFile.toString())
                    .withValue("hedera.recordStream.wrappedRecordHashesDir", recentDir.toString());
            customizer.customize(b);
        });
    }

    private BlockRecordStreamConfig fullMigrationConfig(Path jumpstartFile, Path recentDir) {
        return enabledConfigWithFiles(jumpstartFile, recentDir);
    }
}
