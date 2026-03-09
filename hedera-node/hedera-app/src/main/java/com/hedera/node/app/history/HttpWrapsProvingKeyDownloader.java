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

    public void download(@NonNull final String downloadUrl, @NonNull final Path targetPath) throws IOException {
        try (final var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build()) {
            final var request =
                    HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
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
