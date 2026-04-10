// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.schemas;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0750BlockStreamSchemaTest {

    private static final Bytes WRAPPED_HASH = Bytes.wrap(new byte[HASH_SIZE]);
    private static final Bytes HASH_A = Bytes.fromHex("aa".repeat(HASH_SIZE));
    private static final Bytes HASH_B = Bytes.fromHex("bb".repeat(HASH_SIZE));
    private static final Bytes HASH_C = Bytes.fromHex("cc".repeat(HASH_SIZE));
    private static final Bytes HASH_D = Bytes.fromHex("dd".repeat(HASH_SIZE));

    @Mock
    private MigrationContext<SemanticVersion> ctx;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<BlockStreamInfo> blockStreamInfoState;

    @TempDir
    Path tempDir;

    private final AtomicBoolean markerCalled = new AtomicBoolean(false);
    private V0750BlockStreamSchema subject;

    @BeforeEach
    void setUp() {
        markerCalled.set(false);
        subject = new V0750BlockStreamSchema(() -> markerCalled.set(true));
    }

    @Test
    void versionIsV0730() {
        assertEquals(new SemanticVersion(0, 73, 0, "", ""), subject.getVersion());
    }

    @Test
    void skipsOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        assertFalse(markerCalled.get());
        verifyNoInteractions(writableStates);
    }

    @Test
    void skipsWhenEnableCutoverIsFalse() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWith(false));

        subject.restart(ctx);

        assertFalse(markerCalled.get());
        verifyNoInteractions(writableStates);
    }

    @Test
    void skipsWhenBlockInfoIsNull() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWith(true));
        given(ctx.sharedValues()).willReturn(new HashMap<>());

        subject.restart(ctx);

        assertFalse(markerCalled.get());
        verifyNoInteractions(writableStates);
    }

    @Test
    void skipsWhenCutoverAlreadyExecuted() {
        final var blockInfo = BlockInfo.newBuilder()
                .lastBlockNumber(100)
                .cutoverExecuted(true)
                .build();

        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWith(true));
        given(ctx.sharedValues()).willReturn(sharedValuesWithBlockInfo(blockInfo));

        subject.restart(ctx);

        assertFalse(markerCalled.get());
        verifyNoInteractions(writableStates);
    }

    @Test
    void throwsWhenBlockHashesTooShort() {
        final var blockInfo = BlockInfo.newBuilder()
                .lastBlockNumber(100)
                .blockHashes(Bytes.EMPTY) // no hashes at all
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of(WRAPPED_HASH))
                .wrappedIntermediateBlockRootsLeafCount(1)
                .firstConsTimeOfCurrentBlock(new Timestamp(1000, 0))
                .lastUsedConsTime(new Timestamp(1001, 0))
                .consTimeOfLastHandledTxn(new Timestamp(1001, 0))
                .lastIntervalProcessTime(new Timestamp(1000, 0))
                .cutoverExecuted(false)
                .build();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi);

        final var ex = assertThrows(IllegalStateException.class, () -> subject.restart(ctx));
        assertTrue(ex.getMessage().contains("at least one record block hash"));
        assertFalse(markerCalled.get());
    }

    @Test
    void throwsWhenRunningHashesMissingFromSharedValues() {
        final var blockInfo = validBlockInfo();
        final Map<String, Object> sharedValues = new HashMap<>();
        sharedValues.put("SHARED_BLOCK_RECORD_INFO", blockInfo);
        // SHARED_RUNNING_HASHES intentionally missing

        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWithBlockDir(true));
        given(ctx.sharedValues()).willReturn(sharedValues);

        assertThrows(NullPointerException.class, () -> subject.restart(ctx));
        assertFalse(markerCalled.get());
    }

    @Test
    void throwsWhenBlockStreamInfoStateIsNull() {
        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();

        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWithBlockDir(true));
        given(ctx.sharedValues()).willReturn(fullSharedValues(blockInfo, runningHashes));
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .willReturn(blockStreamInfoState);
        given(blockStreamInfoState.get()).willReturn(null);

        assertThrows(NullPointerException.class, () -> subject.restart(ctx));
        assertFalse(markerCalled.get());
    }

    @Test
    void executesCutoverAndSignalsMarker() {
        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi);

        subject.restart(ctx);

        assertTrue(markerCalled.get());

        // Verify the written BlockStreamInfo
        final var captor = ArgumentCaptor.forClass(BlockStreamInfo.class);
        verify(blockStreamInfoState).put(captor.capture());
        final var written = captor.getValue();

        assertEquals(blockInfo.lastBlockNumber(), written.blockNumber());
        assertEquals(blockInfo.firstConsTimeOfCurrentBlock(), written.blockTime());
        assertEquals(blockInfo.lastUsedConsTime(), written.blockEndTime());
        assertEquals(blockInfo.lastIntervalProcessTime(), written.lastIntervalProcessTime());
        assertEquals(blockInfo.consTimeOfLastHandledTxn(), written.lastHandleTime());
        assertEquals(
                blockInfo.wrappedIntermediatePreviousBlockRootHashes(), written.intermediatePreviousBlockRootHashes());
        assertEquals(blockInfo.wrappedIntermediateBlockRootsLeafCount(), written.intermediateBlockRootsLeafCount());

        // Trailing block hashes should be blockHashes minus last HASH_SIZE
        final var fullHashes = blockInfo.blockHashes().toByteArray();
        final var expectedTrailing = Bytes.wrap(fullHashes, 0, fullHashes.length - HASH_SIZE);
        assertEquals(expectedTrailing, written.trailingBlockHashes());

        // Tree hashes should be zeroed
        assertEquals(BlockStreamManager.HASH_OF_ZERO, written.inputTreeRootHash());
        assertEquals(BlockStreamManager.HASH_OF_ZERO, written.consensusHeaderRootHash());
        assertEquals(BlockStreamManager.HASH_OF_ZERO, written.traceDataRootHash());
        assertEquals(0, written.numPrecedingStateChangesItems());
        assertEquals(List.of(), written.rightmostPrecedingStateChangesTreeHashes());
    }

    @Test
    void deletesPreviewBlockFiles() throws IOException {
        final var blockDir = tempDir.resolve("blocks");
        Files.createDirectories(blockDir);
        final var subdir = blockDir.resolve("000000000000042");
        Files.createDirectories(subdir);
        Files.createFile(subdir.resolve("block-42.blk.gz"));
        Files.createFile(subdir.resolve("block-42.mf"));
        Files.createFile(subdir.resolve("pending.pnd.gz"));
        Files.createFile(subdir.resolve("proof.pnd.json"));
        // Non-matching file should survive
        Files.createFile(subdir.resolve("readme.txt"));

        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi, blockDir);

        subject.restart(ctx);

        assertTrue(markerCalled.get());
        assertFalse(Files.exists(subdir.resolve("block-42.blk.gz")));
        assertFalse(Files.exists(subdir.resolve("block-42.mf")));
        assertFalse(Files.exists(subdir.resolve("pending.pnd.gz")));
        assertFalse(Files.exists(subdir.resolve("proof.pnd.json")));
        assertTrue(Files.exists(subdir.resolve("readme.txt")));
    }

    @Test
    void toleratesMissingBlockDirectory() {
        final var blockDir = tempDir.resolve("nonexistent-blocks");

        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi, blockDir);

        assertDoesNotThrow(() -> subject.restart(ctx));
        assertTrue(markerCalled.get());
        verify(blockStreamInfoState)
                .put(ArgumentCaptor.forClass(BlockStreamInfo.class).capture());
    }

    @Test
    void markerNotCalledOnSkip() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWith(false));

        subject.restart(ctx);

        assertFalse(markerCalled.get());
        verify(blockStreamInfoState, never())
                .put(ArgumentCaptor.forClass(BlockStreamInfo.class).capture());
    }

    @Test
    void trailingOutputHashesAssembledInCorrectOrder() {
        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi);

        subject.restart(ctx);

        final var captor = ArgumentCaptor.forClass(BlockStreamInfo.class);
        verify(blockStreamInfoState).put(captor.capture());
        final var written = captor.getValue();

        // The implementation appends in order: nMinus3, nMinus2, nMinus1, running
        // So the output should be [HASH_D | HASH_C | HASH_B | HASH_A]
        // (RunningHashes constructor is (runningHash=A, nMinus1=B, nMinus2=C, nMinus3=D))
        final var outputBytes = written.trailingOutputHashes().toByteArray();
        assertEquals(HASH_SIZE * 4, outputBytes.length);

        final var expectedOutput = new byte[HASH_SIZE * 4];
        HASH_D.getBytes(0, expectedOutput, 0, HASH_SIZE);
        HASH_C.getBytes(0, expectedOutput, HASH_SIZE, HASH_SIZE);
        HASH_B.getBytes(0, expectedOutput, HASH_SIZE * 2, HASH_SIZE);
        HASH_A.getBytes(0, expectedOutput, HASH_SIZE * 3, HASH_SIZE);
        assertEquals(Bytes.wrap(expectedOutput), written.trailingOutputHashes());
    }

    @Test
    void preservesStartOfBlockStateHashFromPreviewBsi() {
        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var stateHash = Bytes.fromHex("ef".repeat(HASH_SIZE));
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(stateHash)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi);

        subject.restart(ctx);

        final var captor = ArgumentCaptor.forClass(BlockStreamInfo.class);
        verify(blockStreamInfoState).put(captor.capture());
        final var written = captor.getValue();

        assertEquals(stateHash, written.startOfBlockStateHash());
    }

    @Test
    void trailingBlockHashesEmptyWhenBlockHashesExactlyOneHash() {
        // When blockHashes is exactly HASH_SIZE, the trailing should be empty
        // (the single hash is the "last" one that gets trimmed)
        final var singleHash = new byte[HASH_SIZE];
        final var blockInfo = BlockInfo.newBuilder()
                .lastBlockNumber(1)
                .blockHashes(Bytes.wrap(singleHash))
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of(WRAPPED_HASH))
                .wrappedIntermediateBlockRootsLeafCount(1)
                .firstConsTimeOfCurrentBlock(new Timestamp(500, 0))
                .lastUsedConsTime(new Timestamp(501, 0))
                .consTimeOfLastHandledTxn(new Timestamp(501, 0))
                .lastIntervalProcessTime(new Timestamp(500, 0))
                .cutoverExecuted(false)
                .build();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(0)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi);

        subject.restart(ctx);

        final var captor = ArgumentCaptor.forClass(BlockStreamInfo.class);
        verify(blockStreamInfoState).put(captor.capture());
        final var written = captor.getValue();

        assertEquals(Bytes.EMPTY, written.trailingBlockHashes());
        assertTrue(markerCalled.get());
    }

    @Test
    void trailingBlockHashesCorrectWithManyHashes() {
        // 5 hashes total; trailing should contain the first 4
        final var fiveHashes = new byte[HASH_SIZE * 5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < HASH_SIZE; j++) {
                fiveHashes[i * HASH_SIZE + j] = (byte) (i + 1);
            }
        }
        final var blockInfo = BlockInfo.newBuilder()
                .lastBlockNumber(200)
                .blockHashes(Bytes.wrap(fiveHashes))
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of(WRAPPED_HASH))
                .wrappedIntermediateBlockRootsLeafCount(1)
                .firstConsTimeOfCurrentBlock(new Timestamp(2000, 0))
                .lastUsedConsTime(new Timestamp(2001, 0))
                .consTimeOfLastHandledTxn(new Timestamp(2001, 0))
                .lastIntervalProcessTime(new Timestamp(2000, 0))
                .cutoverExecuted(false)
                .build();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi);

        subject.restart(ctx);

        final var captor = ArgumentCaptor.forClass(BlockStreamInfo.class);
        verify(blockStreamInfoState).put(captor.capture());
        final var written = captor.getValue();

        // First 4 hashes should be in trailing, 5th (last) trimmed
        final var expectedTrailing = Bytes.wrap(fiveHashes, 0, HASH_SIZE * 4);
        assertEquals(expectedTrailing, written.trailingBlockHashes());
    }

    @Test
    void throwsWhenBlockHashesShorterThanOneHash() {
        // Fewer bytes than a single hash should fail the guard
        final var partialHash = new byte[HASH_SIZE - 1];
        final var blockInfo = BlockInfo.newBuilder()
                .lastBlockNumber(100)
                .blockHashes(Bytes.wrap(partialHash))
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of(WRAPPED_HASH))
                .wrappedIntermediateBlockRootsLeafCount(1)
                .firstConsTimeOfCurrentBlock(new Timestamp(1000, 0))
                .lastUsedConsTime(new Timestamp(1001, 0))
                .consTimeOfLastHandledTxn(new Timestamp(1001, 0))
                .lastIntervalProcessTime(new Timestamp(1000, 0))
                .cutoverExecuted(false)
                .build();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi);

        final var ex = assertThrows(IllegalStateException.class, () -> subject.restart(ctx));
        assertTrue(ex.getMessage().contains("at least one record block hash"));
        assertFalse(markerCalled.get());
    }

    @Test
    void copiesMultipleIntermediatePreviousBlockRootHashes() {
        final var hashE = Bytes.fromHex("ee".repeat(HASH_SIZE));
        final var hashF = Bytes.fromHex("ff".repeat(HASH_SIZE));
        final var intermediateHashes = List.of(WRAPPED_HASH, hashE, hashF);
        final var twoHashes = new byte[HASH_SIZE * 2];
        final var blockInfo = BlockInfo.newBuilder()
                .lastBlockNumber(300)
                .blockHashes(Bytes.wrap(twoHashes))
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(intermediateHashes)
                .wrappedIntermediateBlockRootsLeafCount(7)
                .firstConsTimeOfCurrentBlock(new Timestamp(3000, 0))
                .lastUsedConsTime(new Timestamp(3001, 0))
                .consTimeOfLastHandledTxn(new Timestamp(3001, 0))
                .lastIntervalProcessTime(new Timestamp(3000, 0))
                .cutoverExecuted(false)
                .build();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi);

        subject.restart(ctx);

        final var captor = ArgumentCaptor.forClass(BlockStreamInfo.class);
        verify(blockStreamInfoState).put(captor.capture());
        final var written = captor.getValue();

        assertEquals(intermediateHashes, written.intermediatePreviousBlockRootHashes());
        assertEquals(7, written.intermediateBlockRootsLeafCount());
    }

    @Test
    void copiesEmptyIntermediatePreviousBlockRootHashes() {
        final var twoHashes = new byte[HASH_SIZE * 2];
        final var blockInfo = BlockInfo.newBuilder()
                .lastBlockNumber(300)
                .blockHashes(Bytes.wrap(twoHashes))
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of())
                .wrappedIntermediateBlockRootsLeafCount(0)
                .firstConsTimeOfCurrentBlock(new Timestamp(3000, 0))
                .lastUsedConsTime(new Timestamp(3001, 0))
                .consTimeOfLastHandledTxn(new Timestamp(3001, 0))
                .lastIntervalProcessTime(new Timestamp(3000, 0))
                .cutoverExecuted(false)
                .build();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi);

        subject.restart(ctx);

        final var captor = ArgumentCaptor.forClass(BlockStreamInfo.class);
        verify(blockStreamInfoState).put(captor.capture());
        final var written = captor.getValue();

        assertEquals(List.of(), written.intermediatePreviousBlockRootHashes());
        assertEquals(0, written.intermediateBlockRootsLeafCount());
    }

    @Test
    void deletesBlockFilesInMultipleSubdirectories() throws IOException {
        final var blockDir = tempDir.resolve("multi-blocks");
        Files.createDirectories(blockDir);
        final var subdir1 = blockDir.resolve("000000000000001");
        Files.createDirectories(subdir1);
        Files.createFile(subdir1.resolve("block-1.blk.gz"));
        final var subdir2 = blockDir.resolve("000000000000002");
        Files.createDirectories(subdir2);
        Files.createFile(subdir2.resolve("block-2.blk.gz"));
        Files.createFile(subdir2.resolve("block-2.mf"));

        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi, blockDir);

        subject.restart(ctx);

        assertFalse(Files.exists(subdir1.resolve("block-1.blk.gz")));
        assertFalse(Files.exists(subdir2.resolve("block-2.blk.gz")));
        assertFalse(Files.exists(subdir2.resolve("block-2.mf")));
        // Directories themselves should remain
        assertTrue(Files.exists(subdir1));
        assertTrue(Files.exists(subdir2));
    }

    @Test
    void ignoresFilesTooDeeplyNested() throws IOException {
        // Files.walk depth is 2, so files at depth 3 should not be deleted
        final var blockDir = tempDir.resolve("deep-blocks");
        Files.createDirectories(blockDir);
        final var subdir = blockDir.resolve("000000000000001");
        Files.createDirectories(subdir);
        final var deepDir = subdir.resolve("nested");
        Files.createDirectories(deepDir);
        Files.createFile(deepDir.resolve("block-deep.blk.gz"));

        final var blockInfo = validBlockInfo();
        final var runningHashes = validRunningHashes();
        final var previewBsi = BlockStreamInfo.newBuilder()
                .blockNumber(50)
                .startOfBlockStateHash(BlockStreamManager.HASH_OF_ZERO)
                .build();

        givenCutoverContext(blockInfo, runningHashes, previewBsi, blockDir);

        subject.restart(ctx);

        assertTrue(Files.exists(deepDir.resolve("block-deep.blk.gz")));
    }

    private void givenCutoverContext(
            final BlockInfo blockInfo, final RunningHashes runningHashes, final BlockStreamInfo previewBsi) {
        givenCutoverContext(blockInfo, runningHashes, previewBsi, tempDir.resolve("default-blocks"));
    }

    private void givenCutoverContext(
            final BlockInfo blockInfo,
            final RunningHashes runningHashes,
            final BlockStreamInfo previewBsi,
            final Path blockDir) {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWithBlockDir(true, blockDir));
        given(ctx.sharedValues()).willReturn(fullSharedValues(blockInfo, runningHashes));
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID))
                .willReturn(blockStreamInfoState);
        given(blockStreamInfoState.get()).willReturn(previewBsi);
    }

    private static BlockInfo validBlockInfo() {
        // blockHashes needs at least 2*HASH_SIZE bytes (cutover trims the last HASH_SIZE)
        final var twoHashes = new byte[HASH_SIZE * 2];
        return BlockInfo.newBuilder()
                .lastBlockNumber(100)
                .blockHashes(Bytes.wrap(twoHashes))
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of(WRAPPED_HASH))
                .wrappedIntermediateBlockRootsLeafCount(1)
                .firstConsTimeOfCurrentBlock(new Timestamp(1000, 0))
                .lastUsedConsTime(new Timestamp(1001, 0))
                .consTimeOfLastHandledTxn(new Timestamp(1001, 0))
                .lastIntervalProcessTime(new Timestamp(1000, 0))
                .cutoverExecuted(false)
                .build();
    }

    private static RunningHashes validRunningHashes() {
        return new RunningHashes(HASH_A, HASH_B, HASH_C, HASH_D);
    }

    private static Map<String, Object> sharedValuesWithBlockInfo(final BlockInfo blockInfo) {
        final var map = new HashMap<String, Object>();
        map.put("SHARED_BLOCK_RECORD_INFO", blockInfo);
        return map;
    }

    private static Map<String, Object> fullSharedValues(final BlockInfo blockInfo, final RunningHashes runningHashes) {
        final var map = new HashMap<String, Object>();
        map.put("SHARED_BLOCK_RECORD_INFO", blockInfo);
        map.put("SHARED_RUNNING_HASHES", runningHashes);
        return map;
    }

    private Configuration configWith(final boolean enableCutover) {
        return HederaTestConfigBuilder.create()
                .withValue("blockStream.enableCutover", enableCutover)
                .getOrCreateConfig();
    }

    private Configuration configWithBlockDir(final boolean enableCutover) {
        return configWithBlockDir(enableCutover, tempDir.resolve("default-blocks"));
    }

    private Configuration configWithBlockDir(final boolean enableCutover, final Path blockDir) {
        return HederaTestConfigBuilder.create()
                .withValue("blockStream.enableCutover", enableCutover)
                .withValue("blockStream.blockFileDir", blockDir.toString())
                .getOrCreateConfig();
    }
}
