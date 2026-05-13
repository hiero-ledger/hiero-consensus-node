// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.schemas;

import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0740BlockStreamSchemaTest {
    private static final Bytes WRAPPED_HASH = Bytes.wrap(new byte[HASH_SIZE]);
    private static final Bytes HASH_A = Bytes.fromHex("aa".repeat(HASH_SIZE));
    private static final Bytes HASH_B = Bytes.fromHex("bb".repeat(HASH_SIZE));
    private static final Bytes HASH_C = Bytes.fromHex("cc".repeat(HASH_SIZE));
    private static final Bytes HASH_D = Bytes.fromHex("dd".repeat(HASH_SIZE));

    @Mock
    private MigrationContext<SemanticVersion> ctx;

    @TempDir
    Path tempDir;

    private V0740BlockStreamSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0740BlockStreamSchema();
    }

    @Test
    void versionIsV0740() {
        assertEquals(new SemanticVersion(0, 74, 0, "", ""), subject.getVersion());
    }

    @Test
    void skipsOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verify(ctx, never()).newStates();
    }

    @Test
    void skipsWhenEnableCutoverIsFalse() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWith(false));

        subject.restart(ctx);

        verify(ctx, never()).newStates();
    }

    @Test
    void skipsWhenBlockInfoIsNull() {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWith(true));
        given(ctx.sharedValues()).willReturn(new HashMap<>());

        subject.restart(ctx);

        verify(ctx, never()).newStates();
    }

    @Test
    void skipsWhenCutoverAlreadyExecuted() {
        final var blockInfo =
                validBlockInfo().copyBuilder().previewStreamOverwritten(true).build();

        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWith(true));
        given(ctx.sharedValues()).willReturn(sharedValuesWithBlockInfo(blockInfo));

        subject.restart(ctx);

        verify(ctx, never()).newStates();
    }

    @Test
    void throwsWhenRunningHashesMissingFromSharedValues() {
        final var sharedValues = new HashMap<String, Object>();
        sharedValues.put("SHARED_BLOCK_RECORD_INFO", validBlockInfo());

        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWithBlockDir(true));
        given(ctx.sharedValues()).willReturn(sharedValues);

        assertThrows(NullPointerException.class, () -> subject.restart(ctx));
        verify(ctx, never()).newStates();
    }

    @Test
    void deletesPreviewBlockFilesWithoutMutatingState() throws IOException {
        final var blockDir = tempDir.resolve("blocks");
        Files.createDirectories(blockDir);
        final var subdir = blockDir.resolve("000000000000042");
        Files.createDirectories(subdir);
        Files.createFile(subdir.resolve("block-42.blk.gz"));
        Files.createFile(subdir.resolve("block-42.mf"));
        Files.createFile(subdir.resolve("pending.pnd.gz"));
        Files.createFile(subdir.resolve("proof.pnd.json"));
        Files.createFile(subdir.resolve("readme.txt"));

        givenCutoverContext(blockDir);

        subject.restart(ctx);

        assertFalse(Files.exists(subdir.resolve("block-42.blk.gz")));
        assertFalse(Files.exists(subdir.resolve("block-42.mf")));
        assertFalse(Files.exists(subdir.resolve("pending.pnd.gz")));
        assertFalse(Files.exists(subdir.resolve("proof.pnd.json")));
        assertTrue(Files.exists(subdir.resolve("readme.txt")));
        verify(ctx, never()).newStates();
    }

    @Test
    void toleratesMissingBlockDirectoryWithoutMutatingState() {
        givenCutoverContext(tempDir.resolve("nonexistent-blocks"));

        assertDoesNotThrow(() -> subject.restart(ctx));

        verify(ctx, never()).newStates();
    }

    @Test
    void ignoresFilesTooDeeplyNested() throws IOException {
        final var blockDir = tempDir.resolve("deep-blocks");
        Files.createDirectories(blockDir);
        final var subdir = blockDir.resolve("000000000000001");
        Files.createDirectories(subdir);
        final var deepDir = subdir.resolve("nested");
        Files.createDirectories(deepDir);
        Files.createFile(deepDir.resolve("block-deep.blk.gz"));

        givenCutoverContext(blockDir);

        subject.restart(ctx);

        assertTrue(Files.exists(deepDir.resolve("block-deep.blk.gz")));
        verify(ctx, never()).newStates();
    }

    private void givenCutoverContext(final Path blockDir) {
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig()).willReturn(configWithBlockDir(true, blockDir));
        given(ctx.sharedValues()).willReturn(fullSharedValues(validBlockInfo(), validRunningHashes()));
    }

    private static BlockInfo validBlockInfo() {
        return BlockInfo.newBuilder()
                .lastBlockNumber(100)
                .blockHashes(Bytes.wrap(new byte[HASH_SIZE * 2]))
                .previousWrappedRecordBlockRootHash(WRAPPED_HASH)
                .wrappedIntermediatePreviousBlockRootHashes(List.of(WRAPPED_HASH))
                .wrappedIntermediateBlockRootsLeafCount(1)
                .firstConsTimeOfCurrentBlock(new Timestamp(1000, 0))
                .lastUsedConsTime(new Timestamp(1001, 0))
                .consTimeOfLastHandledTxn(new Timestamp(1001, 0))
                .lastIntervalProcessTime(new Timestamp(1000, 0))
                .previewStreamOverwritten(false)
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
