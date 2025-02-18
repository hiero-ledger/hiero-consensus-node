/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.NONE;
import static com.hedera.node.app.blocks.BlockStreamManager.PendingWork.POST_UPGRADE_WORK;
import static com.hedera.node.app.blocks.BlockStreamManager.ZERO_BLOCK_HASH;
import static com.hedera.node.app.blocks.BlockStreamService.FAKE_RESTART_BLOCK_HASH;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.appendHash;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.combine;
import static com.hedera.node.app.blocks.impl.BlockStreamManagerImpl.classifyPendingWork;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.InitialStateHash;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Hash;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.events.ConsensusEvent;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    private static final BlockItem FAKE_EVENT_TRANSACTION =
            BlockItem.newBuilder().eventTransaction(EventTransaction.DEFAULT).build();
    private static final BlockItem FAKE_TRANSACTION_RESULT =
            BlockItem.newBuilder().transactionResult(TransactionResult.DEFAULT).build();
    private static final Bytes FAKE_RESULT_HASH = noThrowSha384HashOfItem(FAKE_TRANSACTION_RESULT);
    private static final BlockItem FAKE_STATE_CHANGES =
            BlockItem.newBuilder().stateChanges(StateChanges.DEFAULT).build();
    private static final BlockItem FAKE_RECORD_FILE_ITEM =
            BlockItem.newBuilder().recordFile(RecordFileItem.DEFAULT).build();
    private final InitialStateHash hashInfo = new InitialStateHash(completedFuture(ZERO_BLOCK_HASH), 0);

    @Mock
    private BlockHashSigner blockHashSigner;

    @Mock
    private StateHashedNotification notification;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private BoundaryStateChangeListener boundaryStateChangeListener;

    @Mock
    private BlockItemWriter aWriter;

    @Mock
    private BlockItemWriter bWriter;

    @Mock
    private ReadableStates readableStates;

    @Mock
    private CompletableFuture<Bytes> mockSigningFuture;

    @Mock
    private ConsensusEvent consensusEvent;

    private WritableStates writableStates;

    @Mock
    private Round round;

    @Mock
    private State state;

    private final AtomicReference<Bytes> lastAItem = new AtomicReference<>();
    private final AtomicReference<Bytes> lastBItem = new AtomicReference<>();
    private final AtomicReference<PlatformState> stateRef = new AtomicReference<>();
    private final AtomicReference<BlockStreamInfo> infoRef = new AtomicReference<>();

    private WritableSingletonStateBase<BlockStreamInfo> blockStreamInfoState;

    private BlockStreamManagerImpl subject;

    @BeforeEach
    void setUp() {
        writableStates = mock(WritableStates.class, withSettings().extraInterfaces(CommittableWritableStates.class));
    }

    @Test
    void classifiesPendingGenesisWorkByIntervalTime() {
        assertSame(
                BlockStreamManager.PendingWork.GENESIS_WORK,
                classifyPendingWork(BlockStreamInfo.DEFAULT, SemanticVersion.DEFAULT));
    }

    @Test
    void classifiesPriorVersionHasPostUpgradeWorkWithDifferentVersionButIntervalTime() {
        assertSame(
                POST_UPGRADE_WORK,
                classifyPendingWork(
                        BlockStreamInfo.newBuilder()
                                .creationSoftwareVersion(
                                        SemanticVersion.newBuilder().major(1).build())
                                .lastIntervalProcessTime(new Timestamp(1234567, 890))
                                .build(),
                        CREATION_VERSION));
    }

    @Test
    void classifiesNonGenesisBlockOfSameVersionWithWorkNotDoneStillHasPostUpgradeWork() {
        assertEquals(
                POST_UPGRADE_WORK,
                classifyPendingWork(
                        BlockStreamInfo.newBuilder()
                                .creationSoftwareVersion(CREATION_VERSION)
                                .lastIntervalProcessTime(new Timestamp(1234567, 890))
                                .build(),
                        CREATION_VERSION));
    }

    @Test
    void classifiesNonGenesisBlockOfSameVersionWithWorkDoneAsNoWork() {
        assertSame(
                NONE,
                classifyPendingWork(
                        BlockStreamInfo.newBuilder()
                                .postUpgradeWorkDone(true)
                                .creationSoftwareVersion(CREATION_VERSION)
                                .lastIntervalProcessTime(new Timestamp(1234567, 890))
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
                boundaryStateChangeListener,
                hashInfo,
                SemanticVersion.DEFAULT,
                TEST_PLATFORM_STATE_FACADE);
        assertSame(Instant.EPOCH, subject.lastIntervalProcessTime());
        subject.setLastIntervalProcessTime(CONSENSUS_NOW);
        assertEquals(CONSENSUS_NOW, subject.lastIntervalProcessTime());

        assertSame(Instant.EPOCH, subject.lastHandleTime());
        subject.setLastHandleTime(CONSENSUS_NOW);
        assertEquals(CONSENSUS_NOW, subject.lastHandleTime());
    }

    @Test
    void requiresLastHashToBeInitialized() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(DEFAULT_CONFIG, 1));
        subject = new BlockStreamManagerImpl(
                blockHashSigner,
                () -> aWriter,
                ForkJoinPool.commonPool(),
                configProvider,
                boundaryStateChangeListener,
                hashInfo,
                SemanticVersion.DEFAULT,
                TEST_PLATFORM_STATE_FACADE);
        assertThrows(IllegalStateException.class, () -> subject.startRound(round, state));
    }

    @Test
    void startsAndEndsBlockWithSingleRoundPerBlockAsExpected() throws ParseException {
        givenSubjectWith(
                1,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        givenEndOfRoundSetup();
        given(boundaryStateChangeListener.boundaryTimestampOrThrow()).willReturn(Timestamp.DEFAULT);
        given(round.getRoundNum()).willReturn(ROUND_NO);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);
        assertFalse(subject.hasLedgerId());

        given(blockHashSigner.isReady()).willReturn(true);
        // Start the round that will be block N
        subject.startRound(round, state);
        assertTrue(subject.hasLedgerId());
        assertSame(POST_UPGRADE_WORK, subject.pendingWork());
        subject.confirmPendingWorkFinished();
        assertSame(NONE, subject.pendingWork());
        // We don't fail hard on duplicate calls to confirm post-upgrade work
        assertDoesNotThrow(() -> subject.confirmPendingWorkFinished());

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        verify(boundaryStateChangeListener).setBoundaryTimestamp(CONSENSUS_NOW);
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertNull(subject.prngSeed());
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.setRoundFirstUserTransactionTime(CONSENSUS_NOW);
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
                4,
                List.of(
                        Bytes.EMPTY,
                        Bytes.EMPTY,
                        Bytes.fromHex(
                                "cf1343eb8811fc4ccbd468b9703d60272894c91d1972efeb2d77d2e9d82598659feaf09b7c6bf0f1c3e0fcf4a4f08f48")),
                Timestamp.DEFAULT,
                true,
                SemanticVersion.DEFAULT,
                CONSENSUS_THEN,
                BlockRecordService.EPOCH);
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
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        given(round.getRoundNum()).willReturn(ROUND_NO);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        verify(boundaryStateChangeListener).setBoundaryTimestamp(CONSENSUS_NOW);
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertNull(subject.prngSeed());
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);

        // End the round (which cannot close the block since signer isn't ready)
        subject.endRound(state, ROUND_NO);

        verify(blockHashSigner, never()).signFuture(any());
    }

    @Test
    void blockWithNoUserTransactionsHasExpectedHeader() {
        givenSubjectWith(
                1,
                blockStreamInfoWith(
                        Bytes.EMPTY, CREATION_VERSION.copyBuilder().patch(0).build()),
                platformStateWithFreezeTime(null),
                aWriter);
        final AtomicReference<BlockHeader> writtenHeader = new AtomicReference<>();
        givenEndOfRoundSetup(writtenHeader);
        given(boundaryStateChangeListener.boundaryTimestampOrThrow()).willReturn(Timestamp.DEFAULT);
        given(round.getRoundNum()).willReturn(ROUND_NO);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);
        assertFalse(subject.hasLedgerId());

        given(blockHashSigner.isReady()).willReturn(true);
        // Start the round that will be block N
        subject.startRound(round, state);
        assertTrue(subject.hasLedgerId());
        assertSame(POST_UPGRADE_WORK, subject.pendingWork());
        subject.confirmPendingWorkFinished();
        assertSame(NONE, subject.pendingWork());
        // We don't fail hard on duplicate calls to confirm post-upgrade work
        assertDoesNotThrow(() -> subject.confirmPendingWorkFinished());

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        verify(boundaryStateChangeListener).setBoundaryTimestamp(CONSENSUS_NOW);
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertNull(subject.prngSeed());
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
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
        assertFalse(header.hasFirstTransactionConsensusTime());
    }

    @Test
    void doesNotEndBlockWithMultipleRoundPerBlockIfNotModZero() {
        givenSubjectWith(
                7, blockStreamInfoWith(Bytes.EMPTY, CREATION_VERSION), platformStateWithFreezeTime(null), aWriter);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);

        // Assert the internal state of the subject has changed as expected
        verify(boundaryStateChangeListener).setBoundaryTimestamp(CONSENSUS_NOW);
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));

        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);

        // End the round
        subject.endRound(state, ROUND_NO);

        // Assert the internal state of the subject has changed as expected and the writer has been closed
        verify(blockHashSigner, times(3)).isReady();
        verifyNoMoreInteractions(blockHashSigner);
    }

    @Test
    void alwaysEndsBlockOnFreezeRoundPerBlockAsExpected() throws ParseException {
        final var resultHashes = Bytes.fromHex("aa".repeat(48) + "bb".repeat(48) + "cc".repeat(48) + "dd".repeat(48));
        givenSubjectWith(
                7,
                blockStreamInfoWith(resultHashes, CREATION_VERSION),
                platformStateWithFreezeTime(CONSENSUS_NOW.minusSeconds(1)),
                aWriter);
        givenEndOfRoundSetup();
        given(round.getRoundNum()).willReturn(ROUND_NO);
        given(boundaryStateChangeListener.boundaryTimestampOrThrow()).willReturn(Timestamp.DEFAULT);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        given(blockHashSigner.isReady()).willReturn(true);
        // Start the round that will be block N
        subject.startRound(round, state);

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        verify(boundaryStateChangeListener).setBoundaryTimestamp(CONSENSUS_NOW);
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
        assertEquals(Bytes.fromHex("aa".repeat(48)), subject.prngSeed());
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.setRoundFirstUserTransactionTime(CONSENSUS_NOW);
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
                4,
                List.of(
                        Bytes.EMPTY,
                        Bytes.EMPTY,
                        Bytes.fromHex(
                                "cf1343eb8811fc4ccbd468b9703d60272894c91d1972efeb2d77d2e9d82598659feaf09b7c6bf0f1c3e0fcf4a4f08f48")),
                Timestamp.DEFAULT,
                false,
                SemanticVersion.DEFAULT,
                CONSENSUS_THEN,
                BlockRecordService.EPOCH);
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
        givenSubjectWith(
                1,
                blockStreamInfoWith(Bytes.EMPTY, CREATION_VERSION),
                platformStateWithFreezeTime(null),
                aWriter,
                bWriter);
        givenEndOfRoundSetup();
        doAnswer(invocationOnMock -> {
                    lastBItem.set(invocationOnMock.getArgument(0));
                    return bWriter;
                })
                .when(bWriter)
                .writePbjItem(any());
        given(round.getRoundNum()).willReturn(ROUND_NO);
        given(boundaryStateChangeListener.boundaryTimestampOrThrow()).willReturn(Timestamp.DEFAULT);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);
        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
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
        given(notification.round()).willReturn(ROUND_NO);
        given(notification.hash()).willReturn(FAKE_START_OF_BLOCK_STATE_HASH);
        // Notify the subject of the required start-of-state hash
        subject.notify(notification);
        subject.startRound(round, state);
        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
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
        assertEquals(2, aProof.siblingHashes().size());
        // And the proof for N+1 using a direct proof
        final var bProofItem = lastBItem.get();
        assertNotNull(bProofItem);
        final var bItem = BlockItem.PROTOBUF.parse(bProofItem);
        assertTrue(bItem.hasBlockProof());
        final var bProof = bItem.blockProofOrThrow();
        assertEquals(N_BLOCK_NO + 1, bProof.block());
        assertEquals(FIRST_FAKE_SIGNATURE, bProof.blockSignature());
        assertTrue(bProof.siblingHashes().isEmpty());
    }

    private void givenSubjectWith(
            final int roundsPerBlock,
            @NonNull final BlockStreamInfo blockStreamInfo,
            @NonNull final PlatformState platformState,
            @NonNull final BlockItemWriter... writers) {
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);
        given(round.iterator())
                .willAnswer(invocationOnMock -> List.of(consensusEvent).iterator());
        given(consensusEvent.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);
        final AtomicInteger nextWriter = new AtomicInteger(0);
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.roundsPerBlock", roundsPerBlock)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));
        subject = new BlockStreamManagerImpl(
                blockHashSigner,
                () -> writers[nextWriter.getAndIncrement()],
                ForkJoinPool.commonPool(),
                configProvider,
                boundaryStateChangeListener,
                hashInfo,
                SemanticVersion.DEFAULT,
                TEST_PLATFORM_STATE_FACADE);
        given(state.getReadableStates(BlockStreamService.NAME)).willReturn(readableStates);
        given(state.getReadableStates(PlatformStateService.NAME)).willReturn(readableStates);
        infoRef.set(blockStreamInfo);
        stateRef.set(platformState);
        blockStreamInfoState = new WritableSingletonStateBase<>(BLOCK_STREAM_INFO_KEY, infoRef::get, infoRef::set);
        given(readableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_KEY))
                .willReturn(blockStreamInfoState);
        given(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_KEY))
                .willReturn(new WritableSingletonStateBase<>(PLATFORM_STATE_KEY, stateRef::get, stateRef::set));
    }

    private void givenEndOfRoundSetup() {
        givenEndOfRoundSetup(null);
    }

    private void givenEndOfRoundSetup(@Nullable final AtomicReference<BlockHeader> headerRef) {
        given(boundaryStateChangeListener.flushChanges()).willReturn(FAKE_STATE_CHANGES);
        doAnswer(invocationOnMock -> {
                    lastAItem.set(invocationOnMock.getArgument(0));
                    if (headerRef != null) {
                        final var item = BlockItem.PROTOBUF.parse(lastAItem.get());
                        if (item.hasBlockHeader()) {
                            headerRef.set(item.blockHeaderOrThrow());
                        }
                    }
                    return aWriter;
                })
                .when(aWriter)
                .writePbjItem(any());
        given(state.getWritableStates(BlockStreamService.NAME)).willReturn(writableStates);
        given(writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_KEY))
                .willReturn(blockStreamInfoState);
        doAnswer(invocationOnMock -> {
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
