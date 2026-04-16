// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_LABEL;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_LABEL;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.quiescence.QuiescedHeartbeat;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockOpeningTest {

    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private State state;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private BlockRecordStreamProducer streamFileProducer;

    @Mock
    private QuiescenceController quiescenceController;

    @Mock
    private QuiescedHeartbeat quiescedHeartbeat;

    @Mock
    private Platform platform;

    @Mock
    private WrappedRecordFileBlockHashesDiskWriter wrappedRecordHashesDiskWriter;

    private BlockRecordManagerImpl subject;

    @Test
    void roundStartOpensBlockFromClosedBoundary() {
        given(streamFileProducer.getRunningHash()).willReturn(Bytes.wrap(new byte[48]));
        setupPersistentState(baseBlockInfo().build());

        subject.startRound(CONSENSUS_NOW, state);

        assertTrue(subject.willOpenNewBlock(CONSENSUS_NOW, state));
        assertEquals(asTimestamp(CONSENSUS_NOW), subject.blockTimestamp());
        assertEquals(null, readBlockInfo().blockTime());
        verify(streamFileProducer).switchBlocks(-1, 0, CONSENSUS_NOW);
    }

    @Test
    void roundStartDoesNotOpenAnotherBlockWhenOneIsAlreadyOpen() {
        given(streamFileProducer.getRunningHash()).willReturn(Bytes.wrap(new byte[48]));
        setupPersistentState(baseBlockInfo().build());

        subject.startRound(CONSENSUS_NOW, state);

        subject.startRound(CONSENSUS_NOW.plusSeconds(1), state);

        assertFalse(subject.willOpenNewBlock(CONSENSUS_NOW.plusSeconds(1), state));
        verify(streamFileProducer).switchBlocks(-1, 0, CONSENSUS_NOW);
        verify(streamFileProducer, never()).switchBlocks(anyLong(), anyLong(), eq(CONSENSUS_NOW.plusSeconds(1)));
    }

    @Test
    void restartOpensNextBlockFromPersistedClosedBoundaryWithoutReconstructingCloseData() {
        final var lastHandledTime = new Timestamp(10, 2);
        final var priorBlockHashBytes = new byte[48];
        priorBlockHashBytes[0] = 0x22;
        final var priorBlockHash = Bytes.wrap(priorBlockHashBytes);
        final var runningHash = Bytes.wrap(new byte[48]);
        setupPersistentState(
                baseBlockInfo()
                        .lastBlockNumber(0)
                        .firstConsTimeOfLastBlock(new Timestamp(10, 1))
                        .blockHashes(priorBlockHash)
                        .consTimeOfLastHandledTxn(lastHandledTime)
                        .lastUsedConsTime(lastHandledTime)
                        .build(),
                RunningHashes.newBuilder().runningHash(runningHash).build());

        subject.startRound(CONSENSUS_NOW, state);

        verify(streamFileProducer).initRunningHash(any());
        verify(streamFileProducer).switchBlocks(0, 1, CONSENSUS_NOW);
        verify(streamFileProducer, never()).getRunningHash();
        assertEquals(asTimestamp(CONSENSUS_NOW), subject.blockTimestamp());
        assertEquals(null, readBlockInfo().blockTime());
    }

    @Test
    void restartRestoresWrappedHashTrackerFromClosedBoundaryState() throws Exception {
        final var lastHandledTime = new Timestamp(10, 2);
        final var seedHasher = new IncrementalStreamingHasher(
                MessageDigest.getInstance(DigestType.SHA_384.algorithmName()), List.of(), 0);
        seedHasher.addLeaf(new byte[] {1, 2, 3});
        final var seedIntermediateHashes = seedHasher.intermediateHashingState();
        final var seedPrevHashBytes = new byte[48];
        seedPrevHashBytes[0] = (byte) 0xCD;
        final var seedPrevHash = Bytes.wrap(seedPrevHashBytes);
        final var priorBlockHashBytes = new byte[48];
        priorBlockHashBytes[0] = 0x22;
        final var priorBlockHash = Bytes.wrap(priorBlockHashBytes);
        final var endRunningHashBytes = new byte[48];
        endRunningHashBytes[0] = 0x33;
        final var endRunningHash = Bytes.wrap(endRunningHashBytes);
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "RECORDS")
                .withValue("hedera.recordStream.liveWritePrevWrappedRecordHashes", true)
                .getOrCreateConfig();
        setupPersistentState(
                config,
                baseBlockInfo()
                        .lastBlockNumber(2)
                        .firstConsTimeOfLastBlock(new Timestamp(8, 0))
                        .blockHashes(priorBlockHash)
                        .consTimeOfLastHandledTxn(lastHandledTime)
                        .lastUsedConsTime(lastHandledTime)
                        .previousWrappedRecordBlockRootHash(seedPrevHash)
                        .wrappedIntermediatePreviousBlockRootHashes(seedIntermediateHashes)
                        .wrappedIntermediateBlockRootsLeafCount(1)
                        .votingComplete(true)
                        .build(),
                RunningHashes.newBuilder().runningHash(endRunningHash).build());

        assertEquals(1L, prevWrappedRecordBlockHashesLeafCountOf(subject));
        assertEquals(seedPrevHash, previousWrappedRecordBlockRootHashOf(subject));
        verify(streamFileProducer, never()).getRunningHash();
    }

    @Test
    void endRoundPersistsClosedBoundaryDirectly() {
        final var currentBlockTime = Instant.ofEpochSecond(10, 1);
        final var lastHandledTime = new Timestamp(10, 2);
        final var priorBlockHashBytes = new byte[48];
        priorBlockHashBytes[0] = 0x22;
        final var priorBlockHash = Bytes.wrap(priorBlockHashBytes);
        final var endRunningHashBytes = new byte[48];
        endRunningHashBytes[0] = 0x33;
        final var endRunningHash = Bytes.wrap(endRunningHashBytes);
        given(streamFileProducer.getRunningHash()).willReturn(endRunningHash);
        setupPersistentState(
                baseBlockInfo()
                        .lastBlockNumber(2)
                        .firstConsTimeOfLastBlock(new Timestamp(8, 0))
                        .blockHashes(priorBlockHash)
                        .consTimeOfLastHandledTxn(lastHandledTime)
                        .lastUsedConsTime(lastHandledTime)
                        .build(),
                RunningHashes.newBuilder().runningHash(priorBlockHash).build());

        subject.startRound(currentBlockTime, state);
        subject.endRound(state, 112, Instant.ofEpochSecond(100, 0));

        assertEquals(3L, readBlockInfo().lastBlockNumber());
        assertEquals(asTimestamp(currentBlockTime), readBlockInfo().firstConsTimeOfLastBlock());
        assertEquals(Timestamp.DEFAULT, readBlockInfo().firstConsTimeOfCurrentBlock());
        assertEquals(null, readBlockInfo().blockTime());
        assertEquals(endRunningHash, readRunningHashes().runningHash());
        verify(streamFileProducer).closeBlock(3);
    }

    @Test
    void maybeQuiesceStartsHeartbeatOnQuiesceCommandChange() {
        setupPersistentState(baseBlockInfo().build());
        given(quiescenceController.getQuiescenceStatus()).willReturn(QUIESCE);

        subject.maybeQuiesce(state);
        subject.maybeQuiesce(state);

        verify(platform, times(1)).quiescenceCommand(QUIESCE);
        verify(quiescedHeartbeat, times(1)).start(any(), any());
    }

    @Test
    void maybeQuiesceDoesNothingWhenCommandRemainsDontQuiesce() {
        setupPersistentState(baseBlockInfo().build());
        given(quiescenceController.getQuiescenceStatus()).willReturn(DONT_QUIESCE);

        subject.maybeQuiesce(state);

        verify(platform, never()).quiescenceCommand(any());
        verify(quiescedHeartbeat, never()).start(any(), any());
    }

    private void setupPersistentState(@NonNull final BlockInfo blockInfo) {
        setupPersistentState(DEFAULT_CONFIG, blockInfo, RunningHashes.DEFAULT);
    }

    private void setupPersistentState(@NonNull final BlockInfo blockInfo, @NonNull final RunningHashes runningHashes) {
        setupPersistentState(DEFAULT_CONFIG, blockInfo, runningHashes);
    }

    private void setupPersistentState(
            @NonNull final com.swirlds.config.api.Configuration config,
            @NonNull final BlockInfo blockInfo,
            @NonNull final RunningHashes runningHashes) {
        final var blockInfoRef = new AtomicReference<>(blockInfo);
        final var runningHashesRef = new AtomicReference<>(runningHashes);
        final var readableStates = new MapReadableStates(Map.of(
                BLOCKS_STATE_ID,
                new FunctionReadableSingletonState<>(BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, blockInfoRef::get),
                RUNNING_HASHES_STATE_ID,
                new FunctionReadableSingletonState<>(
                        RUNNING_HASHES_STATE_ID, RUNNING_HASHES_STATE_LABEL, runningHashesRef::get)));
        final var writableStates = new MapWritableStates(Map.of(
                BLOCKS_STATE_ID,
                new FunctionWritableSingletonState<>(
                        BLOCKS_STATE_ID, BLOCKS_STATE_LABEL, blockInfoRef::get, blockInfoRef::set),
                RUNNING_HASHES_STATE_ID,
                new FunctionWritableSingletonState<>(
                        RUNNING_HASHES_STATE_ID,
                        RUNNING_HASHES_STATE_LABEL,
                        runningHashesRef::get,
                        runningHashesRef::set)));
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(state.getReadableStates(BlockRecordService.NAME)).willReturn(readableStates);
        Mockito.lenient().when(state.getWritableStates(BlockRecordService.NAME)).thenReturn(writableStates);
        subject = new BlockRecordManagerImpl(
                configProvider,
                state,
                streamFileProducer,
                quiescenceController,
                quiescedHeartbeat,
                platform,
                wrappedRecordHashesDiskWriter,
                InitTrigger.RESTART);
    }

    private BlockInfo readBlockInfo() {
        return state.getWritableStates(BlockRecordService.NAME)
                .<BlockInfo>getSingleton(BLOCKS_STATE_ID)
                .get();
    }

    private RunningHashes readRunningHashes() {
        return state.getWritableStates(BlockRecordService.NAME)
                .<RunningHashes>getSingleton(RUNNING_HASHES_STATE_ID)
                .get();
    }

    private BlockInfo.Builder baseBlockInfo() {
        return BlockInfo.newBuilder()
                .lastBlockNumber(-1)
                .firstConsTimeOfLastBlock(Timestamp.DEFAULT)
                .blockHashes(Bytes.EMPTY)
                .consTimeOfLastHandledTxn(Timestamp.DEFAULT)
                .migrationRecordsStreamed(true)
                .firstConsTimeOfCurrentBlock(Timestamp.DEFAULT)
                .lastUsedConsTime(Timestamp.DEFAULT)
                .lastIntervalProcessTime(Timestamp.DEFAULT)
                .previousWrappedRecordBlockRootHash(Bytes.EMPTY)
                .wrappedIntermediatePreviousBlockRootHashes(List.of())
                .wrappedIntermediateBlockRootsLeafCount(0)
                .votingComplete(true);
    }

    private static long prevWrappedRecordBlockHashesLeafCountOf(@NonNull final BlockRecordManagerImpl subject) {
        try {
            final Field field = BlockRecordManagerImpl.class.getDeclaredField("prevWrappedRecordBlockHashes");
            field.setAccessible(true);
            return ((IncrementalStreamingHasher) field.get(subject)).leafCount();
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Bytes previousWrappedRecordBlockRootHashOf(@NonNull final BlockRecordManagerImpl subject) {
        try {
            final Field field = BlockRecordManagerImpl.class.getDeclaredField("previousWrappedRecordBlockRootHash");
            field.setAccessible(true);
            return (Bytes) field.get(subject);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
