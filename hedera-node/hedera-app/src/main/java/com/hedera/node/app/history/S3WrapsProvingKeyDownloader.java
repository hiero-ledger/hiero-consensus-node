// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import com.hedera.node.app.s3.S3Client;
import com.hedera.node.app.s3.S3ClientException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * Downloads the WRAPS proving key from an S3-compatible URL using {@link S3Client}.
 *
 * <p>Expects a path-style URL of the form {@code https://host/bucket/key}.
 */
public class S3WrapsProvingKeyDownloader implements WrapsProvingKeyDownloader {

    @Override
    public void download(@NonNull final String downloadUrl, @NonNull final Path targetPath) throws IOException {
        final var uri = URI.create(downloadUrl);
        final var pathSegment = uri.getPath();
        if (pathSegment == null || pathSegment.length() < 2) {
            throw new IOException("Invalid S3 URL (missing bucket/key path): " + downloadUrl);
        }
        // Strip leading '/' then split into bucket and key at the first '/'
        final var withoutLeadingSlash = pathSegment.substring(1);
        final var slashIndex = withoutLeadingSlash.indexOf('/');
        if (slashIndex <= 0) {
            throw new IOException("Invalid S3 URL (missing key after bucket): " + downloadUrl);
        }
        final var bucket = withoutLeadingSlash.substring(0, slashIndex);
        final var key = withoutLeadingSlash.substring(slashIndex + 1);
        final var endpoint =
                uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/";

        try (final var s3Client = new S3Client(endpoint, bucket)) {
            final long bytesDownloaded = s3Client.downloadFile(key, targetPath);
            if (bytesDownloaded == -1) {
                throw new IOException("Object not found in S3: " + key);
            }
        } catch (final S3ClientException e) {
            throw new IOException("Failed to download from S3: " + downloadUrl, e);
        }
    }
}
