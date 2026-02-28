// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;

/**
 * Stub interface for downloading the WRAPS proving key file.
 */
public interface WrapsProvingKeyDownloader {
    /**
     * Initiates a download of the WRAPS proving key to the given path, expected to have the given hash.
     * @param path the path to download to
     * @param expectedHash the expected hex-encoded SHA-384 hash of the file
     */
    void initiateDownload(@NonNull Path path, @NonNull String expectedHash);
}
