// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileAndGrpcBlockItemWriterTest {

    private static final String BLK_ZSTD = "0000000000000000001.blk.zstd";
    private static final String MF = "0000000000000000001.mf";
    private static final String PENDING_PROOF_JSON = "0000000000000000001.pnd.json";
    private static final String OPEN_ZSTD = "0000000000000000001.open.zstd";

    @TempDir
    Path tempDir;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private BlockStreamConfig blockStreamConfig;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    @Mock
    private BlockBufferService blockBufferService;

    @BeforeEach
    void setUp() {
        when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        when(versionedConfiguration.getConfigData(BlockStreamConfig.class)).thenReturn(blockStreamConfig);
        when(blockStreamConfig.blockFileDir()).thenReturn(tempDir.toString());
        lenient().when(blockStreamConfig.blockFileBufferOuterSizeKb()).thenReturn(4096);
        lenient().when(blockStreamConfig.blockFileBufferInnerSizeKb()).thenReturn(1024);
        // Do not forward the normal stream to gRPC, so only the file half is exercised for open/write
        lenient().when(blockStreamConfig.streamToBlockNodes()).thenReturn(false);
        when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder()
                        .shardNum(0)
                        .realmNum(0)
                        .accountNum(3)
                        .build());
    }

    @Test
    void flushIncompleteBlockPersistsIssArtifactViaFileWriter() {
        final var subject = new FileAndGrpcBlockItemWriter(
                configProvider, selfNodeAccountIdManager, FileSystems.getDefault(), blockBufferService);
        subject.openBlock(1);
        subject.writePbjItemAndBytes(
                BlockItem.newBuilder()
                        .roundHeader(RoundHeader.newBuilder().roundNumber(1L).build())
                        .build(),
                Bytes.wrap(new byte[] {1, 2, 3}));

        subject.flushIncompleteBlock();

        // The file half persists the open block as a .open.zstd triage artifact, with no .blk.zstd, no completion
        // marker,
        // and no pending-proof sidecar.
        final var dir = tempDir.resolve("block-0.0.3");
        assertThat(Files.exists(dir.resolve(OPEN_ZSTD))).isTrue();
        assertThat(Files.exists(dir.resolve(BLK_ZSTD))).isFalse();
        assertThat(Files.exists(dir.resolve(MF))).isFalse();
        assertThat(Files.exists(dir.resolve(PENDING_PROOF_JSON))).isFalse();
    }

    @Test
    void abandonBlockClosesTheFileWriter() throws IOException {
        final var subject = new FileAndGrpcBlockItemWriter(
                configProvider, selfNodeAccountIdManager, FileSystems.getDefault(), blockBufferService);
        subject.openBlock(1);
        final var item = BlockItem.newBuilder()
                .roundHeader(RoundHeader.newBuilder().roundNumber(1L).build())
                .build();
        subject.writePbjItemAndBytes(item, Bytes.wrap(new byte[] {1, 2, 3}));

        subject.abandonBlock();

        // The file half is closed, so the partial block file is a complete Zstandard frame; nothing else is written
        final var dir = tempDir.resolve("block-0.0.3");
        try (final var files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly(BLK_ZSTD);
        }
        try (final var in = BlockFileUtils.openInputStream(dir.resolve(BLK_ZSTD))) {
            assertThat(in.readAllBytes()).isEqualTo(new byte[] {10, 3, 1, 2, 3});
        }
        assertThatThrownBy(() -> subject.writePbjItemAndBytes(item, Bytes.wrap(new byte[] {1})))
                .isInstanceOf(IllegalStateException.class);
    }
}
