// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.bucky.RetryPolicy;
import com.hedera.bucky.S3Client;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    void buildsRetryPolicyFromConfig() {
        when(config.uploadTimeout()).thenReturn(Duration.ofSeconds(45));

        // maxRetries is 2 (see setUp), so the policy allows 3 attempts (the initial try plus 2 retries).
        final RetryPolicy policy = BuckyBlockUploader.retryPolicyFor(config);

        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.baseDelayMs()).isEqualTo(200L);
        assertThat(policy.maxDelayMs()).isEqualTo(20_000L);
        assertThat(policy.totalTimeoutMs()).isEqualTo(45_000L);
        assertThat(policy.requestTimeoutMs()).isZero();
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
    void pointerMarkerTxtStripsToBaseNameAndUsesTextPlain() throws Exception {
        final Path marker = tempDir.resolve("iss-round-9.txt");
        Files.writeString(marker, "issRound=9\n");

        final var uploader = new BuckyBlockUploader(config, "0.0.3", credentialsFile(), (c, cr) -> s3);
        uploader.uploadBlockFiles(UploadCategory.ISS, INCIDENT, List.of(marker));

        // The .txt extension is stripped to form the key folder, and the pointer is uploaded as text/plain.
        final String key = "iss-blocks/0.0.3/iss/" + INCIDENT + "/iss-round-9/iss-round-9.txt";
        verify(s3).uploadFile(eq(key), eq("STANDARD"), any(), eq("text/plain"));
    }

    @Test
    void skipsFileWhenUploadUltimatelyFails() throws Exception {
        // Retry now lives inside bucky's S3Client (the RetryPolicy); when uploadFile still throws after bucky has
        // exhausted it, the uploader issues exactly one call, logs, and skips the file — no hand-rolled retry loop.
        final Path iss = tempDir.resolve(FileBlockItemWriter.longToFileName(2L) + ".iss.gz");
        Files.write(iss, new byte[] {9});
        doThrow(new IOException("exhausted")).when(s3).uploadFile(anyString(), anyString(), any(), anyString());
        final var uploader = new BuckyBlockUploader(config, "0.0.3", credentialsFile(), (c, cr) -> s3);

        final List<String> uris = uploader.uploadBlockFiles(UploadCategory.ISS, INCIDENT, List.of(iss));

        verify(s3, times(1)).uploadFile(anyString(), anyString(), any(), anyString());
        assertThat(uris).isEmpty();
    }

    @Test
    void doesNotUploadProofSidecarWhenPendingContentsUploadFails() throws Exception {
        // The .pnd.json proof sidecar must upload only if the .pnd.gz contents upload succeeds;
        // a sidecar-only success must not be reported, else the coordinator marks the ISS block uploaded when it
        // wasn't.
        final String base = FileBlockItemWriter.longToFileName(2L);
        final Path pnd = tempDir.resolve(base + ".pnd.gz");
        final Path proof = tempDir.resolve(base + ".pnd.json");
        Files.write(pnd, new byte[] {1, 2, 3});
        Files.writeString(proof, "{}");
        // Contents (.pnd.gz) upload fails; the sidecar (.pnd.json) would otherwise succeed by default.
        doThrow(new IOException("contents failed"))
                .when(s3)
                .uploadFile(argThat((String key) -> key.endsWith(".pnd.gz")), anyString(), any(), anyString());

        final var uploader = new BuckyBlockUploader(config, "0.0.3", credentialsFile(), (c, cr) -> s3);
        final List<String> uris = uploader.uploadBlockFiles(UploadCategory.ISS, INCIDENT, List.of(pnd));

        assertThat(uris).isEmpty();
        verify(s3).uploadFile(argThat((String key) -> key.endsWith(".pnd.gz")), anyString(), any(), anyString());
        verify(s3, never())
                .uploadFile(argThat((String key) -> key.endsWith(".pnd.json")), anyString(), any(), anyString());
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
