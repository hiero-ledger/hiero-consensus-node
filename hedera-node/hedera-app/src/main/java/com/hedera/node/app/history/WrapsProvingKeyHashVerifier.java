// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility that verifies the WRAPS proving key file on disk against an expected SHA-384 hash.
 * If the file is missing or its hash does not match, the downloader is invoked. If the hash
 * matches and no hash is yet stored in state, the pending hash is set on the history service.
 */
public class WrapsProvingKeyHashVerifier {
    private static final Logger log = LogManager.getLogger(WrapsProvingKeyHashVerifier.class);

    private final Path provingKeyPath;
    private final String bootstrapHash;
    private final HistoryService historyService;
    private final Bytes existingHashInState;
    private final WrapsProvingKeyDownloader downloader;

    public WrapsProvingKeyHashVerifier(
            @NonNull final Path provingKeyPath,
            @NonNull final String bootstrapHash,
            @NonNull final HistoryService historyService,
            @Nullable final Bytes existingHashInState,
            @NonNull final WrapsProvingKeyDownloader downloader) {
        this.provingKeyPath = requireNonNull(provingKeyPath);
        this.bootstrapHash = requireNonNull(bootstrapHash);
        this.historyService = requireNonNull(historyService);
        this.existingHashInState = existingHashInState;
        this.downloader = requireNonNull(downloader);
    }

    /**
     * Verifies the proving key file and, if appropriate, sets the pending hash on the history service.
     */
    public void verifyAndSetPendingHash() {
        if (bootstrapHash.isBlank()) {
            return;
        }
        if (!Files.exists(provingKeyPath)) {
            log.info("WRAPS proving key file not found at {}, initiating download", provingKeyPath);
            downloader.initiateDownload(provingKeyPath, bootstrapHash);
            return;
        }
        final Bytes fileHash;
        try {
            fileHash = CommonUtils.noThrowSha384HashOf(Bytes.wrap(Files.readAllBytes(provingKeyPath)));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read WRAPS proving key file at " + provingKeyPath, e);
        }
        final var expectedHash = Bytes.fromHex(bootstrapHash);
        if (!fileHash.equals(expectedHash)) {
            log.warn(
                    "WRAPS proving key hash mismatch at {} (expected={}, actual={}), initiating download",
                    provingKeyPath,
                    expectedHash,
                    fileHash);
            downloader.initiateDownload(provingKeyPath, bootstrapHash);
            return;
        }
        if (existingHashInState == null) {
            log.info("WRAPS proving key hash verified, setting pending hash");
            historyService.setPendingExpectedWrapsProvingKeyHash(expectedHash);
        } else {
            log.info("WRAPS proving key hash verified and already in state");
        }
    }
}
