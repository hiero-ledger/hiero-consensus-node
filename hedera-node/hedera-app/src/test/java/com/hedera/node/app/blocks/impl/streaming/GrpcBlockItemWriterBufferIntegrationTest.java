// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration test for {@link GrpcBlockItemWriter#flushIncompleteBlock()} against a REAL {@link BlockBufferService}
 * (the in-memory buffer used in {@code writerMode=GRPC}, the production default). The mock-based
 * {@code GrpcBlockItemWriterTest} stubs {@code getBlockState(...)}; this test instead drives the real buffer lifecycle
 * ({@code openBlock} + {@code addItem} via the writer) and verifies the open, unproven block is persisted to disk as a
 * recoverable-by-analysis {@code .iss.gz} artifact — closing the residual gap that only a real-buffer run can cover.
 */
@ExtendWith(MockitoExtension.class)
class GrpcBlockItemWriterBufferIntegrationTest {

    @TempDir
    Path tempDir;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    @Mock
    private BlockStreamMetrics blockStreamMetrics;

    private BlockBufferService blockBufferService;

    @BeforeEach
    void setUp() throws Exception {
        final Configuration config = HederaTestConfigBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.streamMode", "BLOCKS")
                // streamToBlockNodes enables the buffer to accept items (isGrpcStreamingEnabled)
                .withValue("blockStream.streamToBlockNodes", true)
                .withValue("blockStream.blockFileDir", tempDir.toString())
                .withValue("blockStream.buffer.isBufferPersistenceEnabled", false)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1));
        when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(3)
                        .build());

        blockBufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        // Mark the buffer started so it accepts items, without spinning up the real background pruning machinery.
        markStarted(blockBufferService);
    }

    @Test
    void grpcWriterFlushesOpenBlockToIssArtifactFromRealBuffer() throws Exception {
        final long blockNumber = 7L;
        final var writer = new GrpcBlockItemWriter(
                configProvider, selfNodeAccountIdManager, FileSystems.getDefault(), blockBufferService);

        // Drive the real buffer lifecycle: open + write an item (these flow into the real BlockBufferService). We do
        // NOT close the block, mirroring the in-progress block at catastrophic failure.
        writer.openBlock(blockNumber);
        final var item = BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(blockNumber).build())
                .build();
        writer.writePbjItemAndBytes(item, BlockItem.PROTOBUF.toBytes(item));

        writer.flushIncompleteBlock();

        final var baseName = FileBlockItemWriter.longToFileName(blockNumber);
        final var dir = tempDir.resolve("block-0.0.3");
        // The open block is persisted as a .iss.gz triage artifact only: no complete block, no pending block/sidecar.
        assertThat(Files.exists(dir.resolve(baseName + ".iss.gz"))).isTrue();
        assertThat(Files.exists(dir.resolve(baseName + ".blk.gz"))).isFalse();
        assertThat(Files.exists(dir.resolve(baseName + ".pnd.gz"))).isFalse();
        assertThat(Files.exists(dir.resolve(baseName + ".pnd.json"))).isFalse();

        // The block is still OPEN in the real buffer (the writer must not close it, which would make it eligible for
        // the buffer's own persistence/recovery path).
        final BlockState state = blockBufferService.getBlockState(blockNumber);
        assertThat(state).isNotNull();
        assertThat(state.isClosed()).isFalse();

        // The artifact round-trips back to a Block for analysis (BlockBytes is wire-identical to Block).
        final byte[] contents;
        try (final var in = new GZIPInputStream(Files.newInputStream(dir.resolve(baseName + ".iss.gz")))) {
            contents = in.readAllBytes();
        }
        final var parsed = Block.PROTOBUF.parse(Bytes.wrap(contents));
        assertThat(parsed.items()).hasSize(1);
        assertThat(parsed.items().getFirst().blockHeader().number()).isEqualTo(blockNumber);
    }

    private static void markStarted(final BlockBufferService svc) throws Exception {
        final var field = BlockBufferService.class.getDeclaredField("isStarted");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(svc)).set(true);
    }
}
