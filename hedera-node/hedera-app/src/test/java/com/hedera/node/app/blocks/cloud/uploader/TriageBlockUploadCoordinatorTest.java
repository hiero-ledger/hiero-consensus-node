// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TriageBlockUploadCoordinatorTest {

    @TempDir
    Path tempDir;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private FailureBlockUploadConfig config;

    @Mock
    private BlockStreamManager blockStreamManager;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    // Fixed clock -> deterministic incident-folder name "2026-06-16T14-32-05Z"
    private final InstantSource instantSource = InstantSource.fixed(Instant.parse("2026-06-16T14:32:05Z"));
    private static final String EXPECTED_FOLDER = "2026-06-16T14-32-05Z";

    private Path issBlockDir;
    private TriageBlockUploadCoordinator subject;

    @BeforeEach
    void setUp() {
        issBlockDir = tempDir.resolve("iss-blocks");
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(FailureBlockUploadConfig.class))
                .thenReturn(config);
        lenient().when(config.issBlockDir()).thenReturn(issBlockDir.toString());
        lenient()
                .when(selfNodeAccountIdManager.getSelfNodeAccountId())
                .thenReturn(AccountID.newBuilder().accountNum(3).build());
        subject = new TriageBlockUploadCoordinator(
                configProvider, blockStreamManager, selfNodeAccountIdManager, FileSystems.getDefault(), instantSource);
    }

    private Path triageDir() {
        return issBlockDir.resolve("block-0.0.3").resolve(EXPECTED_FOLDER).resolve("triage");
    }

    @Test
    void stagesFlushedFilesAndProofSidecarUnderTriageWhenEnabled() throws IOException {
        when(config.triageUploadEnabled()).thenReturn(true);

        final Path streamDir = tempDir.resolve("stream");
        Files.createDirectories(streamDir);
        final String pndBase = FileBlockItemWriter.longToFileName(7L);
        final String openBase = FileBlockItemWriter.longToFileName(8L);
        final Path pndGz = Files.write(streamDir.resolve(pndBase + ".pnd.gz"), new byte[] {1});
        // A .pnd.gz carries a .pnd.json proof sidecar that must be staged alongside it.
        Files.write(streamDir.resolve(pndBase + ".pnd.json"), new byte[] {2});
        final Path openGz = Files.write(streamDir.resolve(openBase + ".open.gz"), new byte[] {3});
        when(blockStreamManager.flushedTriageBlockFiles()).thenReturn(List.of(pndGz, openGz));

        subject.stageFlushedTriageBlocks(null);

        assertThat(triageDir().resolve(pndBase + ".pnd.gz")).exists();
        assertThat(triageDir().resolve(pndBase + ".pnd.json")).exists();
        assertThat(triageDir().resolve(openBase + ".open.gz")).exists();
    }

    @Test
    void noOpWhenDisabled() {
        when(config.triageUploadEnabled()).thenReturn(false);

        subject.stageFlushedTriageBlocks(null);

        verifyNoInteractions(blockStreamManager);
        assertThat(issBlockDir).doesNotExist();
    }

    @Test
    void noOpWhenNoFilesFlushed() {
        when(config.triageUploadEnabled()).thenReturn(true);
        when(blockStreamManager.flushedTriageBlockFiles()).thenReturn(List.of());

        subject.stageFlushedTriageBlocks(null);

        assertThat(issBlockDir).doesNotExist();
    }

    @Test
    void swallowsStagingErrorsWhenFlushedFileMissing() {
        when(config.triageUploadEnabled()).thenReturn(true);
        // A flushed file that no longer exists on disk must not abort staging or propagate.
        when(blockStreamManager.flushedTriageBlockFiles()).thenReturn(List.of(tempDir.resolve("gone.open.gz")));

        assertThatCode(() -> subject.stageFlushedTriageBlocks(null)).doesNotThrowAnyException();

        assertThat(triageDir().resolve("gone.open.gz")).doesNotExist();
    }
}
