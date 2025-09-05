// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.NONE;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.POST_UPGRADE_WORK;
import static com.hedera.node.app.blocks.BlockStreamManager.ZERO_BLOCK_HASH;
import static com.hedera.node.app.blocks.BlockStreamService.FAKE_RESTART_BLOCK_HASH;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.appendHash;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.combine;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_LABEL;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_STATE_LABEL;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.RecordFileItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.HederaVirtualMapState;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.InitialStateHash;
import com.hedera.node.app.service.networkadmin.impl.FreezeServiceImpl;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.test.fixtures.CryptoRandomUtils;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamManagerImplTest {

    private static final SemanticVersion CREATION_VERSION = new SemanticVersion(1, 2, 3, "alpha.1", "2");
    private static final long ROUND_NO = 123L;
    private static final long N_MINUS_2_BLOCK_NO = 664L;
    private static final long N_MINUS_1_BLOCK_NO = 665L;
    private static final long N_BLOCK_NO = 666L;
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L);
    private static final Timestamp CONSENSUS_THEN = new Timestamp(890, 0);
    private static final Hash FAKE_START_OF_BLOCK_STATE_HASH = new Hash(new byte[48]);
    private static final Bytes N_MINUS_2_BLOCK_HASH = Bytes.wrap(noThrowSha384HashOf(new byte[] {(byte) 0xAA}));
    private static final Bytes FIRST_FAKE_SIGNATURE = Bytes.fromHex("ff".repeat(48));
    private static final Bytes SECOND_FAKE_SIGNATURE = Bytes.fromHex("ee".repeat(48));
    private static final BlockItem FAKE_SIGNED_TRANSACTION =
            BlockItem.newBuilder().signedTransaction(Bytes.EMPTY).build();
    private static final BlockItem FAKE_TRANSACTION_RESULT = BlockItem.newBuilder()
            .transactionResult(TransactionResult.newBuilder().consensusTimestamp(CONSENSUS_THEN))
            .build();
    private static final Bytes FAKE_RESULT_HASH = noThrowSha384HashOfItem(FAKE_TRANSACTION_RESULT);
    private static final BlockItem FAKE_STATE_CHANGES = BlockItem.newBuilder()
            .stateChanges(StateChanges.newBuilder().consensusTimestamp(CONSENSUS_THEN))
            .build();
    private static final BlockItem FAKE_RECORD_FILE_ITEM =
            BlockItem.newBuilder().recordFile(RecordFileItem.DEFAULT).build();
    private final InitialStateHash hashInfo = new InitialStateHash(completedFuture(ZERO_BLOCK_HASH), 0);

    @Mock
    private BlockHashSigner blockHashSigner;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private StateHashedNotification notification;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private BoundaryStateChangeListener boundaryStateChangeListener;

    @Mock
    private BlockStreamManager.Lifecycle lifecycle;

    @Mock
    private BlockItemWriter aWriter;

    @Mock
    private BlockItemWriter bWriter;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private CompletableFuture<Bytes> mockSigningFuture;

    private WritableStates writableStates;

    @Mock
    private Round round;

    @Mock
    private HederaVirtualMapState state;

    @Mock
    private Iterator<ConsensusEvent> mockIterator;

    @Mock
    private ConsensusEvent mockEvent;

    @Mock
    private Metrics metrics;

    @Mock
    private Counter indirectProofsCounter;

    @Mock
    private ReadableSingletonState<Object> platformStateReadableSingletonState;

    private final AtomicReference<Bytes> lastAItem = new AtomicReference<>();
    private final AtomicReference<Bytes> lastBItem = new AtomicReference<>();
    private final AtomicReference<PlatformState> stateRef = new AtomicReference<>();
    private final AtomicReference<BlockStreamInfo> infoRef = new AtomicReference<>();

    private WritableSingletonStateBase<BlockStreamInfo> blockStreamInfoState;

    private BlockStreamManagerImpl subject;

    @BeforeEach
    void setUp() {
        writableStates = mock(WritableStates.class, withSettings().extraInterfaces(CommittableWritableStates.class));
        lenient().when(metrics.getOrCreate(any(Counter.Config.class))).thenReturn(indirectProofsCounter);
    }

    @Test
    void classifiesPendingGenesisWorkByIntervalTime() {
        assertSame(
                BlockStreamManager.PendingWork.GENESIS_WORK,
                BlockStreamManagerImpl.classifyPendingWork(BlockStreamInfo.DEFAULT, SemanticVersion.DEFAULT));
    }

    @Test
    void classifiesPriorVersionHasPostUpgradeWorkWithDifferentVersionButIntervalTime() {
        assertSame(
                POST_UPGRADE_WORK,
                BlockStreamManagerImpl.classifyPendingWork(
                        BlockStreamInfo.newBuilder()
                                .creationSoftwareVersion(
                                        SemanticVersion.newBuilder().major(1).build())
                                .lastHandleTime(new Timestamp(1234567, 890))
                                .build(),
                        CREATION_VERSION));
    }

    @Test
    void classifiesNonGenesisBlockOfSameVersionWithWorkNotDoneStillHasPostUpgradeWork() {
        assertEquals(
                POST_UPGRADE_WORK,
                BlockStreamManagerImpl.classifyPendingWork(
                        BlockStreamInfo.newBuilder()
                                .creationSoftwareVersion(CREATION_VERSION)
                                .lastHandleTime(new Timestamp(1234567, 890))
                                .build(),
                        CREATION_VERSION));
    }

    @Test
    void classifiesNonGenesisBlockOfSameVersionWithWorkDoneAsNoWork() {
        assertSame(
                NONE,
                BlockStreamManagerImpl.classifyPendingWork(
                        BlockStreamInfo.newBuilder()
                                .postUpgradeWorkDone(true)
                                .creationSoftwareVersion(CREATION_VERSION)
                                .lastIntervalProcessTime(new Timestamp(1234567, 890))
                                .lastHandleTime(new Timestamp(1234567, 890))
                                .build(),
                        CREATION_VERSION));
    }

    @Test
    void canUpdateDistinguishedTimes() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(DEFAULT_CONFIG, 1L));
        subject = new BlockStreamManagerImpl(
                blockHashSigner,
                () -> aWriter,
                ForkJoinPool.commonPool(),
                configProvider,
                networkInfo,
                boundaryStateChangeListener,
                hashInfo,
                SemanticVersion.DEFAULT,
                TEST_PLATFORM_STATE_FACADE,
                lifecycle,
                metrics);
        assertSame(Instant.EPOCH, subject.lastIntervalProcessTime());
        subject.setLastIntervalProcessTime(CONSENSUS_NOW);
        assertEquals(CONSENSUS_NOW, subject.lastIntervalProcessTime());

        assertSame(Instant.EPOCH, subject.lastTopLevelConsensusTime());
        subject.setLastTopLevelTime(CONSENSUS_NOW);
        assertEquals(CONSENSUS_NOW, subject.lastTopLevelConsensusTime());
    }

    @Test
    void requiresLastHashToBeInitialized() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(DEFAULT_CONFIG, 1));
        subject = new BlockStreamManagerImpl(
                blockHashSigner,
                () -> aWriter,
                ForkJoinPool.commonPool(),
                configProvider,
                networkInfo,
                boundaryStateChangeListener,
                hashInfo,
                SemanticVersion.DEFAULT,
                TEST_PLATFORM_STATE_FACADE,
                lifecycle,
                metrics);
        assertThrows(IllegalStateException.class, () -> subject.startRound(round, state));
    }

    @Test
    void startsAndEndsBlockWithSingleRoundPerBlockAsExpected() throws ParseException {
        givenSubjectWith(
                1,
                0,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        givenEndOfRoundSetup();
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);
        given(round.getRoundNum()).willReturn(ROUND_NO);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);
        assertFalse(subject.hasLedgerId());

        given(blockHashSigner.isReady()).willReturn(true);
        given(blockHashSigner.schemeId()).willReturn(1L);
        // Start the round that will be block N
        subject.startRound(round, state);
        assertTrue(subject.hasLedgerId());
        assertSame(POST_UPGRADE_WORK, subject.pendingWork());
        subject.confirmPendingWorkFinished();
        assertSame(NONE, subject.pendingWork());
        // We don't fail hard on duplicate calls to confirm post-upgrade work
        assertDoesNotThrow(() -> subject.confirmPendingWorkFinished());

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertNull(subject.prngSeed());
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_SIGNED_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);

        // Immediately resolve to the expected ledger signature
        given(blockHashSigner.signFuture(any())).willReturn(mockSigningFuture);
        doAnswer(invocationOnMock -> {
                    final Consumer<Bytes> consumer = invocationOnMock.getArgument(0);
                    consumer.accept(FIRST_FAKE_SIGNATURE);
                    return null;
                })
                .when(mockSigningFuture)
                .thenAcceptAsync(any());
        // End the round
        subject.endRound(state, ROUND_NO);

        verify(aWriter).openBlock(N_BLOCK_NO);

        // Assert the internal state of the subject has changed as expected and the writer has been closed
        final var expectedBlockInfo = new BlockStreamInfo(
                N_BLOCK_NO,
                asTimestamp(CONSENSUS_NOW),
                appendHash(combine(ZERO_BLOCK_HASH, FAKE_RESULT_HASH), appendHash(ZERO_BLOCK_HASH, Bytes.EMPTY, 4), 4),
                appendHash(FAKE_RESTART_BLOCK_HASH, appendHash(N_MINUS_2_BLOCK_HASH, Bytes.EMPTY, 256), 256),
                Bytes.fromHex(
                        "edde6b2beddb2fda438665bbe6df0a639c518e6d5352e7276944b70777d437d28d1b22813ed70f5b8a3a3cbaf08aa9a8"),
                ZERO_BLOCK_HASH,
                2,
                List.of(
                        Bytes.EMPTY,
                        Bytes.fromHex(
                                "839ddb854c8f4cf9c3705268b17bc7d53e91454ff14dbbfffd6c77b6118a0e79fb1e478b4924bfb0fd93ef60101d3237")),
                FAKE_TRANSACTION_RESULT.transactionResultOrThrow().consensusTimestampOrThrow(),
                true,
                SemanticVersion.DEFAULT,
                CONSENSUS_THEN,
                CONSENSUS_THEN,
                Bytes.fromHex(
                        "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b"),
                Bytes.fromHex(
                        "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b"),
                Bytes.fromHex(
                        "bf99e1dfd15ffe551ae4bc0953f396639755f0419522f323875806a55a57dca6a4df61ea6dee28bec0c37ed54881d392"));

        final var actualBlockInfo = infoRef.get();
        assertEquals(expectedBlockInfo, actualBlockInfo);

        // Assert the block proof was written
        final var proofItem = lastAItem.get();
        assertNotNull(proofItem);
        final var item = BlockItem.PROTOBUF.parse(proofItem);
        assertTrue(item.hasBlockProof());
        final var proof = item.blockProofOrThrow();
        assertEquals(N_BLOCK_NO, proof.block());
        assertEquals(FIRST_FAKE_SIGNATURE, proof.blockSignature());
    }

    @Test
    void doesNotEndBlockEvenAtModZeroRoundIfSignerIsNotReady() {
        givenSubjectWith(
                1,
                0,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        givenEndOfRoundSetup();
        lenient().when(round.getRoundNum()).thenReturn(ROUND_NO);
        lenient().when(round.getConsensusTimestamp()).thenReturn(CONSENSUS_NOW);
        lenient().when(blockHashSigner.isReady()).thenReturn(false);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertNull(subject.prngSeed());
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_SIGNED_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);

        // End the round (which cannot close the block since signer isn't ready)
        subject.endRound(state, ROUND_NO);

        // Verify signer was checked but never asked to sign
        verify(blockHashSigner, never()).signFuture(any());
    }

    @Test
    void blockWithNoUserTransactionsHasExpectedHeader() {
        givenSubjectWith(
                1,
                0,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        final AtomicReference<BlockHeader> writtenHeader = new AtomicReference<>();
        givenEndOfRoundSetup(writtenHeader);
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);
        given(round.getRoundNum()).willReturn(ROUND_NO);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);
        assertFalse(subject.hasLedgerId());

        given(blockHashSigner.isReady()).willReturn(true);
        given(blockHashSigner.schemeId()).willReturn(1L);
        // Start the round that will be block N
        subject.startRound(round, state);
        assertTrue(subject.hasLedgerId());
        assertSame(POST_UPGRADE_WORK, subject.pendingWork());
        subject.confirmPendingWorkFinished();
        assertSame(NONE, subject.pendingWork());
        // We don't fail hard on duplicate calls to confirm post-upgrade work
        assertDoesNotThrow(() -> subject.confirmPendingWorkFinished());

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertNull(subject.prngSeed());
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_SIGNED_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);

        // Immediately resolve to the expected ledger signature
        given(blockHashSigner.signFuture(any())).willReturn(completedFuture(FIRST_FAKE_SIGNATURE));
        // End the round
        subject.endRound(state, ROUND_NO);

        final var header = writtenHeader.get();
        assertNotNull(header);
        assertEquals(N_BLOCK_NO, header.number());
        assertEquals(header.blockTimestamp(), asTimestamp(CONSENSUS_NOW));
    }

    @Test
    void doesNotEndBlockWithMultipleRoundPerBlockIfNotModZero() {
        givenSubjectWith(
                2,
                0,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        givenEndOfRoundSetup();
        given(round.getRoundNum()).willReturn(ROUND_NO);
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);
        given(state.getReadableStates(BlockStreamService.NAME)).willReturn(readableStates);
        given(state.getReadableStates(PlatformStateService.NAME)).willReturn(readableStates);
        given(readableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .willReturn(blockStreamInfoState);
        given(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        PLATFORM_STATE_STATE_ID, PLATFORM_STATE_STATE_LABEL, stateRef::get, stateRef::set));

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);

        // Assert the internal state of the subject has changed as expected
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));

        // Write some items to the block
        subject.writeItem(FAKE_SIGNED_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);

        // End the round
        subject.endRound(state, ROUND_NO);

        // Assert the internal state of the subject has changed as expected and the writer has been closed
        verify(blockHashSigner).isReady();
        verifyNoMoreInteractions(blockHashSigner);
    }

    @Test
    void alwaysEndsBlockOnFreezeRoundPerBlockAsExpected() throws ParseException {
        final var resultHashes = Bytes.fromHex("aa".repeat(48) + "bb".repeat(48) + "cc".repeat(48) + "dd".repeat(48));
        givenSubjectWith(
                2,
                2, // Use time-based blocks with 2 second period
                blockStreamInfoWith(resultHashes, CREATION_VERSION),
                platformStateWithFreezeTime(CONSENSUS_NOW),
                aWriter);
        givenEndOfRoundSetup();
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(PLATFORM_STATE_STATE_ID)).willReturn(platformStateReadableSingletonState);
        given(platformStateReadableSingletonState.get())
                .willReturn(
                        PlatformState.newBuilder().latestFreezeRound(ROUND_NO).build());
        given(round.getRoundNum()).willReturn(ROUND_NO);
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        given(blockHashSigner.isReady()).willReturn(true);
        given(blockHashSigner.schemeId()).willReturn(1L);
        // Start the round that will be block N
        subject.startRound(round, state);

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_SIGNED_TRANSACTION);
        assertEquals(Bytes.fromHex("aa".repeat(48)), subject.prngSeed());
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        assertEquals(Bytes.fromHex("bb".repeat(48)), subject.prngSeed());
        subject.writeItem(FAKE_STATE_CHANGES);
        for (int i = 0; i < 8; i++) {
            subject.writeItem(FAKE_RECORD_FILE_ITEM);
        }

        // Immediately resolve to the expected ledger signature
        given(blockHashSigner.signFuture(any())).willReturn(mockSigningFuture);
        doAnswer(invocationOnMock -> {
                    final Consumer<Bytes> consumer = invocationOnMock.getArgument(0);
                    consumer.accept(FIRST_FAKE_SIGNATURE);
                    return null;
                })
                .when(mockSigningFuture)
                .thenAcceptAsync(any());
        // End the round
        subject.endRound(state, ROUND_NO);

        verify(aWriter).openBlock(N_BLOCK_NO);

        // Assert the internal state of the subject has changed as expected and the writer has been closed
        final var expectedBlockInfo = new BlockStreamInfo(
                N_BLOCK_NO,
                asTimestamp(CONSENSUS_NOW),
                appendHash(combine(Bytes.fromHex("dd".repeat(48)), FAKE_RESULT_HASH), resultHashes, 4),
                appendHash(FAKE_RESTART_BLOCK_HASH, appendHash(N_MINUS_2_BLOCK_HASH, Bytes.EMPTY, 256), 256),
                Bytes.fromHex(
                        "edde6b2beddb2fda438665bbe6df0a639c518e6d5352e7276944b70777d437d28d1b22813ed70f5b8a3a3cbaf08aa9a8"),
                ZERO_BLOCK_HASH,
                2,
                List.of(
                        Bytes.EMPTY,
                        Bytes.fromHex(
                                "839ddb854c8f4cf9c3705268b17bc7d53e91454ff14dbbfffd6c77b6118a0e79fb1e478b4924bfb0fd93ef60101d3237")),
                FAKE_TRANSACTION_RESULT.transactionResultOrThrow().consensusTimestampOrThrow(),
                false,
                SemanticVersion.DEFAULT,
                CONSENSUS_THEN,
                CONSENSUS_THEN,
                Bytes.fromHex(
                        "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b"),
                Bytes.fromHex(
                        "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b"),
                Bytes.fromHex(
                        "8ee0718d5f75f867f85cb4e400ebf7bfbb4cd91479d7f3f8bfd28ce062c318c312b8f4de185a994b78337e6391e3f000"));
        final var actualBlockInfo = infoRef.get();
        assertEquals(expectedBlockInfo, actualBlockInfo);

        // Assert the block proof was written
        final var proofItem = lastAItem.get();
        assertNotNull(proofItem);
        final var item = BlockItem.PROTOBUF.parse(proofItem);
        assertTrue(item.hasBlockProof());
        final var proof = item.blockProofOrThrow();
        assertEquals(N_BLOCK_NO, proof.block());
        assertEquals(FIRST_FAKE_SIGNATURE, proof.blockSignature());
    }

    @Test
    @SuppressWarnings("unchecked")
    void supportsMultiplePendingBlocksWithIndirectProofAsExpected() throws ParseException {
        given(blockHashSigner.isReady()).willReturn(true);
        given(blockHashSigner.schemeId()).willReturn(1L);
        givenSubjectWith(
                1,
                0,
                blockStreamInfoWith(Bytes.EMPTY, CREATION_VERSION),
                platformStateWithFreezeTime(null),
                aWriter,
                bWriter);
        givenEndOfRoundSetup();
        doAnswer(invocationOnMock -> {
                    lastBItem.set(invocationOnMock.getArgument(1));
                    return bWriter;
                })
                .when(bWriter)
                .writePbjItemAndBytes(any(), any());
        given(round.getRoundNum()).willReturn(ROUND_NO);
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);
        // Write some items to the block
        subject.writeItem(FAKE_SIGNED_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);
        final CompletableFuture<Bytes> firstSignature = (CompletableFuture<Bytes>) mock(CompletableFuture.class);
        final CompletableFuture<Bytes> secondSignature = (CompletableFuture<Bytes>) mock(CompletableFuture.class);
        given(blockHashSigner.signFuture(any())).willReturn(firstSignature).willReturn(secondSignature);
        // End the round in block N
        subject.endRound(state, ROUND_NO);

        // Start the round that will be block N+1
        given(round.getRoundNum()).willReturn(ROUND_NO + 1);
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW.plusSeconds(1)); // Next round timestamp
        given(notification.round()).willReturn(ROUND_NO);
        given(notification.hash()).willReturn(FAKE_START_OF_BLOCK_STATE_HASH);
        // Notify the subject of the required start-of-state hash
        subject.notify(notification);
        subject.startRound(round, state);
        // Write some items to the block
        subject.writeItem(FAKE_SIGNED_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);
        // End the round in block N+1
        subject.endRound(state, ROUND_NO + 1);

        final ArgumentCaptor<Consumer<Bytes>> firstCaptor = ArgumentCaptor.forClass(Consumer.class);
        final ArgumentCaptor<Consumer<Bytes>> secondCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(firstSignature).thenAcceptAsync(firstCaptor.capture());
        verify(secondSignature).thenAcceptAsync(secondCaptor.capture());
        secondCaptor.getValue().accept(FIRST_FAKE_SIGNATURE);
        firstCaptor.getValue().accept(SECOND_FAKE_SIGNATURE);

        // Assert both block proofs were written, but with the proof for N using an indirect proof
        final var aProofItem = lastAItem.get();
        assertNotNull(aProofItem);
        final var aItem = BlockItem.PROTOBUF.parse(aProofItem);
        assertTrue(aItem.hasBlockProof());
        final var aProof = aItem.blockProofOrThrow();
        assertEquals(N_BLOCK_NO, aProof.block());
        assertEquals(FIRST_FAKE_SIGNATURE, aProof.blockSignature());
        assertEquals(3, aProof.siblingHashes().size());
        // And the proof for N+1 using a direct proof
        final var bProofItem = lastBItem.get();
        assertNotNull(bProofItem);
        final var bItem = BlockItem.PROTOBUF.parse(bProofItem);
        assertTrue(bItem.hasBlockProof());
        final var bProof = bItem.blockProofOrThrow();
        assertEquals(N_BLOCK_NO + 1, bProof.block());
        assertEquals(FIRST_FAKE_SIGNATURE, bProof.blockSignature());
        assertTrue(bProof.siblingHashes().isEmpty());

        verify(indirectProofsCounter).increment();
    }

    @Test
    void createsBlockWhenTimePeriodElapses() {
        // Given a 2 second block period
        givenSubjectWith(
                1,
                2,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        givenEndOfRoundSetup();
        given(round.getRoundNum()).willReturn(ROUND_NO);
        given(blockHashSigner.isReady()).willReturn(true);
        given(blockHashSigner.schemeId()).willReturn(1L);

        // Set up the signature future to complete immediately and run the callback synchronously
        given(blockHashSigner.signFuture(any())).willReturn(mockSigningFuture);
        doAnswer(invocationOnMock -> {
                    final Consumer<Bytes> consumer = invocationOnMock.getArgument(0);
                    consumer.accept(FIRST_FAKE_SIGNATURE);
                    return null;
                })
                .when(mockSigningFuture)
                .thenAcceptAsync(any());

        // When starting a round at t=0
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(1000));
        subject.initLastBlockHash(N_MINUS_2_BLOCK_HASH);
        subject.startRound(round, state);

        // And another round at t=1
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(1001));
        subject.startRound(round, state);
        subject.endRound(state, ROUND_NO);

        // Then block should not be closed
        verify(aWriter, never()).closeCompleteBlock();

        // When starting another round at t=3 (after period)
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(1003));
        subject.startRound(round, state);
        subject.endRound(state, ROUND_NO);

        // Then block should be closed
        verify(aWriter).closeCompleteBlock();
    }

    @Test
    void doesNotCreateBlockWhenTimePeriodNotElapsed() {
        // Given a 2 second block period
        givenSubjectWith(
                1,
                2,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        givenEndOfRoundSetup();
        given(round.getRoundNum()).willReturn(ROUND_NO);
        given(blockHashSigner.isReady()).willReturn(true);

        // When starting a round at t=0
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(1000));
        subject.initLastBlockHash(N_MINUS_2_BLOCK_HASH);
        subject.startRound(round, state);

        // And another round at t=1.5
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(1001, 500_000_000));
        subject.startRound(round, state);
        subject.endRound(state, ROUND_NO);

        // Then block should not be closed
        verify(aWriter, never()).closeCompleteBlock();
    }

    @Test
    void alwaysEndsBlockOnFreezeRoundEvenIfPeriodNotElapsed() {
        // Given a 2 second block period
        givenSubjectWith(
                1,
                2,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(Instant.ofEpochSecond(1001)),
                aWriter);
        givenEndOfRoundSetup();
        given(round.getRoundNum()).willReturn(ROUND_NO);
        given(blockHashSigner.isReady()).willReturn(true);
        given(blockHashSigner.schemeId()).willReturn(1L);
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(PLATFORM_STATE_STATE_ID)).willReturn(platformStateReadableSingletonState);
        given(platformStateReadableSingletonState.get())
                .willReturn(
                        PlatformState.newBuilder().latestFreezeRound(ROUND_NO).build());

        // Set up the signature future to complete immediately and run the callback synchronously
        given(blockHashSigner.signFuture(any())).willReturn(mockSigningFuture);
        doAnswer(invocationOnMock -> {
                    final Consumer<Bytes> consumer = invocationOnMock.getArgument(0);
                    consumer.accept(FIRST_FAKE_SIGNATURE);
                    return null;
                })
                .when(mockSigningFuture)
                .thenAcceptAsync(any());

        // When starting a round at t=0
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(1000));
        subject.initLastBlockHash(N_MINUS_2_BLOCK_HASH);
        subject.startRound(round, state);

        // And another round at t=1 with freeze
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(1001));
        subject.startRound(round, state);
        subject.endRound(state, ROUND_NO);

        // Then block should be closed due to freeze, even though period not elapsed
        verify(aWriter).closeCompleteBlock();
    }

    @Test
    void usesRoundsPerBlockWhenBlockPeriodIsZero() {
        // Given blockPeriodSeconds=0 and roundsPerBlock=2
        givenSubjectWith(
                2,
                0,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        givenEndOfRoundSetup();
        given(blockHashSigner.isReady()).willReturn(true);

        // Set up the signature future to complete immediately and run the callback synchronously
        given(blockHashSigner.signFuture(any())).willReturn(mockSigningFuture);
        doAnswer(invocationOnMock -> {
                    final Consumer<Bytes> consumer = invocationOnMock.getArgument(0);
                    consumer.accept(FIRST_FAKE_SIGNATURE);
                    return null;
                })
                .when(mockSigningFuture)
                .thenAcceptAsync(any());

        // When processing rounds
        given(round.getConsensusTimestamp()).willReturn(Instant.ofEpochSecond(1000));
        subject.initLastBlockHash(N_MINUS_2_BLOCK_HASH);

        // First round (not mod 2)
        given(round.getRoundNum()).willReturn(3L);
        subject.startRound(round, state);
        subject.endRound(state, 3L);
        verify(aWriter, never()).closeCompleteBlock();

        // Second round (mod 2)
        given(round.getRoundNum()).willReturn(4L);
        subject.startRound(round, state);
        subject.endRound(state, 4L);
        verify(aWriter).closeCompleteBlock();
    }

    @Test
    void trackEventHashAddsEventHashAndGetEventIndexReturnsTheirPositions() {
        // Given a manager with a single round per block
        givenSubjectWith(
                1, 0, blockStreamInfoWith(Bytes.EMPTY, CREATION_VERSION), platformStateWithFreezeTime(null), aWriter);
        givenEndOfRoundSetup();
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);
        given(round.getRoundNum()).willReturn(ROUND_NO);

        // Initialize hash and start a round
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);
        subject.startRound(round, state);

        // When tracking multiple event hashes
        Hash eventHash1 = CryptoRandomUtils.randomHash();
        Hash eventHash2 = CryptoRandomUtils.randomHash();
        Hash eventHash3 = CryptoRandomUtils.randomHash();

        subject.trackEventHash(eventHash1);
        subject.trackEventHash(eventHash2);
        subject.trackEventHash(eventHash3);

        // Then they should be retrievable by index
        assertEquals(Optional.of(0), subject.getEventIndex(eventHash1));
        assertEquals(Optional.of(1), subject.getEventIndex(eventHash2));
        assertEquals(Optional.of(2), subject.getEventIndex(eventHash3));

        // And non-existent hash should return empty
        Hash unknownHash = CryptoRandomUtils.randomHash();
        assertEquals(Optional.empty(), subject.getEventIndex(unknownHash));
    }

    @Test
    void eventHashMapIsClearedBetweenBlocks() {
        // Given a manager with a single round per block
        givenSubjectWith(
                1,
                0,
                blockStreamInfoWith(Bytes.EMPTY, CREATION_VERSION),
                platformStateWithFreezeTime(null),
                aWriter,
                aWriter);
        givenEndOfRoundSetup();
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);
        given(round.getRoundNum()).willReturn(ROUND_NO);
        given(blockHashSigner.isReady()).willReturn(true);

        // Set up the signature future to complete immediately
        given(blockHashSigner.signFuture(any())).willReturn(mockSigningFuture);
        doAnswer(invocationOnMock -> {
                    final Consumer<Bytes> consumer = invocationOnMock.getArgument(0);
                    consumer.accept(FIRST_FAKE_SIGNATURE);
                    return null;
                })
                .when(mockSigningFuture)
                .thenAcceptAsync(any());

        // Initialize hash and start a round
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);
        subject.startRound(round, state);

        // Track event hashes in the first block
        Hash eventHash1 = CryptoRandomUtils.randomHash();
        Hash eventHash2 = CryptoRandomUtils.randomHash();

        subject.trackEventHash(eventHash1);
        subject.trackEventHash(eventHash2);

        // Verify hashes are tracked in the first block
        assertEquals(Optional.of(0), subject.getEventIndex(eventHash1));
        assertEquals(Optional.of(1), subject.getEventIndex(eventHash2));

        // End the first block
        subject.endRound(state, ROUND_NO);

        // Start a new round/block
        given(round.getRoundNum()).willReturn(ROUND_NO + 1);
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW.plusSeconds(1));
        subject.startRound(round, state);

        // The previous block's event hashes should no longer be available
        assertEquals(Optional.empty(), subject.getEventIndex(eventHash1));
        assertEquals(Optional.empty(), subject.getEventIndex(eventHash2));

        // Track a new event hash in the second block
        Hash eventHash3 = CryptoRandomUtils.randomHash();
        subject.trackEventHash(eventHash3);

        // Verify the index starts from 0 again in the new block
        assertEquals(Optional.of(0), subject.getEventIndex(eventHash3));
    }

    private void givenSubjectWith(
            final int roundsPerBlock,
            final int blockPeriod,
            @NonNull final BlockStreamInfo blockStreamInfo,
            @NonNull final PlatformState platformState,
            @NonNull final BlockItemWriter... writers) {
        final AtomicInteger nextWriter = new AtomicInteger(0);
        final var config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.roundsPerBlock", roundsPerBlock)
                .withValue("blockStream.blockPeriod", Duration.of(blockPeriod, ChronoUnit.SECONDS))
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));
        subject = new BlockStreamManagerImpl(
                blockHashSigner,
                () -> writers[nextWriter.getAndIncrement()],
                ForkJoinPool.commonPool(),
                configProvider,
                networkInfo,
                boundaryStateChangeListener,
                hashInfo,
                SemanticVersion.DEFAULT,
                TEST_PLATFORM_STATE_FACADE,
                lifecycle,
                metrics);
        given(state.getReadableStates(any())).willReturn(readableStates);
        given(readableStates.getSingleton(PLATFORM_STATE_STATE_ID)).willReturn(platformStateReadableSingletonState);
        lenient().when(state.getReadableStates(FreezeServiceImpl.NAME)).thenReturn(readableStates);
        infoRef.set(blockStreamInfo);
        stateRef.set(platformState);
        blockStreamInfoState = new FunctionWritableSingletonState<>(
                BLOCK_STREAM_INFO_STATE_ID, BLOCK_STREAM_INFO_STATE_LABEL, infoRef::get, infoRef::set);
    }

    private void givenEndOfRoundSetup() {
        givenEndOfRoundSetup(null);
    }

    private void givenEndOfRoundSetup(@Nullable final AtomicReference<BlockHeader> headerRef) {
        // Add mock for round iterator
        lenient().when(round.iterator()).thenReturn(mockIterator);
        lenient().when(mockIterator.next()).thenReturn(mockEvent);
        lenient().when(mockEvent.getConsensusTimestamp()).thenReturn(CONSENSUS_NOW);
        lenient()
                .doAnswer(invocationOnMock -> {
                    lastAItem.set(invocationOnMock.getArgument(1));
                    if (headerRef != null) {
                        final var item = BlockItem.PROTOBUF.parse(lastAItem.get());
                        if (item.hasBlockHeader()) {
                            headerRef.set(item.blockHeaderOrThrow());
                        }
                    }
                    return aWriter;
                })
                .when(aWriter)
                .writePbjItemAndBytes(any(), any());
        lenient().when(state.getWritableStates(BlockStreamService.NAME)).thenReturn(writableStates);
        lenient().when(state.getReadableStates(BlockStreamService.NAME)).thenReturn(readableStates);
        lenient().when(state.getReadableStates(PlatformStateService.NAME)).thenReturn(readableStates);
        lenient()
                .when(writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .thenReturn(blockStreamInfoState);
        lenient()
                .when(readableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .thenReturn(blockStreamInfoState);
        lenient()
                .when(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID))
                .thenReturn(new FunctionWritableSingletonState<>(
                        PLATFORM_STATE_STATE_ID, PLATFORM_STATE_STATE_LABEL, stateRef::get, stateRef::set));
        lenient()
                .doAnswer(invocationOnMock -> {
                    blockStreamInfoState.commit();
                    return null;
                })
                .when((CommittableWritableStates) writableStates)
                .commit();
    }

    private BlockStreamInfo blockStreamInfoWith(
            @NonNull final Bytes resultHashes, @NonNull final SemanticVersion creationVersion) {
        return BlockStreamInfo.newBuilder()
                .blockNumber(N_MINUS_1_BLOCK_NO)
                .creationSoftwareVersion(creationVersion)
                .trailingBlockHashes(appendHash(N_MINUS_2_BLOCK_HASH, Bytes.EMPTY, 256))
                .trailingOutputHashes(resultHashes)
                .lastIntervalProcessTime(CONSENSUS_THEN)
                .lastHandleTime(CONSENSUS_THEN)
                .blockTime(asTimestamp(CONSENSUS_NOW.minusSeconds(5))) // Add block time to track last block creation
                .build();
    }

    private PlatformState platformStateWithFreezeTime(@Nullable final Instant freezeTime) {
        return PlatformState.newBuilder()
                .creationSoftwareVersion(CREATION_VERSION)
                .freezeTime(freezeTime == null ? null : asTimestamp(freezeTime))
                .build();
    }

    private static Bytes noThrowSha384HashOfItem(@NonNull final BlockItem item) {
        return Bytes.wrap(noThrowSha384HashOf(BlockItem.PROTOBUF.toBytes(item).toByteArray()));
    }
}
