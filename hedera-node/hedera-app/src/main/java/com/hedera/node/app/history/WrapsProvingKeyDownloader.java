// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Downloads the WRAPS proving key file from a URL to a local path.
 */
@FunctionalInterface
public interface WrapsProvingKeyDownloader {
    /**
     * Synchronously downloads the WRAPS proving key from the given URL to the target path.
     *
     * @param downloadUrl the URL to download from
     * @param targetPath the local path to write the downloaded file to
     * @throws IOException if the download fails
     */
    void download(@NonNull String downloadUrl, @NonNull Path targetPath) throws IOException;
}
