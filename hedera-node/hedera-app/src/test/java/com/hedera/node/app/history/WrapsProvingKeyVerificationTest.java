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
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    void doesNotHashOrDownloadWhenArtifactsEnvVarIsBlank(final EnvironmentVariables environment) throws IOException {
        // A directory cannot be read as a FileInputStream; if the code attempted to hash it
        // an UncheckedIOException would be thrown.  The early-return when
        // TSS_LIB_WRAPS_ARTIFACTS_PATH is unset must prevent any file access.
        final var path = tempDir.resolve("would-fail-if-read");
        Files.createDirectory(path);
        given(tssConfig.wrapsProvingKeyDownloadEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn(HASH_A.toHex());
        environment.set(WrapsProvingKeyVerification.WRAPS_ARTIFACTS_ENV_VAR, "");

        assertDoesNotThrow(() -> subject.ensureProvingKey(configuration, downloader));
        verifyNoInteractions(downloader);
    }

    @Test
    void skipsDownloadWhenFileMatchesHash(final EnvironmentVariables environment) throws IOException {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, CONTENT_A);
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        setArtifactsEnvVar(environment);

        subject.ensureProvingKey(configuration, downloader);

        verifyNoInteractions(downloader);
    }

    @Test
    void doesNotThrowOnUnreadableFile(final EnvironmentVariables environment) throws IOException {
        final var path = tempDir.resolve("unreadable");
        Files.createDirectory(path);
        final var hash = "aa".repeat(48);
        givenConfigWithHashAndPath(hash, path);
        setArtifactsEnvVar(environment);

        assertDoesNotThrow(() -> subject.ensureProvingKey(configuration, downloader));
        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @Test
    void downloadsWhenFileMissing(final EnvironmentVariables environment) throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_A);
        setArtifactsEnvVar(environment);

        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @Test
    void downloadsOnHashMismatch(final EnvironmentVariables environment) throws Exception {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, "wrong content on disk".getBytes());
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_A);
        setArtifactsEnvVar(environment);

        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @Test
    void continuesWhenDownloadedFileHashStillMismatches(final EnvironmentVariables environment) throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        final var configHash = "aa".repeat(48); // won't match CONTENT_B's hash
        givenConfigWithHashAndPath(configHash, path);
        givenDownloaderWritesContent(path, CONTENT_B);
        setArtifactsEnvVar(environment);

        subject.ensureProvingKey(configuration, downloader);
    }

    @Test
    void continuesOnDownloadIOException(final EnvironmentVariables environment) throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        final var configHash = "aa".repeat(48);
        givenConfigWithHashAndPath(configHash, path);
        doThrow(new IOException("network error")).when(downloader).download(anyString(), any());
        setArtifactsEnvVar(environment);

        subject.ensureProvingKey(configuration, downloader);
    }

    @Test
    void passesConfiguredDownloadUrlToDownloader(final EnvironmentVariables environment) throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_A);
        setArtifactsEnvVar(environment);

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
        setArtifactsEnvVar(environment);

        assertDoesNotThrow(
                () -> validateArtifactsPathConsistency(tempDir.resolve("wraps-v1.0.0.tar.gz"), tempDir.toString()));
        assertTrue(WRAPSLibraryBridge.isProofSupported());
    }

    @Test
    void doesNotStartSecondDownloadWhileOneIsInFlight(final EnvironmentVariables environment) throws Exception {
        setArtifactsEnvVar(environment);
        final var executor = Executors.newSingleThreadExecutor();
        try {
            final var subject = new WrapsProvingKeyVerification(executor);
            final var path = tempDir.resolve("key.tar.gz");
            givenConfigWithHashAndPath(HASH_A.toHex(), path);
            final var downloadStarted = new CountDownLatch(1);
            final var releaseDownload = new CountDownLatch(1);
            doAnswer(inv -> {
                        downloadStarted.countDown();
                        assertTrue(releaseDownload.await(10, TimeUnit.SECONDS));
                        Files.write(inv.getArgument(1), CONTENT_A);
                        return null;
                    })
                    .when(downloader)
                    .download(anyString(), eq(path));

            subject.ensureProvingKey(configuration, downloader);
            assertTrue(downloadStarted.await(10, TimeUnit.SECONDS));
            // A second init trigger (e.g. reconnect) must not stack another download on the executor
            subject.ensureProvingKey(configuration, downloader);
            releaseDownload.countDown();

            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            verify(downloader, Mockito.times(1)).download(DOWNLOAD_URL, path);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releasesTheInFlightGuardOnceADownloadFinishes(final EnvironmentVariables environment) throws Exception {
        // The guard's failure mode is "silently never downloads again", so pin down that it is released. Each
        // attempt leaves content whose hash does not match, so a second init trigger must download again.
        setArtifactsEnvVar(environment);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);

        subject.ensureProvingKey(configuration, downloader);
        subject.ensureProvingKey(configuration, downloader);

        verify(downloader, Mockito.times(2)).download(DOWNLOAD_URL, path);
    }

    @Test
    void stillDownloadsAfterTheExecutorRejectsAnAttempt(final EnvironmentVariables environment) throws Exception {
        // An executor that rejects the first submission, then runs inline. The rejected task never runs, so
        // nothing inside it can release the in-flight guard; a later attempt must not be locked out.
        setArtifactsEnvVar(environment);
        final var rejectFirst = new Executor() {
            private boolean rejected = false;

            @Override
            public void execute(@NonNull final Runnable command) {
                if (!rejected) {
                    rejected = true;
                    throw new RejectedExecutionException("executor is saturated");
                }
                command.run();
            }
        };
        final var subject = new WrapsProvingKeyVerification(rejectFirst);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_A);

        assertThrows(RejectedExecutionException.class, () -> subject.ensureProvingKey(configuration, downloader));
        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @SuppressWarnings("unchecked")
    @Test
    void schedulesRetryOnDownloadHashMismatch(final EnvironmentVariables environment) throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);
        setArtifactsEnvVar(environment);

        given(retryScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        verify(retryScheduler).scheduleWithFixedDelay(any(), eq(60_000L), eq(60_000L), eq(TimeUnit.MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    void schedulesRetryOnDownloadException(final EnvironmentVariables environment) throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        doThrow(new IOException("network error")).when(downloader).download(anyString(), any());
        setArtifactsEnvVar(environment);

        given(retryScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        verify(retryScheduler).scheduleWithFixedDelay(any(), eq(60_000L), eq(60_000L), eq(TimeUnit.MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    void retryUsesConfiguredInterval(final EnvironmentVariables environment) throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        given(tssConfig.wrapsProvingKeyRetryInterval()).willReturn(Duration.ofSeconds(42));
        givenDownloaderWritesContent(path, CONTENT_B);
        setArtifactsEnvVar(environment);

        given(retryScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        verify(retryScheduler).scheduleWithFixedDelay(any(), eq(42_000L), eq(42_000L), eq(TimeUnit.MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    void doesNotScheduleSecondRetryWhenRetryAlreadyScheduled(final EnvironmentVariables environment) throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);
        setArtifactsEnvVar(environment);

        given(retryScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);
        subject.ensureProvingKey(configuration, downloader);

        verify(retryScheduler, Mockito.times(1))
                .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void retrySucceedsAndCancelsScheduledFuture(final EnvironmentVariables environment) throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);
        setArtifactsEnvVar(environment);

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
    void retryTaskContinuesOnHashMismatch(final EnvironmentVariables environment) throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);
        setArtifactsEnvVar(environment);

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
    void retryTaskContinuesOnDownloadException(final EnvironmentVariables environment) throws Exception {
        final var subject = new WrapsProvingKeyVerification(Runnable::run, retryScheduler);
        final var path = tempDir.resolve("key.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenDownloaderWritesContent(path, CONTENT_B);
        setArtifactsEnvVar(environment);

        final ArgumentCaptor<Runnable> retryCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(retryScheduler.scheduleWithFixedDelay(retryCaptor.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(scheduledFuture);

        subject.ensureProvingKey(configuration, downloader);

        // Re-stub downloader to throw on retry
        doThrow(new IOException("retry error")).when(downloader).download(anyString(), any());

        assertDoesNotThrow(() -> retryCaptor.getValue().run());
        verify(scheduledFuture, Mockito.never()).cancel(Mockito.anyBoolean());
    }

    // ===== hash file logic =====

    @Test
    void artifactsAlreadyPresentTrueWhenHashFileMatchesAndArtifactsPresent() throws IOException {
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
    void artifactsAlreadyPresentFalseWhenHashFileMismatches() throws IOException {
        writeRequiredArtifacts(tempDir);
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), "bb".repeat(48));

        assertFalse(artifactsAlreadyPresent(tempDir.toString(), HASH_A.toHex()));
    }

    @Test
    void artifactsAlreadyPresentFalseWhenArtifactMissing() throws IOException {
        // Hash file matches config but a required artifact file is absent
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), HASH_A.toHex());

        assertFalse(artifactsAlreadyPresent(tempDir.toString(), HASH_A.toHex()));
    }

    @Test
    void artifactsAlreadyPresentFalseWhenNoHashFile() throws IOException {
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
    void skipsDownloadWhenHashFileMatchesAndArtifactsPresent(final EnvironmentVariables environment)
            throws IOException {
        writeRequiredArtifacts(tempDir);
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), HASH_A.toHex());

        // No archive on disk; only the extracted artifacts + hash file are present (mounted-image scenario)
        given(tssConfig.wrapsProvingKeyDownloadEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn(HASH_A.toHex());
        given(tssConfig.wrapsProvingKeyPath())
                .willReturn(tempDir.resolve("wraps.tar.gz").toString());
        setArtifactsEnvVar(environment);

        subject.ensureProvingKey(configuration, downloader);

        verifyNoInteractions(downloader);
    }

    @Test
    void downloadsWhenHashFileMismatches(final EnvironmentVariables environment) throws Exception {
        writeRequiredArtifacts(tempDir);
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), "bb".repeat(48));

        final var archivePath = tempDir.resolve("wraps.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), archivePath);
        givenDownloaderWritesContent(archivePath, CONTENT_A);
        setArtifactsEnvVar(environment);

        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, archivePath);
    }

    @Test
    void downloadsWhenHashFileMatchesButArtifactMissing(final EnvironmentVariables environment) throws Exception {
        // Hash file matches config but a required artifact is missing -> not "already present"
        Files.writeString(tempDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME), HASH_A.toHex());

        final var archivePath = tempDir.resolve("wraps.tar.gz");
        givenConfigWithHashAndPath(HASH_A.toHex(), archivePath);
        givenDownloaderWritesContent(archivePath, CONTENT_A);
        setArtifactsEnvVar(environment);

        subject.ensureProvingKey(configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, archivePath);
    }

    @Test
    void writesHashFileAfterSuccessfulExtraction(final EnvironmentVariables environment) throws Exception {
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

        given(tssConfig.wrapsProvingKeyDownloadEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn(archiveHash);
        given(tssConfig.wrapsProvingKeyPath()).willReturn(archivePath.toString());
        environment.set(WrapsProvingKeyVerification.WRAPS_ARTIFACTS_ENV_VAR, extractionDir.toString());

        subject.ensureProvingKey(configuration, downloader);

        // No download (archive already present and verified); artifacts extracted; hash file written
        verifyNoInteractions(downloader);
        for (final var artifact : WrapsProvingKeyVerification.REQUIRED_ARTIFACT_FILES) {
            assertTrue(Files.isRegularFile(extractionDir.resolve(artifact)), "missing extracted artifact " + artifact);
        }
        final var hashFile = extractionDir.resolve(WrapsProvingKeyVerification.WRAPS_HASH_FILE_NAME);
        assertTrue(Files.isRegularFile(hashFile), "hash file was not written");
        assertEquals(archiveHash, Files.readString(hashFile).trim());
    }

    // ===== helpers =====

    private void givenConfigWithHashAndPath(final String bootstrapHash, final Path provingKeyPath) {
        given(tssConfig.wrapsProvingKeyDownloadEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn(bootstrapHash);
        given(tssConfig.wrapsProvingKeyPath()).willReturn(provingKeyPath.toString());
        // downloadUrl and retryInterval are only used when a download is triggered; mark lenient
        // so tests whose code path does not reach the download step don't fail on unused stubs
        Mockito.lenient().when(tssConfig.wrapsProvingKeyDownloadUrl()).thenReturn(DOWNLOAD_URL);
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

    private void setArtifactsEnvVar(final EnvironmentVariables environment) {
        environment.set(WrapsProvingKeyVerification.WRAPS_ARTIFACTS_ENV_VAR, tempDir.toString());
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
