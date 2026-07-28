// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Downloads the WRAPS proving key from an HTTP URL.
 */
public class HttpWrapsProvingKeyDownloader {

    /**
     * Bounds connect and time-to-response-headers, and is re-applied to each of the JDK client's up-to-five
     * attempts.
     *
     * <p>It does not bound the body transfer, since {@link HttpRequest.Builder#timeout(Duration)} stops applying
     * once the headers are in, so a server that answers and then stalls mid-body can still block the calling
     * thread. That is accepted rather than overlooked: the download URL is a network property pointing at our own
     * artifact host, the transfer runs off the consensus path, and a node that never gets the proving key stays
     * up. Bounding the body correctly needs per-chunk progress tracking, which is a poor trade at that risk.
     */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(1);

    public void download(@NonNull final String downloadUrl, @NonNull final Path targetPath) throws IOException {
        download(downloadUrl, targetPath, RESPONSE_TIMEOUT);
    }

    void download(
            @NonNull final String downloadUrl, @NonNull final Path targetPath, @NonNull final Duration responseTimeout)
            throws IOException {
        try (final var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(30))
                .build()) {
            final var request = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .timeout(responseTimeout)
                    .GET()
                    .build();
            final var response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(
                            targetPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE));
            final int statusCode = response.statusCode();
            if (statusCode == 404) {
                throw new IOException("File not found at URL: " + downloadUrl);
            } else if (statusCode != 200) {
                throw new IOException("Failed to download from " + downloadUrl + " (HTTP status " + statusCode + ")");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + downloadUrl, e);
        }
    }
}
