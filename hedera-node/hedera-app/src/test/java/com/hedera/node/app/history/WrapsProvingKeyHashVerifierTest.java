// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WrapsProvingKeyHashVerifierTest {

    @Mock
    private HistoryService historyService;

    @Mock
    private WrapsProvingKeyDownloader downloader;

    @TempDir
    Path tempDir;

    @Test
    void skipsVerificationWhenBootstrapHashIsBlank() {
        final var verifier = new WrapsProvingKeyHashVerifier(
                tempDir.resolve("proving.key"), "", historyService, null, downloader);

        verifier.verifyAndSetPendingHash();

        verifyNoInteractions(historyService);
        verifyNoInteractions(downloader);
    }

    @Test
    void initiatesDownloadWhenFileDoesNotExist() {
        final var path = tempDir.resolve("nonexistent.key");
        final var hash = "abcdef";
        final var verifier = new WrapsProvingKeyHashVerifier(path, hash, historyService, null, downloader);

        verifier.verifyAndSetPendingHash();

        verify(downloader).initiateDownload(path, hash);
        verifyNoInteractions(historyService);
    }

    @Test
    void initiatesDownloadOnHashMismatch() throws IOException {
        final var path = tempDir.resolve("proving.key");
        Files.write(path, "wrong content".getBytes());
        // Use a hash that won't match
        final var wrongHash = "aa".repeat(48); // 96 hex chars = 48 bytes (SHA-384)
        final var verifier = new WrapsProvingKeyHashVerifier(path, wrongHash, historyService, null, downloader);

        verifier.verifyAndSetPendingHash();

        verify(downloader).initiateDownload(path, wrongHash);
        verifyNoInteractions(historyService);
    }

    @Test
    void setsPendingHashWhenMatchAndStateEmpty() throws IOException {
        final var path = tempDir.resolve("proving.key");
        final var content = "test proving key content".getBytes();
        Files.write(path, content);
        final var actualHash = CommonUtils.noThrowSha384HashOf(Bytes.wrap(content));
        final var hexHash = actualHash.toHex();

        final var verifier = new WrapsProvingKeyHashVerifier(path, hexHash, historyService, null, downloader);

        verifier.verifyAndSetPendingHash();

        verify(historyService).setPendingExpectedWrapsProvingKeyHash(actualHash);
        verifyNoInteractions(downloader);
    }

    @Test
    void doesNotSetPendingHashWhenAlreadyInState() throws IOException {
        final var path = tempDir.resolve("proving.key");
        final var content = "test proving key content".getBytes();
        Files.write(path, content);
        final var actualHash = CommonUtils.noThrowSha384HashOf(Bytes.wrap(content));
        final var hexHash = actualHash.toHex();

        final var verifier =
                new WrapsProvingKeyHashVerifier(path, hexHash, historyService, actualHash, downloader);

        verifier.verifyAndSetPendingHash();

        verifyNoInteractions(downloader);
        // historyService should NOT be called to set pending hash since it's already in state
        verify(historyService, org.mockito.Mockito.never()).setPendingExpectedWrapsProvingKeyHash(actualHash);
    }

    @Test
    void throwsOnUnreadableFile() throws IOException {
        final var path = tempDir.resolve("unreadable");
        // Create a directory where a file is expected — reading it will fail
        Files.createDirectory(path);
        final var hash = "aa".repeat(48);
        final var verifier = new WrapsProvingKeyHashVerifier(path, hash, historyService, null, downloader);

        assertThrows(UncheckedIOException.class, verifier::verifyAndSetPendingHash);
    }
}
