// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.history.schemas.V0730HistorySchema;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableStates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    private static final Bytes HASH_A = noThrowSha384HashOf(Bytes.wrap(CONTENT_A));
    private static final Bytes HASH_B = noThrowSha384HashOf(Bytes.wrap(CONTENT_B));
    private static final String DOWNLOAD_URL = "https://s3.example.com/bucket/proving-key.tar.gz";

    @Mock
    private State state;

    @Mock
    private Configuration configuration;

    @Mock
    private TssConfig tssConfig;

    @Mock
    private HttpWrapsProvingKeyDownloader downloader;

    @TempDir
    Path tempDir;

    private WrapsProvingKeyVerification subject;

    @BeforeEach
    void setUp() {
        // Use synchronous executor so async downloads run inline for testing
        subject = new WrapsProvingKeyVerification(Runnable::run);
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        Mockito.lenient().when(tssConfig.wrapsProvingKeyRetryInterval()).thenReturn(Duration.ofSeconds(60));
    }

    // ===== ensureProvingKey() tests =====

    @Test
    void throwsWhenWrapsEnabledAndBootstrapHashIsBlank() {
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.wrapsEnabled()).willReturn(true);
        given(tssConfig.wrapsProvingKeyHash()).willReturn("");

        assertThrows(IllegalArgumentException.class, () -> subject.ensureProvingKey(state, configuration, downloader));
    }

    @Test
    void skipsVerificationWhenWrapsNotEnabled() {
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.wrapsEnabled()).willReturn(false);

        subject.ensureProvingKey(state, configuration, downloader);

        verifyNoInteractions(downloader);
    }

    @Test
    void persistsAndSkipsDownloadWhenFileMatchesHash() throws IOException {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, CONTENT_A);
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        final var writableStates = givenWritableHistoryStates(null);

        subject.ensureProvingKey(state, configuration, downloader);

        verifyNoInteractions(downloader);
        verify(state).getWritableStates(HistoryService.NAME);
        verify((CommittableWritableStates) writableStates).commit();
    }

    @Test
    void skipsCommitWhenHashAlreadyInState() throws IOException {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, CONTENT_A);
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenWritableHistoryStates(HASH_A);

        subject.ensureProvingKey(state, configuration, downloader);

        verifyNoInteractions(downloader);
    }

    @Test
    void throwsOnUnreadableFile() throws IOException {
        final var path = tempDir.resolve("unreadable");
        Files.createDirectory(path);
        final var hash = "aa".repeat(48);
        givenConfigWithHashAndPath(hash, path);
        givenWritableHistoryStates(null);

        assertThrows(UncheckedIOException.class, () -> subject.ensureProvingKey(state, configuration, downloader));
    }

    // ===== async download tests =====

    @Test
    void downloadsWhenFileMissing() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenWritableHistoryStates(null);
        givenDownloaderWritesContent(path, CONTENT_A);

        subject.ensureProvingKey(state, configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @Test
    void downloadsOnHashMismatch() throws Exception {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, "wrong content on disk".getBytes());
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenWritableHistoryStates(null);
        givenDownloaderWritesContent(path, CONTENT_A);

        subject.ensureProvingKey(state, configuration, downloader);

        verify(downloader).download(DOWNLOAD_URL, path);
    }

    @Test
    void continuesWhenDownloadedFileHashStillMismatches() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        final var configHash = "aa".repeat(48); // won't match CONTENT_B's hash
        givenConfigWithHashAndPath(configHash, path);
        givenWritableHistoryStates(null);
        givenDownloaderWritesContent(path, CONTENT_B);

        subject.ensureProvingKey(state, configuration, downloader);
    }

    @Test
    void continuesOnDownloadIOException() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        final var configHash = "aa".repeat(48);
        givenConfigWithHashAndPath(configHash, path);
        givenWritableHistoryStates(null);
        doThrow(new IOException("network error")).when(downloader).download(anyString(), any());

        subject.ensureProvingKey(state, configuration, downloader);
    }

    @Test
    void passesConfiguredDownloadUrlToDownloader() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        givenWritableHistoryStates(null);
        givenDownloaderWritesContent(path, CONTENT_A);

        subject.ensureProvingKey(state, configuration, downloader);

        verify(downloader).download(eq(DOWNLOAD_URL), eq(path));
    }

    @Test
    void persistsHashEvenWhenFileMissingAndDownloadFails() throws Exception {
        final var path = tempDir.resolve("nonexistent.key");
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        final var writableStates = givenWritableHistoryStates(null);
        doThrow(new IOException("network error")).when(downloader).download(anyString(), any());

        subject.ensureProvingKey(state, configuration, downloader);

        verify(state).getWritableStates(HistoryService.NAME);
        verify((CommittableWritableStates) writableStates).commit();
    }

    // ===== persistence during ensureProvingKey() tests =====

    @Test
    void overwritesStateWhenConfigHashDiffers() throws IOException {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, CONTENT_A);
        givenConfigWithHashAndPath(HASH_A.toHex(), path);
        final var writableStates = givenWritableHistoryStates(HASH_B);

        subject.ensureProvingKey(state, configuration, downloader);

        verify(state).getWritableStates(HistoryService.NAME);
        verify((CommittableWritableStates) writableStates).commit();
    }

    // ===== helpers =====

    private void givenConfigWithHashAndPath(final String bootstrapHash, final Path provingKeyPath) {
        given(configuration.getConfigData(TssConfig.class)).willReturn(tssConfig);
        given(tssConfig.wrapsEnabled()).willReturn(true);
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

    private WritableStates givenWritableHistoryStates(final Bytes existingHash) {
        final var writableStates = Mockito.mock(
                WritableStates.class,
                withSettings().extraInterfaces(CommittableWritableStates.class).defaultAnswer(RETURNS_DEEP_STUBS));
        given(state.getWritableStates(HistoryService.NAME)).willReturn(writableStates);
        final var protoBytes = existingHash != null ? new ProtoBytes(existingHash) : new ProtoBytes(Bytes.EMPTY);
        Mockito.lenient()
                .when(writableStates
                        .<ProtoBytes>getSingleton(V0730HistorySchema.WRAPS_PROVING_KEY_HASH_STATE_ID)
                        .get())
                .thenReturn(protoBytes);
        return writableStates;
    }
}
