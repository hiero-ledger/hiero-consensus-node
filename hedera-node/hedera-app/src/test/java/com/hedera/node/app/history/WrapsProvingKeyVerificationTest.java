// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.history.WrapsProvingKeyVerification.artifactsAlreadyPresent;
import static com.hedera.node.app.history.WrapsProvingKeyVerification.validateArtifactsPathConsistency;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.cryptography.wraps.WRAPSLibraryBridge;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith({MockitoExtension.class, SystemStubsExtension.class})
class WrapsProvingKeyVerificationTest {

    private static final byte[] CONTENT_A = "test-content-a-for-proving-key".getBytes();
    private static final byte[] CONTENT_B = "test-content-b-different-key!!".getBytes();
    private static final Bytes HASH_A = noThrowSha384HashOf(Bytes.wrap(CONTENT_A));
    private static final String DOWNLOAD_URL = "https://s3.example.com/bucket/proving-key.tar.gz";

    @Mock
    private Configuration configuration;

    @Mock
    private TssConfig tssConfig;

    @Mock
    private HttpWrapsProvingKeyDownloader downloader;

    @Mock
    private ScheduledExecutorService retryScheduler;

    @Mock
    @SuppressWarnings("rawtypes")
    private ScheduledFuture scheduledFuture;

    @TempDir
    Path tempDir;

    private WrapsProvingKeyVerification subject;

    @BeforeEach
    void setUp() {
        // Use synchronous executor so async downloads run inline for testing
        subject = new WrapsProvingKeyVerification(Runnable::run);
        Mockito.lenient().when(configuration.getConfigData(TssConfig.class)).thenReturn(tssConfig);
        Mockito.lenient().when(tssConfig.wrapsProvingKeyRetryInterval()).thenReturn(Duration.ofSeconds(60));
    }

    @Test
    void throwsWhenWrapsEnabledAndBootstrapHashIsBlank() {
        given(tssConfig.wrapsProvingKeyDownloadEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn("");

        assertThrows(IllegalArgumentException.class, () -> subject.ensureProvingKey(configuration, downloader));
    }

    @Test
    void skipsVerificationWhenWrapsNotEnabled() {
        given(tssConfig.wrapsProvingKeyDownloadEnabled()).willReturn(false);

        subject.ensureProvingKey(configuration, downloader);

        verifyNoInteractions(downloader);
    }

    @Test
    void skipsDownloadWhenFileMatchesHash() throws IOException {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, CONTENT_A);
        givenConfigWithHashAndPath(HASH_A.toHex(), path);

        subject.ensureProvingKey(configuration, downloader);

        verifyNoInteractions(downloader);
    }

    @Test
    void throwsOnUnreadableFile() throws IOException {
        final var path = tempDir.resolve("unreadable");
        Files.createDirectory(path);
        final var hash = "aa".repeat(48);
        givenConfigWithHashAndPath(hash, path);

        assertThrows(UncheckedIOException.class, () -> subject.ensureProvingKey(configuration, downloader));
    }

    @Test
    void downloadsWhenFileMissing() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_A);

        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @Test
    void downloadsOnHashMismatch() throws Exception {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, "wrong content on disk".getBytes());
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_A);

        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @Test
    void continuesWhenDownloadedFileHashStillMismatches() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        final var configHash = "aa".repeat(48); // won't match CONTENT_B's hash
        givenConfigWithHashAndPath(configHash, path);
        givenDownloaderWritesContent(path, CONTENT_B);

        subject.ensureProvingKey(configuration, downloader);
    }

    @Test
    void continuesOnDownloadIOException() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        final var configHash = "aa".repeat(48);
        givenConfigWithHashAndPath(configHash, path);
        doThrow(new IOException("network error")).when(downloader).download(anyString(), any());

        subject.ensureProvingKey(configuration, downloader);
    }

    @Test
    void passesConfiguredDownloadUrlToDownloader() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_A);

        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(eq(DOWNLOAD_URL), eq(path));
    }

    @Test
    void throwsWhenEnvArtifactsPathNotUnderExtractionDir() {
        final var provingKeyPath = Paths.get("/opt/hgcapp/wraps-v1.0.0.tar.gz");
        final var wrongEnvPath = "/completely/different/path";

        assertThrows(IllegalStateException.class, () -> validateArtifactsPathConsistency(provingKeyPath, wrongEnvPath));
    }

    @Test
    void succeedsWhenEnvArtifactsPathIsUnderExtractionDir() {
        final var provingKeyPath = Paths.get("/opt/hgcapp/wraps-v1.0.0.tar.gz");
        final var correctEnvPath = "/opt/hgcapp/wraps-v1.0.0";

        assertDoesNotThrow(() -> validateArtifactsPathConsistency(provingKeyPath, correctEnvPath));
    }

    @Test
    void succeedsWithRelativeProvingKeyPathAndAbsoluteEnvArtifactsPath() {
        // The default tss.wrapsProvingKeyPath is relative (data/keys/wraps) while the env var is
        // typically absolute; both must be resolved against the working directory before comparison
        final var provingKeyPath = Paths.get("data/keys/wraps-archive");
        final var absoluteEnvPath =
                Paths.get("data/keys/wraps").toAbsolutePath().toString();

        assertDoesNotThrow(() -> validateArtifactsPathConsistency(provingKeyPath, absoluteEnvPath));
    }

    @Test
    void throwsWithRelativeProvingKeyPathAndAbsoluteEnvArtifactsPathOutsideExtractionDir() {
        final var provingKeyPath = Paths.get("data/keys/wraps-archive");
        final var wrongEnvPath = "/completely/different/path";

        assertThrows(IllegalStateException.class, () -> validateArtifactsPathConsistency(provingKeyPath, wrongEnvPath));
    }

    @Test
    void doesNotThrowWhenEnvArtifactsPathIsNull() {
        final var provingKeyPath = Paths.get("/opt/hgcapp/wraps-v1.0.0.tar.gz");

        assertDoesNotThrow(() -> validateArtifactsPathConsistency(provingKeyPath, null));
    }

    @Test
    void doesNotThrowWhenEnvArtifactsPathIsBlank() {
        final var provingKeyPath = Paths.get("/opt/hgcapp/wraps-v1.0.0.tar.gz");

        assertDoesNotThrow(() -> validateArtifactsPathConsistency(provingKeyPath, ""));
    }

    @Test
    void proofSupportedWithConfiguredV100ArtifactSet(final EnvironmentVariables environment) throws Exception {
        for (final var artifact : WrapsProvingKeyVerification.REQUIRED_ARTIFACT_FILES) {
            Files.write(tempDir.resolve(artifact), artifact.getBytes());
        }
        environment.set(WrapsProvingKeyVerification.WRAPS_ARTIFACTS_ENV_VAR, tempDir.toString());

        assertDoesNotThrow(
                () -> validateArtifactsPathConsistency(tempDir.resolve("wraps-v1.0.0.tar.gz"), tempDir.toString()));
        assertTrue(WRAPSLibraryBridge.isProofSupported());
    }

    @SuppressWarnings("unchecked")
    @Test
    void schedulesRetryOnDownloadHashMismatch() throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);

        given(retryScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        verify(retryScheduler).scheduleWithFixedDelay(any(), eq(60_000L), eq(60_000L), eq(TimeUnit.MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    void schedulesRetryOnDownloadException() throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        doThrow(new IOException("network error")).when(downloader).download(anyString(), any());

        given(retryScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        verify(retryScheduler).scheduleWithFixedDelay(any(), eq(60_000L), eq(60_000L), eq(TimeUnit.MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    void retryUsesConfiguredInterval() throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        given(tssConfig.wrapsProvingKeyRetryInterval()).willReturn(Duration.ofSeconds(42));
        givenDownloaderWritesContent(path, CONTENT_B);

        given(retryScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        verify(retryScheduler).scheduleWithFixedDelay(any(), eq(42_000L), eq(42_000L), eq(TimeUnit.MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    void doesNotScheduleSecondRetryWhenRetryAlreadyScheduled() throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);

        given(retryScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);
        subject.ensureProvingKey(configuration, downloader);

        verify(retryScheduler, Mockito.times(1))
                .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void retrySucceedsAndCancelsScheduledFuture() throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);

        final ArgumentCaptor<Runnable> retryCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(retryScheduler.scheduleWithFixedDelay(retryCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        // Re-stub downloader to return correct content for the retry
        givenDownloaderWritesContent(path, CONTENT_A);
        retryCaptor.getValue().run();

        verify(scheduledFuture).cancel(false);
    }

    @SuppressWarnings("unchecked")
    @Test
    void retryTaskContinuesOnHashMismatch() throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);

        final ArgumentCaptor<Runnable> retryCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(retryScheduler.scheduleWithFixedDelay(retryCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        // Retry still gets wrong content
        retryCaptor.getValue().run();

        verify(scheduledFuture, Mockito.never()).cancel(Mockito.anyBoolean());
    }

    @SuppressWarnings("unchecked")
    @Test
    void retryTaskContinuesOnDownloadException() throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);

        final ArgumentCaptor<Runnable> retryCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(retryScheduler.scheduleWithFixedDelay(retryCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        // Re-stub downloader to throw on retry
        doThrow(new IOException("retry error")).when(downloader).download(anyString(), any());

        assertDoesNotThrow(() -> retryCaptor.getValue().run());
        verify(scheduledFuture, Mockito.never()).cancel(Mockito.anyBoolean());
    }

    // ===== sidecar hash file logic =====

    @Test
    void artifactsAlreadyPresentTrueWhenSidecarMatchesAndArtifactsPresent() throws IOException {
        writeRequiredArtifacts(tempDir);
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), HASH_A.toHex());

        assertTrue(artifactsAlreadyPresent(tempDir.toString(), HASH_A.toHex()));
    }

    @Test
    void artifactsAlreadyPresentTrimsAndIgnoresCase() throws IOException {
        writeRequiredArtifacts(tempDir);
        Files.writeString(
                tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME),
                "  " + HASH_A.toHex().toUpperCase() + "\n");

        assertTrue(artifactsAlreadyPresent(tempDir.toString(), HASH_A.toHex()));
    }

    @Test
    void artifactsAlreadyPresentFalseWhenSidecarMismatches() throws IOException {
        writeRequiredArtifacts(tempDir);
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), "bb".repeat(48));

        assertFalse(artifactsAlreadyPresent(tempDir.toString(), HASH_A.toHex()));
    }

    @Test
    void artifactsAlreadyPresentFalseWhenArtifactMissing() throws IOException {
        // Sidecar matches config but a required artifact file is absent
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), HASH_A.toHex());

        assertFalse(artifactsAlreadyPresent(tempDir.toString(), HASH_A.toHex()));
    }

    @Test
    void artifactsAlreadyPresentFalseWhenNoSidecar() throws IOException {
        writeRequiredArtifacts(tempDir);

        assertFalse(artifactsAlreadyPresent(tempDir.toString(), HASH_A.toHex()));
    }

    @Test
    void artifactsAlreadyPresentFalseWhenEnvPathNullOrBlank() {
        assertFalse(artifactsAlreadyPresent(null, HASH_A.toHex()));
        assertFalse(artifactsAlreadyPresent("", HASH_A.toHex()));
        assertFalse(artifactsAlreadyPresent("   ", HASH_A.toHex()));
    }

    @Test
    void skipsDownloadWhenSidecarMatchesAndArtifactsPresent(final EnvironmentVariables environment) throws IOException {
        writeRequiredArtifacts(tempDir);
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), HASH_A.toHex());
        environment.set(WrapsProvingKeyVerification.WRAPS_ARTIFACTS_ENV_VAR, tempDir.toString());

        // No archive on disk; only the extracted artifacts + sidecar are present (mounted-image scenario)
        given(tssConfig.wrapsProvingKeyDownloadEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn(HASH_A.toHex());
        given(tssConfig.wrapsProvingKeyPath())
                .willReturn(tempDir.resolve("wraps.tar.gz").toString());

        subject.ensureProvingKey(configuration, downloader);

        verifyNoInteractions(downloader);
    }

    @Test
    void downloadsWhenSidecarMismatches(final EnvironmentVariables environment) throws Exception {
        writeRequiredArtifacts(tempDir);
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), "bb".repeat(48));
        environment.set(WrapsProvingKeyVerification.WRAPS_ARTIFACTS_ENV_VAR, tempDir.toString());

        final var archivePath = tempDir.resolve("wraps.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), archivePath);
        givenDownloaderWritesContent(archivePath, CONTENT_A);

        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, archivePath);
    }

    @Test
    void downloadsWhenSidecarMatchesButArtifactMissing(final EnvironmentVariables environment) throws Exception {
        // Sidecar matches config but a required artifact is missing -> not "already present"
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), HASH_A.toHex());
        environment.set(WrapsProvingKeyVerification.WRAPS_ARTIFACTS_ENV_VAR, tempDir.toString());

        final var archivePath = tempDir.resolve("wraps.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), archivePath);
        givenDownloaderWritesContent(archivePath, CONTENT_A);

        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, archivePath);
    }

    @Test
    void writesSidecarAfterSuccessfulExtraction(final EnvironmentVariables environment) throws Exception {
        // A real archive containing the four required artifacts, present on disk and matching config
        final byte[] archiveBytes = createTarGz(
                entry("decider_pp.bin", "pp".getBytes(StandardCharsets.UTF_8)),
                entry("decider_vp.bin", "vp".getBytes(StandardCharsets.UTF_8)),
                entry("nova_pp.bin", "npp".getBytes(StandardCharsets.UTF_8)),
                entry("nova_vp.bin", "nvp".getBytes(StandardCharsets.UTF_8)));
        final var archivePath = tempDir.resolve("wraps.tar.gz");
        Files.write(archivePath, archiveBytes);
        final var archiveHash = noThrowSha384HashOf(Bytes.wrap(archiveBytes)).toHex();

        final var extractionDir = tempDir.resolve("extracted");
        environment.set(WrapsProvingKeyVerification.WRAPS_ARTIFACTS_ENV_VAR, extractionDir.toString());

        given(tssConfig.wrapsProvingKeyDownloadEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn(archiveHash);
        given(tssConfig.wrapsProvingKeyPath()).willReturn(archivePath.toString());

        subject.ensureProvingKey(configuration, downloader);

        // No download (archive already present and verified); artifacts extracted; sidecar written
        verifyNoInteractions(downloader);
        for (final var artifact : WrapsProvingKeyVerification.REQUIRED_ARTIFACT_FILES) {
            assertTrue(Files.isRegularFile(extractionDir.resolve(artifact)), "missing extracted artifact " + artifact);
        }
        final var sidecar = extractionDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME);
        assertTrue(Files.isRegularFile(sidecar), "sidecar hash file was not written");
        assertEquals(archiveHash, Files.readString(sidecar).trim());
    }

    // ===== helpers =====

    private void givenConfigWithHashAndPath(final String bootstrapHash, final Path provingKeyPath) {
        given(tssConfig.wrapsProvingKeyDownloadEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn(bootstrapHash);
        given(tssConfig.wrapsProvingKeyPath()).willReturn(provingKeyPath.toString());
        given(tssConfig.wrapsProvingKeyDownloadUrl()).willReturn(DOWNLOAD_URL);
        Mockito.lenient().when(tssConfig.wrapsProvingKeyRetryInterval()).thenReturn(Duration.ofSeconds(60));
    }

    private void givenDownloaderWritesContent(final Path path, final byte[] content) throws IOException {
        doAnswer(inv -> {
                    Files.write(inv.getArgument(1), content);
                    return null;
                })
                .when(downloader)
                .download(anyString(), eq(path));
    }

    private static void writeRequiredArtifacts(final Path dir) throws IOException {
        for (final var artifact : WrapsProvingKeyVerification.REQUIRED_ARTIFACT_FILES) {
            Files.write(dir.resolve(artifact), artifact.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ===== minimal tar.gz builder (mirrors TarGzExtractorTest) =====

    private static final int BLOCK_SIZE = 512;

    private static byte[] createTarGz(final byte[]... entries) throws IOException {
        final var tarBaos = new ByteArrayOutputStream();
        for (final byte[] entry : entries) {
            tarBaos.write(entry);
        }
        // Two zero blocks mark end of archive
        tarBaos.write(new byte[BLOCK_SIZE]);
        tarBaos.write(new byte[BLOCK_SIZE]);

        final var gzBaos = new ByteArrayOutputStream();
        try (final var gzos = new GZIPOutputStream(gzBaos)) {
            gzos.write(tarBaos.toByteArray());
        }
        return gzBaos.toByteArray();
    }

    /** Creates a tar entry (header + data blocks) for a regular file. */
    private static byte[] entry(final String name, final byte[] content) {
        final byte[] header = new byte[BLOCK_SIZE];
        final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        writeOctal(header, 100, 8, 0644); // mode
        writeOctal(header, 108, 8, 0); // uid
        writeOctal(header, 116, 8, 0); // gid
        writeOctal(header, 124, 12, content.length); // size
        writeOctal(header, 136, 12, 0); // mtime
        header[156] = (byte) '0'; // regular file
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        header[263] = '0';
        header[264] = '0';
        recomputeChecksum(header);

        final int dataBlocks = (content.length + BLOCK_SIZE - 1) / BLOCK_SIZE;
        final byte[] result = new byte[BLOCK_SIZE + dataBlocks * BLOCK_SIZE];
        System.arraycopy(header, 0, result, 0, BLOCK_SIZE);
        System.arraycopy(content, 0, result, BLOCK_SIZE, content.length);
        return result;
    }

    private static void writeOctal(final byte[] header, final int offset, final int fieldLen, final long value) {
        final String octal = String.format("%0" + (fieldLen - 1) + "o", value);
        final byte[] octalBytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(octalBytes, 0, header, offset, octalBytes.length);
        header[offset + octalBytes.length] = 0;
    }

    private static void recomputeChecksum(final byte[] header) {
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long checksum = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            checksum += (header[i] & 0xFF);
        }
        final String chkStr = String.format("%06o\0 ", checksum);
        System.arraycopy(chkStr.getBytes(StandardCharsets.US_ASCII), 0, header, 148, 8);
    }
}
