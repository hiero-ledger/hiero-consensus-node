// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
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
        subject.execute(StreamMode.BLOCKS, config, defaultJumpstartConfig(), false);
        assertNull(subject.result());
        verifyNoInteractions(state);
    }

    @Test
    void skipsWhenComputeHashesIsFalse() {
        final var config = recordsConfigWith(RECORDS, false, b -> {});
        subject.execute(StreamMode.RECORDS, config, defaultJumpstartConfig(), false);
        assertNull(subject.result());
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartConfigNotPopulated() {
        final var config = recordsConfigWith(RECORDS, true, b -> {});
        // Default jumpstart config has blockNum=-1, meaning not configured
        subject.execute(StreamMode.RECORDS, config, defaultJumpstartConfig(), false);
        assertNull(subject.result());
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenWrappedRecordHashesDirBlank() {
        final var config =
                recordsConfigWith(RECORDS, true, b -> b.withValue("hedera.recordStream.wrappedRecordHashesDir", ""));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1), false);
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenRecentHashesFileNotFound() throws Exception {
        final var emptyDir = tempDir.resolve("empty-recent-dir");
        Files.createDirectories(emptyDir);
        final var config = recordsConfigWith(
                RECORDS, true, b -> b.withValue("hedera.recordStream.wrappedRecordHashesDir", emptyDir.toString()));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1), false);
        verifyNoInteractions(state);
    }

    @Test
    void skipsWhenMigrationAlreadyApplied() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 90; i <= 100; i++) {
            entries.add(entry(i));
        }
        final var recentHashesDir = createRecentHashesDir(entries);
        final var config = enabledRecordsConfig(recentHashesDir);

        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(98, 4, 1), true);
        assertNull(subject.result());
    }

    @Test
    void skipsExecutionAfterCrashWhenMigrationAlreadyApplied() throws Exception {
        // Initial migration succeeds
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 90; i <= 100; i++) {
            entries.add(entry(i));
        }
        final var recentHashesDir = createRecentHashesDir(entries);
        final var config = enabledRecordsConfig(recentHashesDir);
        final var jsConfig = jumpstartConfig(98, 4, 1);

        subject.execute(StreamMode.RECORDS, config, jsConfig, false);
        assertThat(subject.result()).isNotNull();

        // Remove the latest entry from the wrapped record hashes file, as would happen if the node went down days after
        // migration and the file rotated or was truncated
        final var truncatedEntries = new ArrayList<>(entries);
        truncatedEntries.removeLast();
        createRecentHashesDir(truncatedEntries); // Overwrites existing file in same dir

        // Instantiate a new migration instance, same config, but migrationAlreadyApplied=true
        final var restartSubject = new WrappedRecordBlockHashMigration();
        restartSubject.execute(StreamMode.RECORDS, config, jsConfig, true);

        assertNull(restartSubject.result());
    }

    @Test
    void returnsEarlyWhenHashCountMismatchesSubtreeHashes() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101))));
        // hashCount=5 but only 1 subtree hash provided
        final var badConfig = new BlockStreamJumpstartConfig(
                100,
                Bytes.wrap(new byte[HASH_SIZE]),
                4,
                5,
                List.of(Bytes.wrap(new byte[HASH_SIZE])),
                Bytes.wrap(new byte[HASH_SIZE]),
                Bytes.wrap(new byte[HASH_SIZE]));
        subject.execute(StreamMode.RECORDS, config, badConfig, false);
        assertNull(subject.result());
    }

    @Test
    void returnsEarlyWhenJumpstartHasherIsEmpty() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100))));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 0, 0), false);
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenRecentHashesLogIsEmpty() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of()));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1), false);
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartBlockNumBeforeFirstRecentBlock() throws Exception {
        // jumpstartBlockNumber 50 < first recent block 100
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101))));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(50, 1, 1), false);
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenJumpstartBlockNumAfterLastRecentBlock() throws Exception {
        // jumpstartBlockNumber 200 > last recent block 101
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101))));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(200, 1, 1), false);
        verifyNoInteractions(state);
    }

    @Test
    void returnsEarlyWhenNeededRecordsHaveGap() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(102), entry(104))));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(100, 1, 1), false);
        assertNull(subject.result());
    }

    @Test
    void returnsEarlyWhenNeededRecordsHaveDuplicateBlockNumbers() throws Exception {
        final var config =
                enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101), entry(101), entry(103))));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(100, 4, 1), false);
        assertNull(subject.result());
    }

    @Test
    void successfullyComputesWrappedRecordHashes() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 90; i <= 100; i++) {
            entries.add(entry(i));
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));

        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(98, 4, 1), false);

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

        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(45, 31, 5), false);

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
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1), false);
    }

    @Test
    void handlesVeryLargeBlockNumbers() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(
                List.of(entry(Long.MAX_VALUE - 5), entry(Long.MAX_VALUE - 4), entry(Long.MAX_VALUE - 3))));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1), false);
    }

    @Test
    void handlesSingleEntryInRecentHashes() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100))));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(0, 1, 1), false);
    }

    @Test
    void handlesJumpstartBlockNumEqualsFirstRecentBlock() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 100; i <= 105; i++) {
            entries.add(entry(i));
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(100, 1, 1), false);
    }

    @Test
    void handlesJumpstartBlockNumEqualsLastRecentBlock() throws Exception {
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 100; i <= 105; i++) {
            entries.add(entry(i));
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));
        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(105, 1, 1), false);
    }

    @Test
    void returnsEarlyWhenPreviousBlockHashHasWrongLength() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101))));
        // previousWrappedRecordBlockHash is 32 bytes instead of HASH_SIZE (48)
        final var badConfig = new BlockStreamJumpstartConfig(
                100,
                Bytes.wrap(new byte[32]),
                4,
                1,
                List.of(Bytes.wrap(new byte[HASH_SIZE])),
                Bytes.wrap(new byte[HASH_SIZE]),
                Bytes.wrap(new byte[HASH_SIZE]));
        subject.execute(StreamMode.RECORDS, config, badConfig, false);
        assertNull(subject.result());
    }

    @Test
    void returnsEarlyWhenSubtreeHashHasWrongLength() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101))));
        // One subtree hash is 32 bytes instead of HASH_SIZE (48)
        final var badConfig = new BlockStreamJumpstartConfig(
                100,
                Bytes.wrap(new byte[HASH_SIZE]),
                4,
                2,
                List.of(Bytes.wrap(new byte[HASH_SIZE]), Bytes.wrap(new byte[32])),
                Bytes.wrap(new byte[HASH_SIZE]),
                Bytes.wrap(new byte[HASH_SIZE]));
        subject.execute(StreamMode.RECORDS, config, badConfig, false);
        assertNull(subject.result());
    }

    @Test
    void returnsEarlyWhenJumpstartConsensusTimestampHashMismatches() throws Exception {
        final var matchingOutputHash = Bytes.wrap(new byte[HASH_SIZE]);
        final var fileTimestampHash = fillHash((byte) 0xAA);
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 90; i <= 100; i++) {
            entries.add(i == 98 ? entryWithHashes(i, fileTimestampHash, matchingOutputHash) : entry(i));
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));

        // Jumpstart config provides a different consensus-timestamp hash for block 98
        final var badConfig = new BlockStreamJumpstartConfig(
                98,
                Bytes.wrap(new byte[HASH_SIZE]),
                4,
                1,
                List.of(Bytes.wrap(new byte[HASH_SIZE])),
                fillHash((byte) 0x11),
                matchingOutputHash);

        subject.execute(StreamMode.RECORDS, config, badConfig, false);
        assertNull(subject.result());
    }

    @Test
    void returnsEarlyWhenJumpstartOutputItemsTreeRootHashMismatches() throws Exception {
        final var matchingTimestampHash = Bytes.wrap(new byte[HASH_SIZE]);
        final var fileOutputHash = fillHash((byte) 0xBB);
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 90; i <= 100; i++) {
            entries.add(i == 98 ? entryWithHashes(i, matchingTimestampHash, fileOutputHash) : entry(i));
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));

        // Jumpstart config provides a different output-items tree root hash for block 98
        final var badConfig = new BlockStreamJumpstartConfig(
                98,
                Bytes.wrap(new byte[HASH_SIZE]),
                4,
                1,
                List.of(Bytes.wrap(new byte[HASH_SIZE])),
                matchingTimestampHash,
                fillHash((byte) 0x22));

        subject.execute(StreamMode.RECORDS, config, badConfig, false);
        assertNull(subject.result());
    }

    @Test
    void skipsHashMatchCheckWhenCurrentBlockHashesNotPopulated() throws Exception {
        // When both currentBlock*Hash properties are empty, the new check is skipped and the
        // migration proceeds even though the file entry would not match empty hashes.
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 90; i <= 100; i++) {
            entries.add(entryWithHashes(i, fillHash((byte) 0xCC), fillHash((byte) 0xDD)));
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));

        final var jsConfig = new BlockStreamJumpstartConfig(
                98,
                Bytes.wrap(new byte[HASH_SIZE]),
                4,
                1,
                List.of(Bytes.wrap(new byte[HASH_SIZE])),
                Bytes.EMPTY,
                Bytes.EMPTY);

        subject.execute(StreamMode.RECORDS, config, jsConfig, false);
        assertThat(subject.result()).isNotNull();
    }

    @Test
    void returnsEarlyWhenJumpstartBlockEntryNotFoundInRecentHashes() throws Exception {
        // Jumpstart block 93 is within [first=90, last=100] range but the entry itself is missing.
        final List<WrappedRecordFileBlockHashes> entries = new ArrayList<>();
        for (long i = 90; i <= 100; i++) {
            if (i != 93) {
                entries.add(entry(i));
            }
        }
        final var config = enabledRecordsConfig(createRecentHashesDir(entries));

        subject.execute(StreamMode.RECORDS, config, jumpstartConfig(93, 4, 1), false);
        assertNull(subject.result());
    }

    @Test
    void returnsEarlyWhenPreviousBlockHashIsEmpty() throws Exception {
        final var config = enabledRecordsConfig(createRecentHashesDir(List.of(entry(100), entry(101))));
        final var badConfig = new BlockStreamJumpstartConfig(
                100,
                Bytes.EMPTY,
                4,
                1,
                List.of(Bytes.wrap(new byte[HASH_SIZE])),
                Bytes.wrap(new byte[HASH_SIZE]),
                Bytes.wrap(new byte[HASH_SIZE]));
        subject.execute(StreamMode.RECORDS, config, badConfig, false);
        assertNull(subject.result());
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
        return entryWithHashes(blockNumber, Bytes.wrap(new byte[HASH_SIZE]), Bytes.wrap(new byte[HASH_SIZE]));
    }

    private WrappedRecordFileBlockHashes entryWithHashes(
            long blockNumber, Bytes consensusTimestampHash, Bytes outputItemsTreeRootHash) {
        return WrappedRecordFileBlockHashes.newBuilder()
                .blockNumber(blockNumber)
                .consensusTimestampHash(consensusTimestampHash)
                .outputItemsTreeRootHash(outputItemsTreeRootHash)
                .build();
    }

    private static Bytes fillHash(byte b) {
        final var bytes = new byte[HASH_SIZE];
        java.util.Arrays.fill(bytes, b);
        return Bytes.wrap(bytes);
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

    private static BlockStreamJumpstartConfig defaultJumpstartConfig() {
        return new BlockStreamJumpstartConfig(
                -1,
                Bytes.wrap(new byte[HASH_SIZE]),
                0,
                0,
                List.of(),
                Bytes.wrap(new byte[HASH_SIZE]),
                Bytes.wrap(new byte[HASH_SIZE]));
    }

    private static BlockStreamJumpstartConfig jumpstartConfig(long blockNumber, long leafCount, int numHashes) {
        final List<Bytes> subtreeHashes = new ArrayList<>(numHashes);
        for (int i = 0; i < numHashes; i++) {
            subtreeHashes.add(Bytes.wrap(new byte[HASH_SIZE]));
        }
        return new BlockStreamJumpstartConfig(
                blockNumber,
                Bytes.wrap(new byte[HASH_SIZE]),
                leafCount,
                numHashes,
                subtreeHashes,
                Bytes.wrap(new byte[HASH_SIZE]),
                Bytes.wrap(new byte[HASH_SIZE]));
    }
}
