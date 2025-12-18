// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.BlockRecordService.NAME;
import static com.hedera.node.app.records.RecordTestData.SIGNER;
import static com.hedera.node.app.records.RecordTestData.STARTING_RUNNING_HASH_OBJ;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static com.swirlds.platform.state.service.PlatformStateService.PLATFORM_STATE_SERVICE;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.google.common.jimfs.Jimfs;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.quiescence.QuiescedHeartbeat;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.records.RecordTestData;
import com.hedera.node.app.records.impl.producers.BlockRecordFormat;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.StreamFileProducerConcurrent;
import com.hedera.node.app.records.impl.producers.StreamFileProducerSingleThreaded;
import com.hedera.node.app.records.impl.producers.formats.BlockRecordWriterFactoryImpl;
import com.hedera.node.app.records.impl.producers.formats.SelfNodeAccountIdManagerImpl;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordFormatV6;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;
import org.hiero.consensus.model.hashgraph.Round;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BlockRecordManagerImpl} in round-boundary mode
 * (when {@code roundBoundaryClosingEnabled = true}).
 */
@ExtendWith(MockitoExtension.class)
class BlockRecordManagerRoundBoundaryTest {

    private static final Instant GENESIS_TIME = Instant.ofEpochSecond(1_234_567L, 890);
    private static final Instant BLOCK_START_TIME = Instant.ofEpochSecond(1_234_568L, 0);
    private static final long BLOCK_PERIOD_SECONDS = 2L;

    /**
     * Config with round-boundary mode enabled (default)
     */
    private static final Configuration ROUND_BOUNDARY_CONFIG = HederaTestConfigBuilder.create()
            .withValue("hedera.recordStream.roundBoundaryClosingEnabled", true)
            .withValue("hedera.recordStream.logPeriod", BLOCK_PERIOD_SECONDS)
            .withValue("blockStream.streamMode", "RECORDS")
            .getOrCreateConfig();

    @Mock
    private State state;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private ReadableSingletonState<BlockInfo> blockInfoState;

    @Mock
    private ReadableSingletonState<RunningHashes> runningHashesState;

    @Mock
    private WritableSingletonState<BlockInfo> writableBlockInfoState;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private WritableStates writableStates;

    @Mock
    private BlockRecordStreamProducer streamFileProducer;

    @Mock
    private QuiescenceController quiescenceController;

    @Mock
    private QuiescedHeartbeat quiescedHeartbeat;

    @Mock
    private Platform platform;

    @Mock
    private Round round;

    private BlockRecordManagerImpl subject;

    @Nested
    @DisplayName("willOpenNewBlock tests")
    class WillOpenNewBlockTests {

        @Test
        @DisplayName("willOpenNewBlock always returns false in round-boundary mode")
        void willOpenNewBlockAlwaysReturnsFalse() {
            setupSubject(Instant.EPOCH);
            assertFalse(subject.willOpenNewBlock(GENESIS_TIME, state));
        }

        @Test
        @DisplayName("willOpenNewBlock returns false even at period boundary")
        void willOpenNewBlockReturnsFalseAtPeriodBoundary() {
            setupSubject(BLOCK_START_TIME);
            // Even after block period has elapsed, willOpenNewBlock returns false
            assertFalse(subject.willOpenNewBlock(BLOCK_START_TIME.plusSeconds(BLOCK_PERIOD_SECONDS + 1), state));
        }
    }

    @Nested
    @DisplayName("startUserTransaction tests")
    class StartUserTransactionTests {

        @Test
        @DisplayName("startUserTransaction returns false in round-boundary mode")
        void startUserTransactionReturnsFalse() {
            setupSubject(Instant.EPOCH);
            assertFalse(subject.startUserTransaction(GENESIS_TIME, state));
        }

        @Test
        @DisplayName("startUserTransaction returns false even at period boundary")
        void startUserTransactionReturnsFalseAtPeriodBoundary() {
            setupSubject(BLOCK_START_TIME);
            assertFalse(subject.startUserTransaction(BLOCK_START_TIME.plusSeconds(BLOCK_PERIOD_SECONDS + 1), state));
        }
    }

    @Nested
    @DisplayName("startRound tests")
    class StartRoundTests {

        @BeforeEach
        void setupWritableStates() {
            given(state.getWritableStates(BlockRecordService.NAME)).willReturn(writableStates);
            given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(writableBlockInfoState);
        }

        @Test
        @DisplayName("startRound at genesis opens block 0")
        void startRoundAtGenesisOpensBlock() {
            setupSubject(Instant.EPOCH);
            given(round.getConsensusTimestamp()).willReturn(GENESIS_TIME);

            subject.startRound(round, state);

            // Verify block was opened
            verify(streamFileProducer).switchBlocks(eq(-1L), eq(0L), eq(GENESIS_TIME));
            verify(quiescenceController).startingBlock(0L);
        }

        @Test
        @DisplayName("startRound opens new block after previous block was closed")
        void startRoundOpensBlockAfterClose() {
            // Setup with a non-genesis block that was closed (recordFileOpen = false)
            setupSubjectWithClosedBlock(BLOCK_START_TIME, 1L);
            given(round.getConsensusTimestamp()).willReturn(BLOCK_START_TIME.plusSeconds(3));
            given(streamFileProducer.getRunningHash()).willReturn(Bytes.EMPTY);

            subject.startRound(round, state);

            // Verify new block was opened
            verify(streamFileProducer).switchBlocks(anyLong(), anyLong(), any(Instant.class));
            verify(quiescenceController).switchTracker(anyLong());
        }
    }

    /**
     * Sets up the subject with a block at the given start time (genesis case, no block currently open).
     */
    private void setupSubject(@NonNull final Instant firstConsTimeOfCurrentBlock) {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(ROUND_BOUNDARY_CONFIG, 1));
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get())
                .willReturn(BlockInfo.newBuilder()
                        .lastBlockNumber(-1)
                        .firstConsTimeOfCurrentBlock(asTimestamp(firstConsTimeOfCurrentBlock))
                        .build());
        given(readableStates.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID))
                .willReturn(runningHashesState);
        given(runningHashesState.get()).willReturn(RunningHashes.DEFAULT);

        subject = new BlockRecordManagerImpl(
                configProvider, state, streamFileProducer, quiescenceController, quiescedHeartbeat, platform);
    }

    /**
     * Sets up the subject with a closed block (recordFileOpen = false after a block was closed).
     */
    private void setupSubjectWithClosedBlock(@NonNull final Instant blockStartTime, long lastBlockNumber) {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(ROUND_BOUNDARY_CONFIG, 1));
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoState);
        given(blockInfoState.get())
                .willReturn(BlockInfo.newBuilder()
                        .lastBlockNumber(lastBlockNumber)
                        .firstConsTimeOfCurrentBlock(asTimestamp(blockStartTime))
                        .build());
        given(readableStates.<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID))
                .willReturn(runningHashesState);
        given(runningHashesState.get()).willReturn(RunningHashes.DEFAULT);

        subject = new BlockRecordManagerImpl(
                configProvider, state, streamFileProducer, quiescenceController, quiescedHeartbeat, platform);
        // recordFileOpen stays false since we don't call startRound
    }

    /**
     * Integration tests for record stream production in round-boundary mode.
     * These tests use mocked producers and verify the full round-based block opening/closing flow.
     */
    @Nested
    @DisplayName("Record stream production integration tests")
    class RecordStreamProductionTests extends AppTestBase {

        private static final Timestamp CONSENSUS_TIME =
                Timestamp.newBuilder().seconds(1_234_567L).nanos(13579).build();
        private static final Timestamp FIRST_CONS_TIME_OF_LAST_BLOCK = new Timestamp(1682899224, 38693760);
        private static final NodeInfoImpl NODE_INFO = new NodeInfoImpl(
                0, AccountID.newBuilder().accountNum(3).build(), 10, List.of(), Bytes.EMPTY, List.of(), false, null);

        private App app;
        private FileSystem fs;
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
        void setUpIntegration() throws Exception {
            // create in memory temp dir
            fs = Jimfs.newFileSystem(com.google.common.jimfs.Configuration.unix());
            final var tempDir = fs.getPath("/temp");
            Files.createDirectory(tempDir);

            // This test is for V6 files at this time.
            blockRecordFormat = BlockRecordFormatV6.INSTANCE;

            // Configure the application with round-boundary mode enabled
            app = appBuilder()
                    .withConfigValue("hedera.recordStream.logDir", tempDir.toString())
                    .withConfigValue("hedera.recordStream.sidecarDir", "sidecar")
                    .withConfigValue("hedera.recordStream.recordFileVersion", 6)
                    .withConfigValue("hedera.recordStream.signatureFileVersion", 6)
                    .withConfigValue("hedera.recordStream.sidecarMaxSizeMb", 256)
                    .withConfigValue("hedera.recordStream.roundBoundaryClosingEnabled", true)
                    .withConfigValue("hedera.recordStream.logPeriod", 2)
                    .withConfigValue("blockStream.streamMode", "BOTH")
                    .withService(new BlockRecordService())
                    .withService(PLATFORM_STATE_SERVICE)
                    .build();

            // Preload the specific state we want to test with
            app.stateMutator(BlockRecordService.NAME)
                    .withSingletonState(
                            RUNNING_HASHES_STATE_ID,
                            new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null))
                    .withSingletonState(
                            BLOCKS_STATE_ID,
                            new BlockInfo(
                                    -1, EPOCH, STARTING_RUNNING_HASH_OBJ.hash(), null, false, EPOCH, EPOCH, EPOCH))
                    .commit();
            app.stateMutator(PlatformStateService.NAME)
                    .withSingletonState(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID, UNINITIALIZED_PLATFORM_STATE)
                    .commit();

            blockRecordWriterFactory =
                    new BlockRecordWriterFactoryImpl(app.configProvider(), SIGNER, fs, selfNodeAccountIdManager);
        }

        @AfterEach
        void tearDown() throws Exception {
            if (fs != null) {
                fs.close();
            }
        }

        /**
         * Test record stream production using startRound/endRound for block management.
         * This simulates the round-boundary mode where blocks are opened at round start
         * and closed at round end based on elapsed time.
         */
        @Test
        @DisplayName("Record stream production with round-boundary block management")
        void testRecordStreamProductionWithRoundBoundaries() throws Exception {
            final var merkleState = app.workingStateAccessor().getState();
            final var producer = mock(BlockRecordStreamProducer.class);
            // Stub getRunningHash() to return empty bytes (used by endRound)
            given(producer.getRunningHash()).willReturn(Bytes.EMPTY);

            final var quiescenceCtrl = mock(QuiescenceController.class);
            final var heartbeat = mock(QuiescedHeartbeat.class);
            final var plat = mock(Platform.class);

            try (final var blockRecordManager = new BlockRecordManagerImpl(
                    app.configProvider(),
                    app.workingStateAccessor().getState(),
                    producer,
                    quiescenceCtrl,
                    heartbeat,
                    plat)) {

                // Verify initial state
                assertThat(blockRecordManager.blockTimestamp()).isNotNull();

                final var startTime = Instant.ofEpochSecond(1_000_000);

                // Start round 1 (opens genesis block)
                blockRecordManager.startRound(mockRound(1, startTime), merkleState);
                assertThat(blockRecordManager.blockNo()).isEqualTo(0L);

                // Verify switchBlocks was called to open the initial block
                verify(producer).switchBlocks(eq(-1L), eq(0L), any(Instant.class));

                // In round-boundary mode, startUserTransaction returns false (no-op)
                assertFalse(blockRecordManager.startUserTransaction(startTime, merkleState));

                // End round 1 - block should close because it's round 1
                boolean closed = blockRecordManager.endRound(merkleState, mockRound(1, startTime.plusMillis(500)));
                assertTrue(closed, "Block should close at round 1");
                verify(producer).finishCurrentBlock();

                // Start round 2 (opens new block)
                blockRecordManager.startRound(mockRound(2, startTime.plusSeconds(1)), merkleState);
                assertThat(blockRecordManager.blockNo()).isEqualTo(1L);
                verify(producer).switchBlocks(eq(0L), eq(1L), any(Instant.class));

                // End round 2 - block should NOT close (only ~0.5 seconds elapsed from block start, need 2)
                closed = blockRecordManager.endRound(merkleState, mockRound(2, startTime.plusMillis(1500)));
                assertFalse(closed, "Block should not close when period not elapsed");

                // End round 3 - block SHOULD close (>= 2 seconds elapsed from block start at startTime+1s)
                closed = blockRecordManager.endRound(merkleState, mockRound(3, startTime.plusSeconds(3)));
                assertTrue(closed, "Block should close when period elapsed");

                // Start round 4 (opens new block after close)
                blockRecordManager.startRound(mockRound(4, startTime.plusSeconds(4)), merkleState);
                assertThat(blockRecordManager.blockNo()).isEqualTo(2L);
            }
        }

        /**
         * Test that blocks are properly closed when the block period (2 seconds) elapses.
         */
        @Test
        @DisplayName("Blocks close when block period elapses")
        void testBlockClosesOnPeriodElapsed() throws Exception {
            final var merkleState = app.workingStateAccessor().getState();
            final var producer = mock(BlockRecordStreamProducer.class);
            // Stub getRunningHash() to return empty bytes (used by endRound)
            given(producer.getRunningHash()).willReturn(Bytes.EMPTY);

            final var quiescenceCtrl = mock(QuiescenceController.class);
            final var heartbeat = mock(QuiescedHeartbeat.class);
            final var plat = mock(Platform.class);

            try (final var blockRecordManager = new BlockRecordManagerImpl(
                    app.configProvider(),
                    app.workingStateAccessor().getState(),
                    producer,
                    quiescenceCtrl,
                    heartbeat,
                    plat)) {

                final var startTime = Instant.ofEpochSecond(1_000_000);

                // Start round 1 (opens genesis block)
                blockRecordManager.startRound(mockRound(1, startTime), merkleState);

                // End round 1 - block should close because it's round 1
                boolean closed = blockRecordManager.endRound(merkleState, mockRound(1, startTime.plusMillis(500)));
                assertTrue(closed, "Block should close at round 1");

                // Start round 2 (opens new block)
                blockRecordManager.startRound(mockRound(2, startTime.plusSeconds(1)), merkleState);

                // End round 2 - block should NOT close (only 0.5 second elapsed, need 2)
                closed = blockRecordManager.endRound(merkleState, mockRound(2, startTime.plusMillis(1500)));
                assertFalse(closed, "Block should not close when period not elapsed");

                // End round 3 - block SHOULD close (>= 2 seconds elapsed)
                closed = blockRecordManager.endRound(merkleState, mockRound(3, startTime.plusSeconds(3)));
                assertTrue(closed, "Block should close when period elapsed");
            }
        }

        /**
         * Test freeze round closes blocks.
         */
        @Test
        @DisplayName("Blocks close at freeze round")
        void testBlockClosesOnFreezeRound() throws Exception {
            // Update state with a freeze round
            app.stateMutator(PlatformStateService.NAME)
                    .withSingletonState(
                            V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID,
                            com.hedera.hapi.platform.state.PlatformState.newBuilder()
                                    .latestFreezeRound(5L)
                                    .build())
                    .commit();

            final var merkleState = app.workingStateAccessor().getState();
            final var producer = mock(BlockRecordStreamProducer.class);
            // Stub getRunningHash() to return empty bytes (used by endRound)
            given(producer.getRunningHash()).willReturn(Bytes.EMPTY);

            final var quiescenceCtrl = mock(QuiescenceController.class);
            final var heartbeat = mock(QuiescedHeartbeat.class);
            final var plat = mock(Platform.class);

            try (final var blockRecordManager = new BlockRecordManagerImpl(
                    app.configProvider(),
                    app.workingStateAccessor().getState(),
                    producer,
                    quiescenceCtrl,
                    heartbeat,
                    plat)) {

                final var startTime = Instant.ofEpochSecond(1_000_000);

                // Start round 1 (opens genesis block)
                blockRecordManager.startRound(mockRound(1, startTime), merkleState);

                // End round 1 - closes at round 1
                blockRecordManager.endRound(merkleState, mockRound(1, startTime.plusMillis(100)));

                // Start round 2
                blockRecordManager.startRound(mockRound(2, startTime.plusMillis(200)), merkleState);

                // End round 5 (freeze round) - should close even though period not elapsed
                boolean closed = blockRecordManager.endRound(merkleState, mockRound(5, startTime.plusMillis(500)));
                assertTrue(closed, "Block should close at freeze round");
            }
        }

        /**
         * Creates test transaction records for round-boundary mode testing.
         * The transactions are organized by rounds, not by time periods.
         *
         * @param startTime the starting consensus time
         * @param transactionsPerRound number of transactions per round
         * @param numRounds number of rounds to generate
         * @return list of transaction records organized by rounds
         */
        private List<SingleTransactionRecord> createRoundBoundaryTestData(
                final Instant startTime, final int transactionsPerRound, final int numRounds) {
            final List<SingleTransactionRecord> records = new ArrayList<>();
            // Load a real transaction record as a template
            try {
                final Path jsonPath = Path.of(RecordTestData.class
                        .getResource("/record-files/2023-05-01T00_00_24.038693760Z.json")
                        .toURI());
                final RecordStreamFile recordStreamFile =
                        RecordStreamFile.JSON.parse(new ReadableStreamingData(Files.newInputStream(jsonPath)));
                final var templateRecord = recordStreamFile.recordStreamItems().get(0);
                final var template = new SingleTransactionRecord(
                        templateRecord.transaction(),
                        templateRecord.record(),
                        Collections.emptyList(),
                        new SingleTransactionRecord.TransactionOutputs(TokenType.FUNGIBLE_COMMON));

                Instant currentTime = startTime;
                for (int round = 0; round < numRounds; round++) {
                    for (int tx = 0; tx < transactionsPerRound; tx++) {
                        final var timestamp = Timestamp.newBuilder()
                                .seconds(currentTime.getEpochSecond())
                                .nanos(currentTime.getNano())
                                .build();
                        final var record = createTransactionRecordWithTimestamp(template, timestamp);
                        records.add(record);
                        currentTime = currentTime.plusNanos(10); // Small increment between transactions
                    }
                    // Add a small gap between rounds
                    currentTime = currentTime.plusNanos(100);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to create test data", e);
            }
            return records;
        }

        /**
         * Creates a SingleTransactionRecord with a new consensus timestamp.
         */
        private SingleTransactionRecord createTransactionRecordWithTimestamp(
                final SingleTransactionRecord template, final Timestamp consensusTimestamp) throws ParseException {
            final var transaction = template.transaction();
            final var signedTransaction = SignedTransaction.PROTOBUF.parse(
                    BufferedData.wrap(transaction.signedTransactionBytes().toByteArray()));
            final var transactionBody = TransactionBody.PROTOBUF.parse(
                    BufferedData.wrap(signedTransaction.bodyBytes().toByteArray()));

            final var newTransactionBody = transactionBody
                    .copyBuilder()
                    .transactionID(transactionBody
                            .transactionID()
                            .copyBuilder()
                            .transactionValidStart(consensusTimestamp)
                            .build())
                    .build();
            final var newSignedTransaction = signedTransaction
                    .copyBuilder()
                    .bodyBytes(TransactionBody.PROTOBUF.toBytes(newTransactionBody))
                    .build();
            final var newTransaction = transaction
                    .copyBuilder()
                    .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(newSignedTransaction))
                    .build();

            final var newTransactionRecord = template.transactionRecord()
                    .copyBuilder()
                    .consensusTimestamp(consensusTimestamp)
                    .build();

            return new SingleTransactionRecord(
                    newTransaction, newTransactionRecord, Collections.emptyList(), template.transactionOutputs());
        }

        /**
         * Test general record stream production in round-boundary mode using startRound/endRound for block management.
         * This is similar to testRecordStreamProduction in BlockRecordManagerTest but uses the round-based API.
         * In round-boundary mode, blocks are opened at round start and closed at round end based on:
         * - Round 1 always closes
         * - Freeze rounds close
         * - When block period (2 seconds) elapses
         */
        @ParameterizedTest
        @CsvSource({"GENESIS, false", "NON_GENESIS, false", "GENESIS, true", "NON_GENESIS, true"})
        @DisplayName("Record stream production with round-boundary block management")
        void testRecordStreamProduction(final String startMode, final boolean concurrent) throws Exception {
            given(selfNodeAccountIdManager.getSelfNodeAccountId()).willReturn(NODE_INFO.accountId());

            // Create test data for round-boundary mode
            // Generate transactions organized by rounds (not time periods)
            // Round 1 will close block 0, then subsequent blocks close when 2 seconds elapse
            final var firstTransactionTime = Instant.ofEpochSecond(1_000_000L, 0);
            final int transactionsPerRound = 10;
            final int numRounds = 5; // Enough rounds to test multiple blocks
            final List<SingleTransactionRecord> allTransactions =
                    createRoundBoundaryTestData(firstTransactionTime, transactionsPerRound, numRounds);

            // setup initial block info
            final long STARTING_BLOCK;
            if (startMode.equals("GENESIS")) {
                STARTING_BLOCK = 0;
            } else {
                // pretend that previous block was 2 seconds before first test transaction
                STARTING_BLOCK = 100;
                app.stateMutator(NAME)
                        .withSingletonState(
                                BLOCKS_STATE_ID,
                                new BlockInfo(
                                        STARTING_BLOCK - 1,
                                        new Timestamp(firstTransactionTime.getEpochSecond() - 2, 0),
                                        STARTING_RUNNING_HASH_OBJ.hash(),
                                        CONSENSUS_TIME,
                                        true,
                                        FIRST_CONS_TIME_OF_LAST_BLOCK,
                                        EPOCH,
                                        EPOCH))
                        .commit();
            }

            final var merkleState = app.workingStateAccessor().getState();
            final var producer = concurrent
                    ? new StreamFileProducerConcurrent(
                            blockRecordFormat, blockRecordWriterFactory, ForkJoinPool.commonPool(), app.hapiVersion())
                    : new StreamFileProducerSingleThreaded(
                            blockRecordFormat, blockRecordWriterFactory, app.hapiVersion());
            Bytes finalRunningHash;
            // Track which block each transaction goes into for hash computation
            final List<Long> transactionBlockNumbers = new ArrayList<>();
            try (final var blockRecordManager = new BlockRecordManagerImpl(
                    app.configProvider(),
                    app.workingStateAccessor().getState(),
                    producer,
                    quiescenceController,
                    quiescedHeartbeat,
                    platform)) {
                // In round-boundary mode, we use startRound to open blocks
                long currentRoundNum = 1;
                // Start the first round - this will open the first block (genesis or non-genesis)
                blockRecordManager.startRound(mockRound(currentRoundNum, firstTransactionTime), merkleState);

                assertThat(blockRecordManager.blockTimestamp()).isNotNull();
                assertThat(blockRecordManager.blockNo()).isEqualTo(blockRecordManager.lastBlockNo() + 1);

                // write blocks & record files
                int transactionCount = 0;
                final List<Bytes> endOfBlockHashes = new ArrayList<>();
                Instant lastTransactionTime = firstTransactionTime;
                int transactionIndex = 0;
                long currentBlockNum = STARTING_BLOCK;

                // Process transactions in rounds
                // Round 1 will close block 0, then we'll process remaining transactions in new blocks
                while (transactionIndex < allTransactions.size()) {

                    // Process transactions for this round
                    final int roundStartIndex = transactionIndex;
                    final int roundEndIndex = Math.min(transactionIndex + transactionsPerRound, allTransactions.size());

                    for (int i = roundStartIndex; i < roundEndIndex; i++) {
                        final var record = allTransactions.get(i);
                        final var recordTime =
                                fromTimestamp(record.transactionRecord().consensusTimestamp());
                        // In round-boundary mode, startUserTransaction returns false (no-op)
                        assertFalse(blockRecordManager.startUserTransaction(recordTime, merkleState));

                        // check start hash if first transaction
                        if (transactionCount == 0) {
                            // check starting hash, we need to be using the correct starting hash for the tests to work
                            assertThat(blockRecordManager.getRunningHash().toHex())
                                    .isEqualTo(STARTING_RUNNING_HASH_OBJ.hash().toHex());
                        }
                        blockRecordManager.endUserTransaction(Stream.of(record), merkleState);
                        // Track which block this transaction is in
                        transactionBlockNumbers.add(currentBlockNum);
                        transactionCount++;
                        lastTransactionTime = recordTime;
                    }
                    transactionIndex = roundEndIndex;

                    // End the round after processing all transactions in this round
                    // In round-boundary mode, this may close the block if conditions are met
                    final var roundEndTime = lastTransactionTime;
                    final boolean closed =
                            blockRecordManager.endRound(merkleState, mockRound(currentRoundNum, roundEndTime));
                    if (currentRoundNum == 1) {}
                    currentRoundNum++;

                    // If the block was closed (e.g., round 1 always closes), start a new round to open the next block
                    // This ensures we can continue processing transactions in the next block
                    if (closed && transactionIndex < allTransactions.size()) {
                        // Get the first transaction time of the next round
                        final var nextRoundFirstTime = fromTimestamp(allTransactions
                                .get(transactionIndex)
                                .transactionRecord()
                                .consensusTimestamp());
                        // Start a new round to open the next block
                        blockRecordManager.startRound(mockRound(currentRoundNum, nextRoundFirstTime), merkleState);
                        currentBlockNum++; // Move to next block
                        currentRoundNum++;
                    }

                    // Track block hashes
                    endOfBlockHashes.add(blockRecordManager.getRunningHash());
                }

                // End the last round
                final var finalRoundEndTime = lastTransactionTime.plusNanos(200);
                blockRecordManager.endRound(merkleState, mockRound(currentRoundNum, finalRoundEndTime));

                // collect info for later validation
                finalRunningHash = blockRecordManager.getRunningHash();
                // try with resources will close the blockRecordManager and result in waiting for background threads to
                // finish and close any open files. Now collect block record manager info to be validated
            }

            // Compute expected running hash based on the actual block numbers used
            // Use the tracked block numbers for each transaction
            final var expectedRunningHash = BlockRecordFormatV6.INSTANCE.computeNewRunningHash(
                    STARTING_RUNNING_HASH_OBJ.hash(),
                    allTransactions.stream()
                            .map(str -> {
                                final int txIndex = allTransactions.indexOf(str);
                                final long blockNum = transactionBlockNumbers.get(txIndex);
                                return BlockRecordFormatV6.INSTANCE.serialize(str, blockNum, RecordTestData.VERSION);
                            })
                            .toList());

            // check running hash
            assertThat(expectedRunningHash.toHex()).isEqualTo(finalRunningHash.toHex());
        }

        private Round mockRound(long roundNum, Instant consensusTime) {
            final var mockRound = mock(Round.class);
            lenient().when(mockRound.getRoundNum()).thenReturn(roundNum);
            lenient().when(mockRound.getConsensusTimestamp()).thenReturn(consensusTime);
            return mockRound;
        }

        private static Instant fromTimestamp(final Timestamp timestamp) {
            return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
        }
    }
}
