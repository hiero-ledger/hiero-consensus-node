// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpStartConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.state.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WrappedRecordBlockHashMigrationTest {

    public static final String RECORDS = "RECORDS";

    @TempDir
    Path tempDir;

    @Mock
    private State state;

    private final WrappedRecordBlockHashMigration subject = new WrappedRecordBlockHashMigration();

    @Test
    void skipsWhenStreamModeIsBlocks() {
        final var config = recordsConfigWith("BLOCKS", true, b -> {});
        assertDoesNotThrow(() -> subject.execute(StreamMode.BLOCKS, config, defaultJumpstartConfig()));
        assertNull(subject.result());
        verifyNoInteractions(state);
    }

    @Test
    void skipsWhenComputeHashesIsFalse() {
        final var config = recordsConfigWith(RECORDS, false, b -> {});
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, defaultJumpstartConfig()));
        assertNull(subject.result());
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartConfigNotPopulated() {
        final var config = recordsConfigWith(RECORDS, true, b -> {});
        // Default jumpstart config has blockNum=-1, meaning not configured
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, defaultJumpstartConfig()));
        assertNull(subject.result());
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenWrappedRecordHashesDirBlank() {
        final var config =
                recordsConfigWith(RECORDS, true, b -> b.withValue("hedera.recordStream.wrappedRecordHashesDir", ""));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1)));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenRecentHashesFileNotFound() throws Exception {
        final var emptyDir = tempDir.resolve("empty-recent-dir");
        Files.createDirectories(emptyDir);
        final var config = recordsConfigWith(
                RECORDS, true, b -> b.withValue("hedera.recordStream.wrappedRecordHashesDir", emptyDir.toString()));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1)));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartHasherIsEmpty() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 0, 0)));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenRecentHashesLogIsEmpty() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of()));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1)));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartBlockNumBeforeFirstRecentBlock() throws Exception {
        // jumpstartBlockNumber 50 < first recent block 100
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(50, 1, 1)));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartBlockNumAfterLastRecentBlock() throws Exception {
        // jumpstartBlockNumber 200 > last recent block 101
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(200, 1, 1)));
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenNeededRecordsHaveGap() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(102), entry(104))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(100, 1, 1)));
        assertNull(subject.result());
    }

    @Test
    void returnsEarlyWhenNeededRecordsHaveDuplicateBlockNumbers() throws Exception {
        final var config =
                enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101), entry(101), entry(103))));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(100, 4, 1)));
        assertNull(subject.result());
    }

    @Test
    void successfullyComputesWrappedRecordHashes() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 90; i <= 100; i++) {
            entries.add(entry(i));
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));

        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(98, 4, 1)));

        final var result = subject.result();
        assertThat(result).isNotNull();
        assertThat(result.previousWrappedRecordBlockRootHash()).isNotNull();
        assertThat(result.previousWrappedRecordBlockRootHash().length()).isEqualTo(HASH_SIZE);
        assertThat(result.wrappedIntermediateBlockRootsLeafCount()).isGreaterThan(0);
    }

    /**
     * Verifies migration completes successfully over a large block range.
     * Jumpstart block: 45, hasher: 31 leaves. Recent hashes: blocks 10–109.
     * Migration processes blocks 46–109 (64 blocks, > jumpstart block 45).
     */
    @Test
    void successfullyMigratesWithStaticTestFiles() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 10; i <= 109; i++) {
            entries.add(entry(i));
        }
        final var recentHashesDir = createRecentHashesDir(entries);

        final var config = recordsConfigWith(RECORDS, true, b -> b.withValue(
                        "hedera.recordStream.wrappedRecordHashesDir", recentHashesDir.toString())
                .withValue("hedera.recordStream.numOfBlockHashesInState", 256));

        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(45, 31, 5)));

        final var result = subject.result();
        assertThat(result).isNotNull();
        assertThat(result.previousWrappedRecordBlockRootHash()).isNotNull();
        assertThat(result.previousWrappedRecordBlockRootHash().length()).isEqualTo(HASH_SIZE);
        // Hasher started with 31 leaves and processed 64 blocks (46–109, > jumpstart block 45)
        assertThat(result.wrappedIntermediateBlockRootsLeafCount()).isEqualTo(31 + 64);
    }

    @Test
    void handlesEmptyRecentHashesListGracefully() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of()));
        assertDoesNotThrow(
                () -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1)),
                "Should handle empty recent hashes list without crashing");
    }

    @Test
    void handlesVeryLargeBlockNumbers() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(
                List.of(entry(Long.MAX_VALUE - 5), entry(Long.MAX_VALUE - 4), entry(Long.MAX_VALUE - 3))));
        assertDoesNotThrow(
                () -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1)),
                "Should handle large block numbers without overflow");
    }

    @Test
    void handlesSingleEntryInRecentHashes() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100))));
        assertDoesNotThrow(
                () -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1)),
                "Should handle single-entry recent hashes");
    }

    @Test
    void handlesJumpstartBlockNumEqualsFirstRecentBlock() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 100; i <= 105; i++) {
            entries.add(entry(i));
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(100, 1, 1)));
    }

    @Test
    void handlesJumpstartBlockNumEqualsLastRecentBlock() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 100; i <= 105; i++) {
            entries.add(entry(i));
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));
        assertDoesNotThrow(() -> subject.execute(StreamMode.RECORDS, config, jumpstartConfig(105, 1, 1)));
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

    @FunctionalInterface
    interface ConfigCustomizer {
        void customize(TestConfigBuilder builder);
    }

    private BlockRecordStreamConfig recordsConfigWith(
            String streamMode, boolean computeHashes, ConfigCustomizer customizer) {
        final var builder = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", streamMode)
                .withValue("hedera.recordStream.computeHashesFromWrappedRecordBlocks", computeHashes)
                .withValue("hedera.recordStream.numOfBlockHashesInState", 256);
        customizer.customize(builder);
        return builder.getOrCreateConfig().getConfigData(BlockRecordStreamConfig.class);
    }

    private BlockRecordStreamConfig enabledRecordsConfig(Path recentDir) {
        return recordsConfigWith(
                RECORDS, true, b -> b.withValue("hedera.recordStream.wrappedRecordHashesDir", recentDir.toString()));
    }

    private static BlockStreamJumpStartConfig defaultJumpstartConfig() {
        return new BlockStreamJumpStartConfig(-1, Bytes.wrap(new byte[HASH_SIZE]), 0, 0, List.of());
    }

    private static BlockStreamJumpStartConfig jumpstartConfig(long blockNumber, long leafCount, int numHashes) {
        final List<Bytes> subtreeHashes = new ArrayList<>(numHashes);
        for (int i = 0; i < numHashes; i++) {
            subtreeHashes.add(Bytes.wrap(new byte[HASH_SIZE]));
        }
        return new BlockStreamJumpStartConfig(
                blockNumber, Bytes.wrap(new byte[HASH_SIZE]), leafCount, numHashes, subtreeHashes);
    }
}
