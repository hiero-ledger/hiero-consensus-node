// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssBlockUploadCoordinatorTest {

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private VersionedConfiguration versionedConfiguration;

    @Mock
    private FailureBlockUploadConfig config;

    @Mock
    private BlockStreamManager blockStreamManager;

    @Mock
    private BlockUploader uploader;

    // Fixed clock → deterministic incident-folder name "2026-06-16T14-32-05Z"
    private final InstantSource instantSource = InstantSource.fixed(Instant.parse("2026-06-16T14:32:05Z"));
    private static final String EXPECTED_FOLDER = "2026-06-16T14-32-05Z";

    private IssBlockUploadCoordinator subject;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(FailureBlockUploadConfig.class))
                .thenReturn(config);
        subject = new IssBlockUploadCoordinator(configProvider, blockStreamManager, uploader, instantSource);
    }

    @Test
    void uploadsFlushedFilesWhenEnabled() {
        when(config.triageUploadEnabled()).thenReturn(true);
        when(config.uploadTimeout()).thenReturn(Duration.ofSeconds(5));
        final List<Path> files = List.of(Path.of("a.pnd.gz"), Path.of("b.open.gz"));
        when(blockStreamManager.flushedTriageBlockFiles()).thenReturn(files);
        when(uploader.uploadBlockFiles(eq(UploadCategory.TRIAGE), eq(EXPECTED_FOLDER), eq(files)))
                .thenReturn(List.of("uriA", "uriB"));

        subject.uploadFlushedIssBlocks();

        // The flushed set goes to the triage/ folder, under the fixed-clock UTC incident timestamp.
        verify(uploader).uploadBlockFiles(UploadCategory.TRIAGE, EXPECTED_FOLDER, files);
    }

    @Test
    void noOpWhenDisabled() {
        when(config.triageUploadEnabled()).thenReturn(false);

        subject.uploadFlushedIssBlocks();

        verifyNoInteractions(blockStreamManager, uploader);
    }

    @Test
    void noOpWhenNoFilesFlushed() {
        when(config.triageUploadEnabled()).thenReturn(true);
        when(blockStreamManager.flushedTriageBlockFiles()).thenReturn(List.of());

        subject.uploadFlushedIssBlocks();

        verify(uploader, never()).uploadBlockFiles(any(), any(), any());
    }

    @Test
    void swallowsUploaderExceptions() {
        when(config.triageUploadEnabled()).thenReturn(true);
        when(config.uploadTimeout()).thenReturn(Duration.ofSeconds(5));
        when(blockStreamManager.flushedTriageBlockFiles()).thenReturn(List.of(Path.of("a.open.gz")));
        when(uploader.uploadBlockFiles(any(), anyString(), any())).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> subject.uploadFlushedIssBlocks()).doesNotThrowAnyException();
    }

    @Test
    void hardTimeoutAbandonsSlowUpload() {
        when(config.triageUploadEnabled()).thenReturn(true);
        when(config.uploadTimeout()).thenReturn(Duration.ofMillis(200));
        when(blockStreamManager.flushedTriageBlockFiles()).thenReturn(List.of(Path.of("a.open.gz")));
        when(uploader.uploadBlockFiles(any(), anyString(), any())).thenAnswer(invocation -> {
            Thread.sleep(5_000); // far longer than the upload timeout
            return List.of("late");
        });

        final long start = System.nanoTime();
        assertThatCode(() -> subject.uploadFlushedIssBlocks()).doesNotThrowAnyException();
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // The coordinator must return shortly after the 200ms deadline, not wait the full 5s upload.
        assertThat(elapsedMs).isLessThan(2_000L);
    }
}
