// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.bucky.S3Client;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BuckyBlockUploaderTest {

    private static final String INCIDENT = "2026-06-16T00-00-00Z";

    @TempDir
    Path tempDir;

    @Mock
    private FailureBlockUploadConfig config;

    @Mock
    private S3Client s3;

    @BeforeEach
    void setUp() {
        lenient().when(config.bucketName()).thenReturn("my-bucket");
        lenient().when(config.endpoint()).thenReturn("https://storage.googleapis.com");
        lenient().when(config.region()).thenReturn("auto");
        lenient().when(config.storageClass()).thenReturn("STANDARD");
        lenient().when(config.objectKeyPrefix()).thenReturn("iss-blocks");
        lenient().when(config.maxRetries()).thenReturn(2);
    }

    private Path credentialsFile() throws IOException {
        final Path file = tempDir.resolve("creds.properties");
        Files.writeString(file, "accessKey=AK\nsecretKey=SK\n");
        return file;
    }

    @Test
    void uploadsPendingContentsAndProofSidecarUnderIssFolder() throws Exception {
        final String base = FileBlockItemWriter.longToFileName(2L);
        final Path pnd = tempDir.resolve(base + ".pnd.gz");
        final Path proof = tempDir.resolve(base + ".pnd.json");
        Files.write(pnd, new byte[] {1, 2, 3});
        Files.writeString(proof, "{}");

        final var uploader = new BuckyBlockUploader(config, "0.0.3", credentialsFile(), (c, cr) -> s3);
        final List<String> uris = uploader.uploadBlockFiles(UploadCategory.ISS, INCIDENT, List.of(pnd));

        final String contentsKey = "iss-blocks/0.0.3/iss/" + INCIDENT + "/" + base + "/" + base + ".pnd.gz";
        final String proofKey = "iss-blocks/0.0.3/iss/" + INCIDENT + "/" + base + "/" + base + ".pnd.json";
        verify(s3).uploadFile(eq(contentsKey), eq("STANDARD"), any(), eq("application/gzip"));
        verify(s3).uploadFile(eq(proofKey), eq("STANDARD"), any(), eq("application/json"));
        verify(s3).close();
        assertThat(uris)
                .containsExactly(
                        "https://storage.googleapis.com/my-bucket/" + contentsKey,
                        "https://storage.googleapis.com/my-bucket/" + proofKey);
    }

    @Test
    void uploadsUnderTriageFolderForTriageCategory() throws Exception {
        final String base = FileBlockItemWriter.longToFileName(7L);
        final Path blk = tempDir.resolve(base + ".blk.gz");
        Files.write(blk, new byte[] {4, 5});

        final var uploader = new BuckyBlockUploader(config, "0.0.3", credentialsFile(), (c, cr) -> s3);
        final List<String> uris = uploader.uploadBlockFiles(UploadCategory.TRIAGE, INCIDENT, List.of(blk));

        final String key = "iss-blocks/0.0.3/triage/" + INCIDENT + "/" + base + "/" + base + ".blk.gz";
        verify(s3).uploadFile(eq(key), eq("STANDARD"), any(), eq("application/gzip"));
        assertThat(uris).containsExactly("https://storage.googleapis.com/my-bucket/" + key);
    }

    @Test
    void openBlockExtensionStripsToPaddedBlockNumberInKey() throws Exception {
        final String base = FileBlockItemWriter.longToFileName(9L);
        final Path open = tempDir.resolve(base + ".open.gz");
        Files.write(open, new byte[] {6});

        final var uploader = new BuckyBlockUploader(config, "0.0.3", credentialsFile(), (c, cr) -> s3);
        uploader.uploadBlockFiles(UploadCategory.ISS, INCIDENT, List.of(open));

        // The key folder is the padded block number, not the whole file name (regression for .open.gz support).
        final String key = "iss-blocks/0.0.3/iss/" + INCIDENT + "/" + base + "/" + base + ".open.gz";
        verify(s3).uploadFile(eq(key), eq("STANDARD"), any(), eq("application/gzip"));
    }

    @Test
    void retriesOnFailureThenSucceeds() throws Exception {
        final Path iss = tempDir.resolve(FileBlockItemWriter.longToFileName(2L) + ".iss.gz");
        Files.write(iss, new byte[] {9});
        doThrow(new IOException("transient"))
                .doNothing()
                .when(s3)
                .uploadFile(anyString(), anyString(), any(), anyString());
        final var uploader = new BuckyBlockUploader(config, "0.0.3", credentialsFile(), (c, cr) -> s3);

        final List<String> uris = uploader.uploadBlockFiles(UploadCategory.ISS, INCIDENT, List.of(iss));

        verify(s3, times(2)).uploadFile(anyString(), anyString(), any(), anyString());
        assertThat(uris).hasSize(1);
    }

    @Test
    void returnsEmptyAndSkipsClientWhenCredentialsMissing() {
        final var uploader = new BuckyBlockUploader(config, "0.0.3", tempDir.resolve("missing.properties"), (c, cr) -> {
            throw new AssertionError("client factory must not be called when credentials are missing");
        });

        final List<String> uris =
                uploader.uploadBlockFiles(UploadCategory.ISS, INCIDENT, List.of(tempDir.resolve("x.iss.gz")));

        assertThat(uris).isEmpty();
    }
}
