// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.BlockRecordService.NAME;
import static com.hedera.node.app.records.RecordTestData.BLOCK_NUM;
import static com.hedera.node.app.records.RecordTestData.ENDING_RUNNING_HASH;
import static com.hedera.node.app.records.RecordTestData.SIGNER;
import static com.hedera.node.app.records.RecordTestData.STARTING_RUNNING_HASH_OBJ;
import static com.hedera.node.app.records.RecordTestData.TEST_BLOCKS;
import static com.hedera.node.app.records.RecordTestData.USER_PUBLIC_KEY;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.node.app.records.impl.producers.formats.v6.RecordStreamV6Verifier.validateRecordStreamFiles;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_LABEL;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.quiescence.QuiescedHeartbeat;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.app.records.impl.BlockRecordStreamProducer;
import com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesDiskWriter;
import com.hedera.node.app.records.impl.producers.BlockRecordFormat;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.StreamFileProducerConcurrent;
import com.hedera.node.app.records.impl.producers.StreamFileProducerSingleThreaded;
import com.hedera.node.app.records.impl.producers.formats.BlockRecordWriterFactoryImpl;
import com.hedera.node.app.records.impl.producers.formats.SelfNodeAccountIdManagerImpl;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordFormatV6;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.merkle.VirtualMapUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.platformstate.V0540PlatformStateSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"DataFlowIssue"})
final class BlockRecordManagerTest extends AppTestBase {

    private static final Timestamp CONSENSUS_TIME =
            Timestamp.newBuilder().seconds(1_234_567L).nanos(13579).build();
    /**
     * Make it small enough to trigger roll over code with the number of test blocks we have
     */
    private static final int NUM_BLOCK_HASHES_TO_KEEP = 4;

    private static final Timestamp FIRST_CONS_TIME_OF_LAST_BLOCK = new Timestamp(1682899224, 38693760);
    private static final Instant FORCED_BLOCK_SWITCH_TIME = Instant.ofEpochSecond(1682899224L, 38693760);
    private static final NodeInfoImpl NODE_INFO = new NodeInfoImpl(
            0, AccountID.newBuilder().accountNum(3).build(), 10, List.of(), Bytes.EMPTY, List.of(), false, null);
    /**
     * Temporary in memory file system used for testing
     */
    private FileSystem fs;

    private App app;

    private BlockRecordFormat blockRecordFormat;
    private BlockRecordWriterFactory blockRecordWriterFactory;

    @Mock
    private QuiescenceController quiescenceController;

    @Mock
    private QuiescedHeartbeat quiescedHeartbeat;

    @Mock
    private Platform platform;

    @Mock
    private SelfNodeAccountIdManagerImpl selfNodeAccountIdManager;

    @BeforeEach
    void setUpEach() throws Exception {
        // create in memory temp dir
        fs = Jimfs.newFileSystem(Configuration.unix());
        final var tempDir = fs.getPath("/temp");
        Files.createDirectory(tempDir);

        // This test is for V6 files at this time.
        blockRecordFormat = BlockRecordFormatV6.INSTANCE;

        // Configure the application configuration and state we want to test with
        app = appBuilder()
                .withConfigValue("hedera.recordStream.logDir", tempDir.toString())
                .withConfigValue("hedera.recordStream.sidecarDir", "sidecar")
                .withConfigValue("hedera.recordStream.recordFileVersion", 6)
                .withConfigValue("hedera.recordStream.signatureFileVersion", 6)
                .withConfigValue("hedera.recordStream.sidecarMaxSizeMb", 256)
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                .withConfigValue("blockStream.streamMode", "BOTH")
                .withService(new BlockRecordService())
                .withService(new PlatformStateService())
                .build();

        // Preload the specific state we want to test with
        app.stateMutator(BlockRecordService.NAME)
                .withSingletonState(
                        RUNNING_HASHES_STATE_ID, new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null))
                .withSingletonState(
                        BLOCKS_STATE_ID,
                        BlockInfo.newBuilder()
                                .lastBlockNumber(-1)
                                .firstConsTimeOfLastBlock(EPOCH)
                                .blockHashes(STARTING_RUNNING_HASH_OBJ.hash())
                                .migrationRecordsStreamed(false)
                                .firstConsTimeOfCurrentBlock(EPOCH)
                                .lastUsedConsTime(EPOCH)
                                .lastIntervalProcessTime(EPOCH)
                                .build())
                .commit();
        app.stateMutator(PlatformStateService.NAME)
                .withSingletonState(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID, UNINITIALIZED_PLATFORM_STATE)
                .commit();
        blockRecordWriterFactory =
                new BlockRecordWriterFactoryImpl(app.configProvider(), SIGNER, fs, selfNodeAccountIdManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        fs.close();
    }

    /**
     * Test general record stream production without calling all the block info getter methods as they can change the
     * way the code runs and are tested in other tests. The normal case is they are not called often.
     */
    @ParameterizedTest
    @CsvSource({"GENESIS, false", "NON_GENESIS, false", "GENESIS, true", "NON_GENESIS, true"})
    void testRecordStreamProduction(final String startMode, final boolean concurrent) throws Exception {
        given(selfNodeAccountIdManager.getSelfNodeAccountId()).willReturn(NODE_INFO.accountId());
        // setup initial block info
        final long STARTING_BLOCK;
        if (startMode.equals("GENESIS")) {
            STARTING_BLOCK = 0;
        } else {
            // pretend that previous block was 2 seconds before first test transaction
            STARTING_BLOCK = BLOCK_NUM;
            app.stateMutator(NAME)
                    .withSingletonState(
                            BLOCKS_STATE_ID,
                            BlockInfo.newBuilder()
                                    .lastBlockNumber(STARTING_BLOCK - 1)
                                    .firstConsTimeOfLastBlock(new Timestamp(
                                            TEST_BLOCKS
                                                            .get(0)
                                                            .get(0)
                                                            .transactionRecord()
                                                            .consensusTimestamp()
                                                            .seconds()
                                                    - 2,
                                            0))
                                    .blockHashes(STARTING_RUNNING_HASH_OBJ.hash())
                                    .consTimeOfLastHandledTxn(CONSENSUS_TIME)
                                    .migrationRecordsStreamed(true)
                                    .firstConsTimeOfCurrentBlock(FIRST_CONS_TIME_OF_LAST_BLOCK)
                                    .lastUsedConsTime(EPOCH)
                                    .lastIntervalProcessTime(EPOCH)
                                    .build())
                    .commit();
        }

        final var merkleState = app.workingStateAccessor().getState();
        final var producer = concurrent
                ? new StreamFileProducerConcurrent(
                        blockRecordFormat, blockRecordWriterFactory, ForkJoinPool.commonPool(), app.hapiVersion())
                : new StreamFileProducerSingleThreaded(blockRecordFormat, blockRecordWriterFactory, app.hapiVersion());
        final var wrappedRecordHashesDiskWriter = mock(WrappedRecordFileBlockHashesDiskWriter.class);
        Bytes finalRunningHash;
        try (final var blockRecordManager = new BlockRecordManagerImpl(
                app.configProvider(),
                app.workingStateAccessor().getState(),
                producer,
                quiescenceController,
                quiescedHeartbeat,
                platform,
                wrappedRecordHashesDiskWriter,
                InitTrigger.RESTART)) {
            if (!startMode.equals("GENESIS")) {
                blockRecordManager.switchBlocksAt(FORCED_BLOCK_SWITCH_TIME);
            }
            assertThat(blockRecordManager.blockTimestamp()).isNotNull();
            assertThat(blockRecordManager.blockNo()).isEqualTo(blockRecordManager.lastBlockNo() + 1);
            // write a blocks & record files
            int transactionCount = 0;
            final List<Bytes> endOfBlockHashes = new ArrayList<>();
            for (int i = 0; i < TEST_BLOCKS.size(); i++) {
                final var blockData = TEST_BLOCKS.get(i);
                final var block = STARTING_BLOCK + i;
                for (var record : blockData) {
                    blockRecordManager.startUserTransaction(
                            fromTimestamp(record.transactionRecord().consensusTimestamp()), merkleState);
                    // check start hash if first transaction
                    if (transactionCount == 0) {
                        // check starting hash, we need to be using the correct starting hash for the tests to work
                        assertThat(blockRecordManager.getRunningHash().toHex())
                                .isEqualTo(STARTING_RUNNING_HASH_OBJ.hash().toHex());
                    }
                    blockRecordManager.endUserTransaction(Stream.of(record), merkleState);
                    transactionCount++;
                    // pretend rounds happen every 20 transactions
                    if (transactionCount % 20 == 0) {
                        blockRecordManager.endRound(merkleState);
                    }
                }
                assertThat(block - 1).isEqualTo(blockRecordManager.lastBlockNo());
                // check block hashes
                if (endOfBlockHashes.size() > 1) {
                    assertThat(endOfBlockHashes.get(endOfBlockHashes.size() - 1).toHex())
                            .isEqualTo(blockRecordManager.lastBlockHash().toHex());
                }
                endOfBlockHashes.add(blockRecordManager.getRunningHash());
            }
            // end the last round
            blockRecordManager.endRound(merkleState);
            // collect info for later validation
            finalRunningHash = blockRecordManager.getRunningHash();
            // try with resources will close the blockRecordManager and result in waiting for background threads to
            // finish and close any open files. No collect block record manager info to be validated
        }
        verify(wrappedRecordHashesDiskWriter, atLeastOnce()).appendAsync(notNull());
        // check running hash
        assertThat(ENDING_RUNNING_HASH.toHex()).isEqualTo(finalRunningHash.toHex());
        // check record files
        final var recordStreamConfig =
                app.configProvider().getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        validateRecordStreamFiles(
                fs.getPath(recordStreamConfig.logDir()).resolve("record" + asAccountString(NODE_INFO.accountId())),
                recordStreamConfig,
                USER_PUBLIC_KEY,
                TEST_BLOCKS,
                STARTING_BLOCK);
    }

    @Test
    void testBlockInfoMethods() throws Exception {
        // setup initial block info, pretend that previous block was 2 seconds before first test transaction
        given(selfNodeAccountIdManager.getSelfNodeAccountId()).willReturn(NODE_INFO.accountId());
        app.stateMutator(NAME)
                .withSingletonState(
                        BLOCKS_STATE_ID,
                        BlockInfo.newBuilder()
                                .lastBlockNumber(BLOCK_NUM - 1)
                                .firstConsTimeOfLastBlock(new Timestamp(
                                        TEST_BLOCKS
                                                        .get(0)
                                                        .get(0)
                                                        .transactionRecord()
                                                        .consensusTimestamp()
                                                        .seconds()
                                                - 2,
                                        0))
                                .blockHashes(STARTING_RUNNING_HASH_OBJ.hash())
                                .consTimeOfLastHandledTxn(CONSENSUS_TIME)
                                .migrationRecordsStreamed(true)
                                .firstConsTimeOfCurrentBlock(FIRST_CONS_TIME_OF_LAST_BLOCK)
                                .lastUsedConsTime(EPOCH)
                                .lastIntervalProcessTime(EPOCH)
                                .build())
                .commit();

        final Random random = new Random(82792874);
        final var merkleState = app.workingStateAccessor().getState();
        final var producer =
                new StreamFileProducerSingleThreaded(blockRecordFormat, blockRecordWriterFactory, app.hapiVersion());
        final var wrappedRecordHashesDiskWriter = mock(WrappedRecordFileBlockHashesDiskWriter.class);
        Bytes finalRunningHash;
        try (final var blockRecordManager = new BlockRecordManagerImpl(
                app.configProvider(),
                app.workingStateAccessor().getState(),
                producer,
                quiescenceController,
                quiescedHeartbeat,
                platform,
                wrappedRecordHashesDiskWriter,
                InitTrigger.RESTART)) {
            blockRecordManager.switchBlocksAt(FORCED_BLOCK_SWITCH_TIME);
            // write a blocks & record files
            int transactionCount = 0;
            Bytes runningHash = STARTING_RUNNING_HASH_OBJ.hash();
            Bytes runningHashNMinus1 = null;
            Bytes runningHashNMinus2 = null;
            Bytes runningHashNMinus3;
            final List<Bytes> endOfBlockHashes = new ArrayList<>();
            endOfBlockHashes.add(runningHash);
            Instant lastBlockFirstTransactionTimestamp = null;
            for (int i = 0; i < TEST_BLOCKS.size(); i++) {
                final var blockData = TEST_BLOCKS.get(i);
                final var block = BLOCK_NUM + i;
                // write this blocks transactions
                int j = 0;
                while (j < blockData.size()) {
                    // write batch == simulated user transaction
                    final int batchSize = Math.min(random.nextInt(100) + 1, blockData.size() - j);
                    final var userTransactions = blockData.subList(j, j + batchSize);
                    for (var record : userTransactions) {
                        blockRecordManager.startUserTransaction(
                                fromTimestamp(record.transactionRecord().consensusTimestamp()), merkleState);
                        blockRecordManager.endUserTransaction(Stream.of(record), merkleState);
                        transactionCount++;
                        // collect hashes
                        runningHashNMinus3 = runningHashNMinus2;
                        runningHashNMinus2 = runningHashNMinus1;
                        runningHashNMinus1 = runningHash;
                        runningHash = blockRecordManager.getRunningHash();
                        // check running hash N - 3
                        if (runningHashNMinus3 != null) {
                            // check running hash N - 3
                            assertThat(runningHashNMinus3.toHex())
                                    .isEqualTo(blockRecordManager.prngSeed().toHex());
                        } else {
                            // check empty as well
                            assertThat(blockRecordManager.prngSeed()).isEqualTo(Bytes.EMPTY);
                        }
                    }
                    j += batchSize;
                    // pretend rounds happen every 20 or so transactions
                    if (transactionCount % 20 == 0) {
                        blockRecordManager.endRound(merkleState);
                    }
                }
                // VALIDATE BLOCK INFO METHODS
                // check last block number
                assertThat(block - 1).isEqualTo(blockRecordManager.lastBlockNo());
                // check last block first transaction timestamp
                if (lastBlockFirstTransactionTimestamp != null) {
                    assertThat(lastBlockFirstTransactionTimestamp)
                            .isEqualTo(blockRecordManager.firstConsTimeOfLastBlock());
                }
                lastBlockFirstTransactionTimestamp =
                        fromTimestamp(blockData.get(0).transactionRecord().consensusTimestamp());
                // check block hashes we have in history
                if (endOfBlockHashes.size() > 0) {
                    // trim endOfBlockHashes to NUM_BLOCK_HASHES_TO_KEEP
                    while (endOfBlockHashes.size() > NUM_BLOCK_HASHES_TO_KEEP) {
                        endOfBlockHashes.remove(0);
                    }
                    assertThat(endOfBlockHashes.get(endOfBlockHashes.size() - 1).toHex())
                            .isEqualTo(blockRecordManager.lastBlockHash().toHex());
                    assertThat(endOfBlockHashes.get(endOfBlockHashes.size() - 1).toHex())
                            .isEqualTo(blockRecordManager
                                    .blockHashByBlockNumber(block - 1)
                                    .toHex());
                    final int numBlockHashesToCheck = Math.min(NUM_BLOCK_HASHES_TO_KEEP, endOfBlockHashes.size());
                    for (int k = (numBlockHashesToCheck - 1); k >= 0; k--) {
                        var blockNumToCheck = block - (numBlockHashesToCheck - k);
                        assertThat(endOfBlockHashes.get(k).toHex())
                                .isEqualTo(blockRecordManager
                                        .blockHashByBlockNumber(blockNumToCheck)
                                        .toHex());
                    }
                }
                endOfBlockHashes.add(blockRecordManager.getRunningHash());
            }
            // end the last round
            blockRecordManager.endRound(merkleState);
            // collect info for later validation
            finalRunningHash = blockRecordManager.getRunningHash();
            // try with resources will close the blockRecordManager and result in waiting for background threads to
            // finish and close any open files. No collect block record manager info to be validated
        }
        verify(wrappedRecordHashesDiskWriter, atLeastOnce()).appendAsync(notNull());
        // check running hash
        assertThat(ENDING_RUNNING_HASH.toHex()).isEqualTo(finalRunningHash.toHex());
        // check record files
        final var recordStreamConfig =
                app.configProvider().getConfiguration().getConfigData(BlockRecordStreamConfig.class);
        validateRecordStreamFiles(
                fs.getPath(recordStreamConfig.logDir()).resolve("record" + asAccountString(NODE_INFO.accountId())),
                recordStreamConfig,
                USER_PUBLIC_KEY,
                TEST_BLOCKS,
                BLOCK_NUM);
    }

    @Test
    void isDefaultConsTimeForNullParam() {
        @SuppressWarnings("ConstantValue")
        final var result = BlockRecordManagerImpl.isDefaultConsTimeOfLastHandledTxn(null);
        //noinspection ConstantValue
        Assertions.assertThat(result).isTrue();
    }

    @Test
    void isDefaultConsTimeForNullConsensusTimeOfLastHandledTxn() {
        final var result = BlockRecordManagerImpl.isDefaultConsTimeOfLastHandledTxn(BlockInfo.newBuilder()
                .firstConsTimeOfLastBlock(CONSENSUS_TIME)
                .blockHashes(Bytes.EMPTY)
                .migrationRecordsStreamed(false)
                .firstConsTimeOfCurrentBlock(CONSENSUS_TIME)
                .lastUsedConsTime(EPOCH)
                .lastIntervalProcessTime(EPOCH)
                .build());
        Assertions.assertThat(result).isTrue();
    }

    @Test
    void isDefaultConsTimeForTimestampAfterEpoch() {
        final var timestampAfterEpoch = Timestamp.newBuilder()
                .seconds(EPOCH.seconds())
                .nanos(EPOCH.nanos() + 1)
                .build();
        final var result = BlockRecordManagerImpl.isDefaultConsTimeOfLastHandledTxn(BlockInfo.newBuilder()
                .firstConsTimeOfLastBlock(CONSENSUS_TIME)
                .blockHashes(Bytes.EMPTY)
                .consTimeOfLastHandledTxn(timestampAfterEpoch)
                .migrationRecordsStreamed(false)
                .firstConsTimeOfCurrentBlock(CONSENSUS_TIME)
                .lastUsedConsTime(EPOCH)
                .lastIntervalProcessTime(EPOCH)
                .build());
        Assertions.assertThat(result).isFalse();
    }

    @Test
    void isDefaultConsTimeForTimestampAtEpoch() {
        final var result = BlockRecordManagerImpl.isDefaultConsTimeOfLastHandledTxn(BlockInfo.newBuilder()
                .firstConsTimeOfLastBlock(CONSENSUS_TIME)
                .blockHashes(Bytes.EMPTY)
                .consTimeOfLastHandledTxn(EPOCH)
                .migrationRecordsStreamed(false)
                .firstConsTimeOfCurrentBlock(CONSENSUS_TIME)
                .lastUsedConsTime(EPOCH)
                .lastIntervalProcessTime(EPOCH)
                .build());
        Assertions.assertThat(result).isTrue();
    }

    @Test
    void isDefaultConsTimeForTimestampBeforeEpoch() {
        final var timestampBeforeEpoch = Timestamp.newBuilder()
                .seconds(EPOCH.seconds())
                .nanos(EPOCH.nanos() - 1)
                .build();
        final var result = BlockRecordManagerImpl.isDefaultConsTimeOfLastHandledTxn(BlockInfo.newBuilder()
                .firstConsTimeOfLastBlock(CONSENSUS_TIME)
                .blockHashes(Bytes.EMPTY)
                .consTimeOfLastHandledTxn(timestampBeforeEpoch)
                .migrationRecordsStreamed(false)
                .firstConsTimeOfCurrentBlock(CONSENSUS_TIME)
                .lastUsedConsTime(EPOCH)
                .lastIntervalProcessTime(EPOCH)
                .build());
        Assertions.assertThat(result).isTrue();
    }

    @Test
    void consTimeOfLastHandledTxnIsSet() {
        final var blockInfo = BlockInfo.newBuilder()
                .firstConsTimeOfLastBlock(EPOCH)
                .blockHashes(Bytes.EMPTY)
                .consTimeOfLastHandledTxn(CONSENSUS_TIME)
                .migrationRecordsStreamed(false)
                .firstConsTimeOfCurrentBlock(EPOCH)
                .lastUsedConsTime(EPOCH)
                .lastIntervalProcessTime(EPOCH)
                .build();
        final var state = simpleBlockInfoState(blockInfo);
        final var subject = new BlockRecordManagerImpl(
                app.configProvider(),
                state,
                mock(BlockRecordStreamProducer.class),
                quiescenceController,
                quiescedHeartbeat,
                platform,
                mock(WrappedRecordFileBlockHashesDiskWriter.class),
                InitTrigger.RESTART);

        final var result = subject.consTimeOfLastHandledTxn();
        Assertions.assertThat(result).isEqualTo(fromTimestamp(CONSENSUS_TIME));
        state.release();
    }

    @Test
    void consTimeOfLastHandledTxnIsNotSet() {
        final var blockInfo = BlockInfo.newBuilder()
                .firstConsTimeOfLastBlock(EPOCH)
                .blockHashes(Bytes.EMPTY)
                .migrationRecordsStreamed(false)
                .firstConsTimeOfCurrentBlock(EPOCH)
                .lastUsedConsTime(EPOCH)
                .lastIntervalProcessTime(EPOCH)
                .build();
        final var state = simpleBlockInfoState(blockInfo);
        final var subject = new BlockRecordManagerImpl(
                app.configProvider(),
                state,
                mock(BlockRecordStreamProducer.class),
                quiescenceController,
                quiescedHeartbeat,
                platform,
                mock(WrappedRecordFileBlockHashesDiskWriter.class),
                InitTrigger.RESTART);

        final var result = subject.consTimeOfLastHandledTxn();
        Assertions.assertThat(result).isEqualTo(fromTimestamp(EPOCH));
        state.release();
    }

    private static State simpleBlockInfoState(final BlockInfo blockInfo) {
        final var virtualMap = VirtualMapUtils.createVirtualMap();
        return new VirtualMapStateImpl(virtualMap, new NoOpMetrics()) {
            @NonNull
            @Override
            public ReadableStates getReadableStates(@NonNull final String serviceName) {
                return new MapReadableStates(Map.of(
                        BLOCKS_STATE_ID,
                        new FunctionReadableSingletonState<>(BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, () -> blockInfo),
                        RUNNING_HASHES_STATE_ID,
                        new FunctionReadableSingletonState<>(
                                RUNNING_HASHES_STATE_ID, RUNNING_HASHES_STATE_LABEL, () -> RunningHashes.DEFAULT)));
            }
        };
    }

    private static Instant fromTimestamp(final Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }

    @Nested
    class LiveWrappedRecordHashesTest {

        private App liveApp;

        @BeforeEach
        void enableLiveWrappedHashes() {
            given(selfNodeAccountIdManager.getSelfNodeAccountId()).willReturn(NODE_INFO.accountId());
            liveApp = appBuilder()
                    .withConfigValue(
                            "hedera.recordStream.logDir", fs.getPath("/temp").toString())
                    .withConfigValue("hedera.recordStream.sidecarDir", "sidecar")
                    .withConfigValue("hedera.recordStream.recordFileVersion", 6)
                    .withConfigValue("hedera.recordStream.signatureFileVersion", 6)
                    .withConfigValue("hedera.recordStream.sidecarMaxSizeMb", 256)
                    .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                    .withConfigValue("hedera.recordStream.liveWritePrevWrappedRecordHashes", true)
                    .withConfigValue("blockStream.streamMode", "BOTH")
                    .withService(new BlockRecordService())
                    .withService(new PlatformStateService())
                    .build();

            liveApp.stateMutator(BlockRecordService.NAME)
                    .withSingletonState(
                            RUNNING_HASHES_STATE_ID,
                            new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null))
                    .withSingletonState(
                            BLOCKS_STATE_ID,
                            BlockInfo.newBuilder()
                                    .lastBlockNumber(-1)
                                    .firstConsTimeOfLastBlock(EPOCH)
                                    .blockHashes(STARTING_RUNNING_HASH_OBJ.hash())
                                    .migrationRecordsStreamed(false)
                                    .firstConsTimeOfCurrentBlock(EPOCH)
                                    .lastUsedConsTime(EPOCH)
                                    .lastIntervalProcessTime(EPOCH)
                                    .build())
                    .commit();
            liveApp.stateMutator(PlatformStateService.NAME)
                    .withSingletonState(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID, UNINITIALIZED_PLATFORM_STATE)
                    .commit();
        }

        private BlockRecordManagerImpl createGenesisManager(App theApp, State state) {
            return createManager(
                    theApp, state, mock(WrappedRecordFileBlockHashesDiskWriter.class), InitTrigger.GENESIS);
        }

        private BlockRecordManagerImpl createManager(
                App theApp, State state, WrappedRecordFileBlockHashesDiskWriter diskWriter, InitTrigger trigger) {
            final var writerFactory =
                    new BlockRecordWriterFactoryImpl(theApp.configProvider(), SIGNER, fs, selfNodeAccountIdManager);
            final var producer =
                    new StreamFileProducerSingleThreaded(blockRecordFormat, writerFactory, theApp.hapiVersion());
            return new BlockRecordManagerImpl(
                    theApp.configProvider(),
                    state,
                    producer,
                    quiescenceController,
                    quiescedHeartbeat,
                    platform,
                    diskWriter,
                    trigger);
        }

        private void processBlock(BlockRecordManagerImpl manager, State state, int blockIndex) {
            for (var record : TEST_BLOCKS.get(blockIndex)) {
                manager.startUserTransaction(
                        fromTimestamp(record.transactionRecord().consensusTimestamp()), state);
                manager.endUserTransaction(Stream.of(record), state);
            }
        }

        private BlockInfo readBlockInfo(State state) {
            return state.getWritableStates(BlockRecordService.NAME)
                    .<BlockInfo>getSingleton(BLOCKS_STATE_ID)
                    .get();
        }

        @Test
        void wrappedRootHashIsPopulatedAfterFirstBlockBoundary() {
            final var state = liveApp.workingStateAccessor().getState();
            try (final var manager = createGenesisManager(liveApp, state)) {
                // Process block 0, then block 1 (first tx of block 1 triggers boundary)
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);

                final var blockInfo = readBlockInfo(state);
                assertThat(blockInfo.previousWrappedRecordBlockRootHash()).isNotEqualTo(Bytes.EMPTY);
                assertThat(blockInfo.previousWrappedRecordBlockRootHash().length())
                        .isEqualTo(HASH_SIZE);
            }
        }

        @Test
        void intermediateHashStateIsNonEmptyAfterBlockBoundary() {
            final var state = liveApp.workingStateAccessor().getState();
            try (final var manager = createGenesisManager(liveApp, state)) {
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);

                final var blockInfo = readBlockInfo(state);
                assertThat(blockInfo.wrappedIntermediatePreviousBlockRootHashes())
                        .isNotEmpty();
            }
        }

        @Test
        void leafCountIncrementsWithEachBlockBoundary() {
            final var state = liveApp.workingStateAccessor().getState();
            try (final var manager = createGenesisManager(liveApp, state)) {
                // Process blocks 0, 1, 2 — two block boundaries fire (block 0 and block 1 complete)
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);
                processBlock(manager, state, 2);

                final var blockInfo = readBlockInfo(state);
                assertThat(blockInfo.wrappedIntermediateBlockRootsLeafCount()).isEqualTo(2);
            }
        }

        @Test
        void wrappedRootHashDiffersBetweenConsecutiveBlocks() {
            final var state = liveApp.workingStateAccessor().getState();
            try (final var manager = createGenesisManager(liveApp, state)) {
                // Complete block 0 (boundary fires at start of block 1)
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);
                final var hashAfterBlock0 = readBlockInfo(state).previousWrappedRecordBlockRootHash();

                // Complete block 1 (boundary fires at start of block 2)
                processBlock(manager, state, 2);
                final var hashAfterBlock1 = readBlockInfo(state).previousWrappedRecordBlockRootHash();

                assertThat(hashAfterBlock0).isNotEqualTo(Bytes.EMPTY);
                assertThat(hashAfterBlock1).isNotEqualTo(Bytes.EMPTY);
                assertThat(hashAfterBlock0).isNotEqualTo(hashAfterBlock1);
            }
        }

        @Test
        void restartContinuityPreservesWrappedHashState() {
            // Process blocks 0, 1 through genesis manager — one boundary fires (block 0 completes), leaf count = 1
            final var state = liveApp.workingStateAccessor().getState();
            final Bytes rootHashFromGenesis;
            final long leafCountFromGenesis;
            try (final var manager = createGenesisManager(liveApp, state)) {
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);

                final var blockInfo = readBlockInfo(state);
                rootHashFromGenesis = blockInfo.previousWrappedRecordBlockRootHash();
                leafCountFromGenesis = blockInfo.wrappedIntermediateBlockRootsLeafCount();
                assertThat(leafCountFromGenesis).isEqualTo(1);
                assertThat(rootHashFromGenesis).isNotEqualTo(Bytes.EMPTY);
            }

            // Simulate restart: new manager with RESTART trigger reads the persisted state.
            // The first boundary after restart has currentBlockStartRunningHash==null so it
            // can't compute wrapped hashes for the in-progress block — but it should preserve
            // the restored hasher state. The second boundary has tracking set up and computes
            // a new wrapped hash leaf using the restored hasher.
            try (final var restartManager = createManager(
                    liveApp, state, mock(WrappedRecordFileBlockHashesDiskWriter.class), InitTrigger.RESTART)) {
                processBlock(restartManager, state, 1);
                processBlock(restartManager, state, 2);

                // After the first boundary on restart (currentBlockStartRunningHash is null),
                // the BlockInfo should preserve the restored hasher state — not zero it out.
                // The leaf count must still equal the genesis value (not reset to 0).
                final var blockInfoAfterFirstBoundary = readBlockInfo(state);
                assertThat(blockInfoAfterFirstBoundary.wrappedIntermediateBlockRootsLeafCount())
                        .as("First restart boundary should preserve restored leaf count")
                        .isEqualTo(leafCountFromGenesis);
                assertThat(blockInfoAfterFirstBoundary.previousWrappedRecordBlockRootHash())
                        .as("First restart boundary should preserve a non-empty root hash")
                        .isNotEqualTo(Bytes.EMPTY);

                processBlock(restartManager, state, 3);

                final var blockInfo = readBlockInfo(state);
                // The restored leaf count (1) should be carried forward and incremented.
                // After the first real computation boundary, leaf count = restored (1) + 1 = 2.
                assertThat(blockInfo.wrappedIntermediateBlockRootsLeafCount()).isEqualTo(leafCountFromGenesis + 1);
                // The root hash should differ from the genesis value (a new block was incorporated)
                assertThat(blockInfo.previousWrappedRecordBlockRootHash()).isNotEqualTo(rootHashFromGenesis);
                assertThat(blockInfo.previousWrappedRecordBlockRootHash().length())
                        .isEqualTo(HASH_SIZE);
            }
        }

        @Test
        void liveModeDelegatesPrecomputedToDiskWriter() {
            final var state = liveApp.workingStateAccessor().getState();
            final var diskWriter = mock(WrappedRecordFileBlockHashesDiskWriter.class);
            try (final var manager = createManager(liveApp, state, diskWriter, InitTrigger.GENESIS)) {
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);
            }
            // Since both flags are on, appendPrecomputed should have been called (not appendAsync)
            verify(diskWriter).appendPrecomputed(notNull());
            verify(diskWriter, org.mockito.Mockito.never()).appendAsync(notNull());
        }

        @Test
        void liveModeWithoutDiskWriteDoesNotCallDiskWriter() throws Exception {
            // Build app with liveWritePrevWrappedRecordHashes=true but writeWrappedRecordFileBlockHashesToDisk=false
            final var liveOnlyApp = appBuilder()
                    .withConfigValue(
                            "hedera.recordStream.logDir", fs.getPath("/temp").toString())
                    .withConfigValue("hedera.recordStream.sidecarDir", "sidecar")
                    .withConfigValue("hedera.recordStream.recordFileVersion", 6)
                    .withConfigValue("hedera.recordStream.signatureFileVersion", 6)
                    .withConfigValue("hedera.recordStream.sidecarMaxSizeMb", 256)
                    .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", false)
                    .withConfigValue("hedera.recordStream.liveWritePrevWrappedRecordHashes", true)
                    .withConfigValue("blockStream.streamMode", "BOTH")
                    .withService(new BlockRecordService())
                    .withService(new PlatformStateService())
                    .build();
            liveOnlyApp
                    .stateMutator(BlockRecordService.NAME)
                    .withSingletonState(
                            RUNNING_HASHES_STATE_ID,
                            new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null))
                    .withSingletonState(
                            BLOCKS_STATE_ID,
                            BlockInfo.newBuilder()
                                    .lastBlockNumber(-1)
                                    .firstConsTimeOfLastBlock(EPOCH)
                                    .blockHashes(STARTING_RUNNING_HASH_OBJ.hash())
                                    .migrationRecordsStreamed(false)
                                    .firstConsTimeOfCurrentBlock(EPOCH)
                                    .lastUsedConsTime(EPOCH)
                                    .lastIntervalProcessTime(EPOCH)
                                    .build())
                    .commit();
            liveOnlyApp
                    .stateMutator(PlatformStateService.NAME)
                    .withSingletonState(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID, UNINITIALIZED_PLATFORM_STATE)
                    .commit();

            final var state = liveOnlyApp.workingStateAccessor().getState();
            final var diskWriter = mock(WrappedRecordFileBlockHashesDiskWriter.class);
            try (final var manager = createManager(liveOnlyApp, state, diskWriter, InitTrigger.GENESIS)) {
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);

                // BlockInfo should still have wrapped hash data (live mode is on)
                final var blockInfo = readBlockInfo(state);
                assertThat(blockInfo.previousWrappedRecordBlockRootHash()).isNotEqualTo(Bytes.EMPTY);
            }
            // But disk writer should NOT have been called at all
            verify(diskWriter, org.mockito.Mockito.never()).appendPrecomputed(org.mockito.ArgumentMatchers.any());
            verify(diskWriter, org.mockito.Mockito.never()).appendAsync(org.mockito.ArgumentMatchers.any());
        }

        @Test
        void freezeBlockUpdatesWrappedHashState() {
            final var state = liveApp.workingStateAccessor().getState();
            try (final var manager = createGenesisManager(liveApp, state)) {
                // Process blocks 0 and 1. One boundary fires (block 0 completes at start of block 1).
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);

                final var blockInfoBefore = readBlockInfo(state);
                assertThat(blockInfoBefore.wrappedIntermediateBlockRootsLeafCount())
                        .isEqualTo(1);
                final var rootHashBefore = blockInfoBefore.previousWrappedRecordBlockRootHash();

                // Call freeze with state — computes wrapped hash for the in-progress block 1
                // and persists the updated BlockInfo to state (leaf count → 2).
                manager.writeFreezeBlockWrappedRecordFileBlockHashes(state);

                final var blockInfoAfterFreeze = readBlockInfo(state);
                // Freeze persisted the updated wrapped hash state
                assertThat(blockInfoAfterFreeze.wrappedIntermediateBlockRootsLeafCount())
                        .isEqualTo(2);
                assertThat(blockInfoAfterFreeze.previousWrappedRecordBlockRootHash())
                        .isNotEqualTo(rootHashBefore);

                // Now process block 2; boundary fires completing block 1 (items are re-computed
                // since freeze didn't clear them — this is expected for orderly shutdown).
                // The boundary uses the hasher that already has the freeze leaf, so leaf count → 3.
                processBlock(manager, state, 2);

                final var blockInfoAfter = readBlockInfo(state);
                // The leaf count increased: 1 (pre-freeze) + 1 (freeze) + 1 (boundary) = 3
                assertThat(blockInfoAfter.wrappedIntermediateBlockRootsLeafCount())
                        .isEqualTo(3);
                // Root hash should have changed from the pre-freeze state
                assertThat(blockInfoAfter.previousWrappedRecordBlockRootHash()).isNotEqualTo(rootHashBefore);
            }
        }

        @Test
        void freezeWithNullStateDoesNotPersistBlockInfo() {
            final var state = liveApp.workingStateAccessor().getState();
            try (final var manager = createGenesisManager(liveApp, state)) {
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);

                final var blockInfoBefore = readBlockInfo(state);
                assertThat(blockInfoBefore.wrappedIntermediateBlockRootsLeafCount())
                        .isEqualTo(1);

                // Call freeze with null state (as Hedera.FREEZE_COMPLETE does) — the internal
                // hasher is updated but BlockInfo in state should NOT change.
                manager.writeFreezeBlockWrappedRecordFileBlockHashes(null);

                final var blockInfoAfter = readBlockInfo(state);
                assertThat(blockInfoAfter.wrappedIntermediateBlockRootsLeafCount())
                        .as("Leaf count should not change when state is null")
                        .isEqualTo(1);
                assertThat(blockInfoAfter.previousWrappedRecordBlockRootHash())
                        .as("Root hash should not change when state is null")
                        .isEqualTo(blockInfoBefore.previousWrappedRecordBlockRootHash());
            }
        }

        @Test
        void wrappedHashFieldsRemainEmptyWhenFeatureDisabled() throws Exception {
            // Build a separate app with the feature explicitly disabled
            final var disabledApp = appBuilder()
                    .withConfigValue(
                            "hedera.recordStream.logDir", fs.getPath("/temp").toString())
                    .withConfigValue("hedera.recordStream.sidecarDir", "sidecar")
                    .withConfigValue("hedera.recordStream.recordFileVersion", 6)
                    .withConfigValue("hedera.recordStream.signatureFileVersion", 6)
                    .withConfigValue("hedera.recordStream.sidecarMaxSizeMb", 256)
                    .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                    .withConfigValue("hedera.recordStream.liveWritePrevWrappedRecordHashes", false)
                    .withConfigValue("blockStream.streamMode", "BOTH")
                    .withService(new BlockRecordService())
                    .withService(new PlatformStateService())
                    .build();
            disabledApp
                    .stateMutator(BlockRecordService.NAME)
                    .withSingletonState(
                            RUNNING_HASHES_STATE_ID,
                            new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null))
                    .withSingletonState(
                            BLOCKS_STATE_ID,
                            BlockInfo.newBuilder()
                                    .lastBlockNumber(-1)
                                    .firstConsTimeOfLastBlock(EPOCH)
                                    .blockHashes(STARTING_RUNNING_HASH_OBJ.hash())
                                    .migrationRecordsStreamed(false)
                                    .firstConsTimeOfCurrentBlock(EPOCH)
                                    .lastUsedConsTime(EPOCH)
                                    .lastIntervalProcessTime(EPOCH)
                                    .build())
                    .commit();
            disabledApp
                    .stateMutator(PlatformStateService.NAME)
                    .withSingletonState(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID, UNINITIALIZED_PLATFORM_STATE)
                    .commit();

            final var state = disabledApp.workingStateAccessor().getState();
            try (final var manager = createGenesisManager(disabledApp, state)) {
                processBlock(manager, state, 0);
                processBlock(manager, state, 1);

                final var blockInfo = readBlockInfo(state);
                assertThat(blockInfo.previousWrappedRecordBlockRootHash()).isEqualTo(Bytes.EMPTY);
                assertThat(blockInfo.wrappedIntermediatePreviousBlockRootHashes())
                        .isEmpty();
                assertThat(blockInfo.wrappedIntermediateBlockRootsLeafCount()).isEqualTo(0);
            }
        }
    }

    @Nested
    class ComputeWrappedRecordBlockRootHashTest {

        private static final Bytes EMPTY_INT_NODE = BlockImplUtils.hashInternalNode(HASH_OF_ZERO, HASH_OF_ZERO);

        @Test
        void producesHashOfCorrectSize() {
            final var result = BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(
                    HASH_OF_ZERO, HASH_OF_ZERO, entryWithZeroHashes());

            assertThat(result.length()).isEqualTo(HASH_SIZE);
        }

        @Test
        void isDeterministic() {
            final var prevBlockHash = randomHash();
            final var allPrevRootHash = randomHash();
            final var entry = entryWith(randomHash(), randomHash());

            final var first =
                    BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(prevBlockHash, allPrevRootHash, entry);
            final var second =
                    BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(prevBlockHash, allPrevRootHash, entry);

            assertThat(first).isEqualTo(second);
        }

        @Test
        void variesWithPreviousBlockRootHash() {
            final var allPrevRootHash = randomHash();
            final var entry = entryWith(randomHash(), randomHash());

            final var resultA =
                    BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(randomHash(), allPrevRootHash, entry);
            final var resultB =
                    BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(randomHash(), allPrevRootHash, entry);

            assertThat(resultA).isNotEqualTo(resultB);
        }

        @Test
        void variesWithAllPrevBlocksRootHash() {
            final var prevBlockHash = randomHash();
            final var entry = entryWith(randomHash(), randomHash());

            final var resultA =
                    BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(prevBlockHash, randomHash(), entry);
            final var resultB =
                    BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(prevBlockHash, randomHash(), entry);

            assertThat(resultA).isNotEqualTo(resultB);
        }

        @Test
        void variesWithOutputItemsTreeRootHash() {
            final var prevBlockHash = randomHash();
            final var allPrevRootHash = randomHash();
            final var consensusHash = randomHash();

            final var resultA = BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(
                    prevBlockHash, allPrevRootHash, entryWith(randomHash(), consensusHash));
            final var resultB = BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(
                    prevBlockHash, allPrevRootHash, entryWith(randomHash(), consensusHash));

            assertThat(resultA).isNotEqualTo(resultB);
        }

        @Test
        void variesWithConsensusTimestampHash() {
            final var prevBlockHash = randomHash();
            final var allPrevRootHash = randomHash();
            final var outputHash = randomHash();

            final var resultA = BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(
                    prevBlockHash, allPrevRootHash, entryWith(outputHash, randomHash()));
            final var resultB = BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(
                    prevBlockHash, allPrevRootHash, entryWith(outputHash, randomHash()));

            assertThat(resultA).isNotEqualTo(resultB);
        }

        @Test
        void matchesManualComputation() {
            final var prevBlockHash = randomHash();
            final var allPrevRootHash = randomHash();
            final var outputHash = randomHash();
            final var consensusHash = randomHash();
            final var entry = entryWith(outputHash, consensusHash);

            // Manually compute the expected tree structure
            final Bytes depth5Node1 = BlockImplUtils.hashInternalNode(prevBlockHash, allPrevRootHash);
            final Bytes depth5Node2 = EMPTY_INT_NODE;
            final Bytes depth5Node3 = BlockImplUtils.hashInternalNode(HASH_OF_ZERO, outputHash);
            final Bytes depth5Node4 = EMPTY_INT_NODE;

            final Bytes depth4Node1 = BlockImplUtils.hashInternalNode(depth5Node1, depth5Node2);
            final Bytes depth4Node2 = BlockImplUtils.hashInternalNode(depth5Node3, depth5Node4);

            final Bytes depth3Node1 = BlockImplUtils.hashInternalNode(depth4Node1, depth4Node2);

            final Bytes depth2Node1 = consensusHash;
            final Bytes depth2Node2 = BlockImplUtils.hashInternalNodeSingleChild(depth3Node1);

            final Bytes expected = BlockImplUtils.hashInternalNode(depth2Node1, depth2Node2);

            final var actual =
                    BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(prevBlockHash, allPrevRootHash, entry);

            assertThat(actual).isEqualTo(expected);
        }

        @Test
        void chainedComputationsProduceDifferentResults() {
            final var allPrevRootHash = randomHash();
            final var entry = entryWith(randomHash(), randomHash());

            final var block1Hash =
                    BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(HASH_OF_ZERO, allPrevRootHash, entry);
            final var block2Hash =
                    BlockRecordManagerImpl.computeWrappedRecordBlockRootHash(block1Hash, allPrevRootHash, entry);

            assertThat(block1Hash).isNotEqualTo(block2Hash);
        }

        private WrappedRecordFileBlockHashes entryWithZeroHashes() {
            return entryWith(Bytes.wrap(new byte[HASH_SIZE]), Bytes.wrap(new byte[HASH_SIZE]));
        }

        private WrappedRecordFileBlockHashes entryWith(Bytes outputTreeRootHash, Bytes consensusTimestampHash) {
            return WrappedRecordFileBlockHashes.newBuilder()
                    .outputItemsTreeRootHash(outputTreeRootHash)
                    .consensusTimestampHash(consensusTimestampHash)
                    .build();
        }

        private Bytes randomHash() {
            final var bytes = new byte[HASH_SIZE];
            new SecureRandom().nextBytes(bytes);
            return Bytes.wrap(bytes);
        }
    }
}
