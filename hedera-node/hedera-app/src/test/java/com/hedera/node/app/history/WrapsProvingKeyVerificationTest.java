// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.history.schemas.V073HistorySchema;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WrapsProvingKeyVerificationTest {

    private static final byte[] CONTENT_A = "test-content-a-for-proving-key".getBytes();
    private static final byte[] CONTENT_B = "test-content-b-different-key!!".getBytes();
    private static final Bytes HASH_A = CommonUtils.noThrowSha384HashOf(Bytes.wrap(CONTENT_A));
    private static final Bytes HASH_B = CommonUtils.noThrowSha384HashOf(Bytes.wrap(CONTENT_B));
    private static final String DOWNLOAD_URL = "https://s3.example.com/bucket/proving-key.tar.gz";

    @Mock
    private State state;

    @Mock
    private Configuration configuration;

    @Mock
    private TssConfig tssConfig;

    @Mock
    private WrapsProvingKeyDownloader downloader;

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private ReadableStates readableStates;

    @TempDir
    Path tempDir;

    private WrapsProvingKeyVerification subject;

    @BeforeEach
    void setUp() {
        // Use synchronous executor so async downloads run inline for testing
        subject = new WrapsProvingKeyVerification(Runnable::run);
    }

    // ===== verify() tests =====

    @Test
    void pendingHashStartsAsEmpty() {
        assertEquals(Bytes.EMPTY, subject.pendingHash());
    }

    @Test
    void throwsWhenWrapsEnabledAndBootstrapHashIsBlank() {
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.wrapsEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn("");

        assertThrows(IllegalArgumentException.class, () -> subject.verify(state, configuration, downloader));
    }

    @Test
    void skipsVerificationWhenWrapsNotEnabled() {
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.wrapsEnabled()).willReturn(false);

        subject.verify(state, configuration, downloader);

        assertEquals(Bytes.EMPTY, subject.pendingHash());
        verifyNoInteractions(downloader);
    }

    @Test
    void setsPendingHashWhenFileMatchesHash() throws IOException {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, CONTENT_A);
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenReadableHistoryStates(null);

        subject.verify(state, configuration, downloader);

        assertEquals(HASH_A, subject.pendingHash());
        verifyNoInteractions(downloader);
    }

    @Test
    void setsPendingHashEvenWhenAlreadyInState() throws IOException {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, CONTENT_A);
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenReadableHistoryStates(HASH_A);

        subject.verify(state, configuration, downloader);

        assertEquals(HASH_A, subject.pendingHash());
        verifyNoInteractions(downloader);
    }

    @Test
    void throwsOnUnreadableFile() throws IOException {
        final var path = tempDir.resolve("unreadable");
        Files.createDirectory(path);
        final var hash = "aa".repeat(48);
        givenConfigWithHashAndPath(hash, path);
        givenReadableHistoryStates(null);

        assertThrows(UncheckedIOException.class, () -> subject.verify(state, configuration, downloader));
    }

    // ===== async download tests =====

    @Test
    void downloadsAndSetsPendingHashWhenFileMissing() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenReadableHistoryStates(null);
        givenDownloaderWritesContent(path, CONTENT_A);

        subject.verify(state, configuration, downloader);

        assertEquals(HASH_A, subject.pendingHash());
        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @Test
    void downloadsAndSetsPendingHashOnHashMismatch() throws Exception {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, "wrong content on disk".getBytes());
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenReadableHistoryStates(null);
        givenDownloaderWritesContent(path, CONTENT_A);

        subject.verify(state, configuration, downloader);

        assertEquals(HASH_A, subject.pendingHash());
        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @Test
    void throwsWhenDownloadedFileHashStillMismatches() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        final var configHash = "aa".repeat(48); // won't match CONTENT_B's hash
        givenConfigWithHashAndPath(configHash, path);
        givenReadableHistoryStates(null);
        givenDownloaderWritesContent(path, CONTENT_B);

        // verify() captures the exception in the CompletableFuture
        subject.verify(state, configuration, downloader);
        assertEquals(Bytes.EMPTY, subject.pendingHash());

        // The exception surfaces when maybePersistPendingHash awaits the download
        final var thrown =
                assertThrows(CompletionException.class, () -> subject.maybePersistPendingHash(state, configuration));
        assertEquals(IllegalStateException.class, thrown.getCause().getClass());
    }

    @Test
    void pendingHashStaysEmptyOnDownloadIOException() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        final var configHash = "aa".repeat(48);
        givenConfigWithHashAndPath(configHash, path);
        givenReadableHistoryStates(null);
        doThrow(new IOException("network error")).when(downloader).download(anyString(), any());

        // IOException is caught and logged; does not propagate
        subject.verify(state, configuration, downloader);

        assertEquals(Bytes.EMPTY, subject.pendingHash());
    }

    @Test
    void passesConfiguredDownloadUrlToDownloader() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenReadableHistoryStates(null);
        givenDownloaderWritesContent(path, CONTENT_A);

        subject.verify(state, configuration, downloader);

        verify(downloader).download(eq(DOWNLOAD_URL), eq(path));
    }

    // ===== maybePersistPendingHash() tests =====

    @Test
    void persistIsNoOpWhenPendingHashIsEmpty() {
        subject.maybePersistPendingHash(state, configuration);

        verify(state, never()).getWritableStates(any());
    }

    @Test
    void persistWritesHashToStateWhenStateIsEmptyAndPendingHashIsSet() throws IOException {
        givenSubjectWithPendingHash(CONTENT_A);
        final var writableStates = givenWritableHistoryStates(null);

        subject.maybePersistPendingHash(state, configuration);

        verify(state).getWritableStates(HistoryService.NAME);
        verify((CommittableWritableStates) writableStates).commit();
    }

    @Test
    void persistLogsMatchWhenStateValueEqualsPendingHash() throws IOException {
        givenSubjectWithPendingHash(CONTENT_A);
        final var writableStates = givenWritableHistoryStates(HASH_A);

        subject.maybePersistPendingHash(state, configuration);

        verify((CommittableWritableStates) writableStates, never()).commit();
    }

    @Test
    void persistOverwritesStateWhenConfigHashMatchesPendingHash() throws IOException {
        givenSubjectWithPendingHash(CONTENT_A);
        final var writableStates = givenWritableHistoryStates(HASH_B);
        // givenConfigWithHashAndPath already set wrapsProvingKeyHash to HASH_A.toHex(),
        // which matches pendingHash — triggering the overwrite branch

        subject.maybePersistPendingHash(state, configuration);

        verify(state).getWritableStates(HistoryService.NAME);
        verify((CommittableWritableStates) writableStates).commit();
    }

    @Test
    void persistThrowsWhenConfigHashDoesNotMatchPendingHash() throws IOException {
        givenSubjectWithPendingHash(CONTENT_A);
        givenWritableHistoryStates(HASH_B);
        // Override the config hash to something that doesn't match pendingHash (HASH_A)
        given(tssConfig.wrapsProvingKeyHash())
                .willReturn(Bytes.wrap("something-else").toHex());

        assertThrows(IllegalStateException.class, () -> subject.maybePersistPendingHash(state, configuration));
    }

    @Test
    void persistThrowsWhenConfigHashIsBlankAndStateValueDiffers() throws IOException {
        givenSubjectWithPendingHash(CONTENT_A);
        givenWritableHistoryStates(HASH_B);
        // Override the config hash to blank
        given(tssConfig.wrapsProvingKeyHash()).willReturn("");

        assertThrows(IllegalStateException.class, () -> subject.maybePersistPendingHash(state, configuration));
    }

    // ===== awaitDownloadIfPending() tests =====

    @Test
    void throwsOnDownloadTimeout() {
        subject = new WrapsProvingKeyVerification(task -> {}, Duration.ofMillis(100));
        final var path = tempDir.resolve("missing.key");
        givenConfigWithHashAndPath("aa".repeat(48), path);
        givenReadableHistoryStates(null);

        // verify() creates a future that never completes (no-op executor)
        subject.verify(state, configuration, downloader);

        assertThrows(IllegalStateException.class, () -> subject.maybePersistPendingHash(state, configuration));
    }

    @Test
    void throwsOnInterruptDuringDownloadWait() {
        subject = new WrapsProvingKeyVerification(task -> {}, Duration.ofSeconds(30));
        final var path = tempDir.resolve("missing.key");
        givenConfigWithHashAndPath("aa".repeat(48), path);
        givenReadableHistoryStates(null);

        // verify() creates a future that never completes (no-op executor)
        subject.verify(state, configuration, downloader);

        Thread.currentThread().interrupt();
        assertThrows(IllegalStateException.class, () -> subject.maybePersistPendingHash(state, configuration));
        // Clear the interrupt flag so it doesn't affect other tests
        assertTrue(Thread.interrupted());
    }

    // ===== helpers =====

    private void givenConfigWithHashAndPath(final String bootstrapHash, final Path provingKeyPath) {
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.wrapsEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn(bootstrapHash);
        given(tssConfig.wrapsProvingKeyPath()).willReturn(provingKeyPath.toString());
        given(tssConfig.wrapsProvingKeyDownloadUrl()).willReturn(DOWNLOAD_URL);
    }

    private void givenDownloaderWritesContent(final Path path, final byte[] content) throws IOException {
        doAnswer(inv -> {
                    Files.write(inv.getArgument(1), content);
                    return null;
                })
                .when(downloader)
                .download(anyString(), eq(path));
    }

    private void givenReadableHistoryStates(final Bytes existingHash) {
        given(state.getReadableStates(HistoryService.NAME)).willReturn(readableStates);
        final var protoBytes = existingHash != null
                ? new com.hedera.hapi.node.state.primitives.ProtoBytes(existingHash)
                : new com.hedera.hapi.node.state.primitives.ProtoBytes(Bytes.EMPTY);
        Mockito.lenient()
                .when(readableStates
                        .<com.hedera.hapi.node.state.primitives.ProtoBytes>getSingleton(
                                V073HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID)
                        .get())
                .thenReturn(protoBytes);
    }

    /**
     * Sets pendingHash naturally by writing a file and calling verify() — no reflection needed.
     */
    private void givenSubjectWithPendingHash(final byte[] content) throws IOException {
        final var path = tempDir.resolve("pending-" + System.nanoTime() + ".key");
        Files.write(path, content);
        final var hash = CommonUtils.noThrowSha384HashOf(Bytes.wrap(content));
        givenConfigWithHashAndPath(hash.toHex(), path);
        givenReadableHistoryStates(null);
        subject.verify(state, configuration, downloader);
        assertEquals(hash, subject.pendingHash());
    }

    private WritableStates givenWritableHistoryStates(final Bytes existingHash) {
        final var writableStates = Mockito.mock(
                WritableStates.class,
                withSettings().extraInterfaces(CommittableWritableStates.class).defaultAnswer(RETURNS_DEEP_STUBS));
        given(state.getWritableStates(HistoryService.NAME)).willReturn(writableStates);
        final var protoBytes = existingHash != null
                ? new com.hedera.hapi.node.state.primitives.ProtoBytes(existingHash)
                : new com.hedera.hapi.node.state.primitives.ProtoBytes(Bytes.EMPTY);
        Mockito.lenient()
                .when(writableStates
                        .<com.hedera.hapi.node.state.primitives.ProtoBytes>getSingleton(
                                V073HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID)
                        .get())
                .thenReturn(protoBytes);
        return writableStates;
    }
}
