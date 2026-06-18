// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static java.util.Objects.requireNonNull;

import com.hedera.bucky.S3Client;
import com.hedera.bucky.S3ClientInitializationException;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link BlockUploader} backed by the {@code com.hedera.bucky} S3 client (AWS Signature V4). For GCP it targets the
 * Google Cloud Storage XML API interoperability endpoint using HMAC interoperability keys; the same code path serves
 * native S3 for AWS. Each block file is streamed to the bucket in fixed-size chunks (the {@code .gz} artifacts are
 * already compressed, so they are uploaded verbatim).
 *
 * <p>The {@link S3Client} is created lazily through {@link S3ClientFactory} — a seam that lets tests supply a stub
 * client without reaching a real bucket. Each file is retried up to {@code maxRetries} times with exponential backoff.
 * The overall hard deadline is enforced by {@code IssBlockUploadCoordinator} (which runs this on a bounded worker), so
 * this class does not time-box itself. All failures are logged and swallowed (the caller is on the shutdown path).
 *
 * <p>Object keys are {@code {prefix}/{node}/{category}/{incidentFolder}/{paddedBlockNumber}/{fileName}}: {@code category}
 * is {@code iss} or {@code triage}, {@code incidentFolder} groups all blocks from one event, and the padded block
 * number (from the file name) groups a pending block's {@code .pnd.gz} contents with its {@code .pnd.json} proof.
 */
class BuckyBlockUploader implements BlockUploader {
    private static final Logger log = LogManager.getLogger(BuckyBlockUploader.class);

    /** Chunk size used to stream a file into the multipart upload. */
    private static final int CHUNK_SIZE = 1024 * 1024;
    /** Base backoff between upload retries; doubles each attempt, capped at {@link #MAX_BACKOFF_MS}. */
    private static final long BASE_BACKOFF_MS = 200;
    /** Upper cap on the per-retry backoff. */
    private static final long MAX_BACKOFF_MS = 20_000;
    /** Block-file extensions, longest first, stripped to recover the padded block number used as the key folder. */
    private static final List<String> EXTENSIONS = List.of(".pnd.json", ".open.gz", ".iss.gz", ".pnd.gz", ".blk.gz");

    /** Seam so tests can supply a stub {@link S3Client} instead of constructing a real one. */
    @FunctionalInterface
    public interface S3ClientFactory {
        @NonNull
        S3Client create(@NonNull FailureBlockUploadConfig config, @NonNull BucketCredentials credentials)
                throws S3ClientInitializationException;
    }

    private final FailureBlockUploadConfig config;
    private final String nodeAccountString;
    private final Path credentialsFile;
    private final S3ClientFactory clientFactory;

    BuckyBlockUploader(
            @NonNull final FailureBlockUploadConfig config,
            @NonNull final String nodeAccountString,
            @NonNull final Path credentialsFile) {
        this(config, nodeAccountString, credentialsFile, BuckyBlockUploader::newS3Client);
    }

    // visible for testing
    BuckyBlockUploader(
            @NonNull final FailureBlockUploadConfig config,
            @NonNull final String nodeAccountString,
            @NonNull final Path credentialsFile,
            @NonNull final S3ClientFactory clientFactory) {
        this.config = requireNonNull(config);
        this.nodeAccountString = requireNonNull(nodeAccountString);
        this.credentialsFile = requireNonNull(credentialsFile);
        this.clientFactory = requireNonNull(clientFactory);
    }

    @Override
    @NonNull
    public List<String> uploadBlockFiles(
            @NonNull final UploadCategory category,
            @NonNull final String incidentFolder,
            @NonNull final List<Path> contentsFiles) {
        requireNonNull(category);
        requireNonNull(incidentFolder);
        requireNonNull(contentsFiles);
        if (contentsFiles.isEmpty()) {
            return List.of();
        }
        final BucketCredentials credentials;
        try {
            credentials = BucketCredentials.load(credentialsFile);
        } catch (final RuntimeException e) {
            log.error("Cannot upload ISS block files: {}", e.getMessage());
            return List.of();
        }
        final List<String> uploaded = new ArrayList<>();
        try (final S3Client s3 = clientFactory.create(config, credentials)) {
            for (final Path contents : contentsFiles) {
                final String uri = uploadWithRetry(s3, category, incidentFolder, contents);
                if (uri != null) {
                    uploaded.add(uri);
                }
                final Path sidecar = proofSidecarOf(contents);
                if (sidecar != null && Files.exists(sidecar)) {
                    final String sidecarUri = uploadWithRetry(s3, category, incidentFolder, sidecar);
                    if (sidecarUri != null) {
                        uploaded.add(sidecarUri);
                    }
                }
            }
        } catch (final Exception e) {
            log.error("Failed to initialize bucket client for ISS block upload", e);
        }
        return uploaded;
    }

    @Nullable
    private String uploadWithRetry(
            @NonNull final S3Client s3,
            @NonNull final UploadCategory category,
            @NonNull final String incidentFolder,
            @NonNull final Path file) {
        final int attempts = Math.max(1, config.maxRetries() + 1);
        final String objectKey = objectKeyFor(category, incidentFolder, file);
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (final InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                s3.uploadFile(objectKey, config.storageClass(), new ChunkedIterator(in), contentTypeFor(file));
                final String uri = uriFor(objectKey);
                log.warn(
                        "Uploaded ISS block file {} to {} (bucket={}, key={})",
                        file.getFileName(),
                        uri,
                        config.bucketName(),
                        objectKey);
                return uri;
            } catch (final Exception e) {
                last = e;
                if (attempt >= attempts) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << (attempt - 1)));
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error(
                "Failed to upload ISS block file {} after {} attempt(s) (key={})",
                file.getFileName(),
                attempts,
                objectKey,
                last);
        return null;
    }

    private String objectKeyFor(
            @NonNull final UploadCategory category, @NonNull final String incidentFolder, @NonNull final Path file) {
        final String fileName = file.getFileName().toString();
        final String prefix = stripTrailingSlash(config.objectKeyPrefix());
        final String base = prefix.isEmpty() ? "" : prefix + "/";
        return base + nodeAccountString + "/" + category.segment() + "/" + incidentFolder + "/" + baseNameOf(fileName)
                + "/" + fileName;
    }

    private String uriFor(@NonNull final String objectKey) {
        return stripTrailingSlash(config.endpoint()) + "/" + config.bucketName() + "/" + objectKey;
    }

    /** The padded block number = the file name with its block-file extension stripped. */
    private static String baseNameOf(@NonNull final String fileName) {
        for (final String ext : EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                return fileName.substring(0, fileName.length() - ext.length());
            }
        }
        return fileName;
    }

    /** The {@code .pnd.json} proof sibling of a {@code .pnd.gz} contents file, or {@code null} for other kinds. */
    @Nullable
    private static Path proofSidecarOf(@NonNull final Path contents) {
        final String name = contents.getFileName().toString();
        if (name.endsWith(".pnd.gz")) {
            return contents.resolveSibling(name.substring(0, name.length() - ".pnd.gz".length()) + ".pnd.json");
        }
        return null;
    }

    private static String stripTrailingSlash(@NonNull final String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String contentTypeFor(@NonNull final Path file) {
        final String name = file.getFileName().toString();
        if (name.endsWith(".gz")) {
            return "application/gzip";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    @NonNull
    private static S3Client newS3Client(
            @NonNull final FailureBlockUploadConfig config, @NonNull final BucketCredentials credentials)
            throws S3ClientInitializationException {
        return new S3Client(
                config.region(),
                config.endpoint(),
                config.bucketName(),
                credentials.accessKey(),
                credentials.secretKey());
    }

    /** Streams a file as a sequence of fixed-size byte arrays; the underlying stream is closed by the caller. */
    private static final class ChunkedIterator implements Iterator<byte[]> {
        private final InputStream in;
        private byte[] next;
        private boolean done;

        ChunkedIterator(@NonNull final InputStream in) {
            this.in = requireNonNull(in);
        }

        @Override
        public boolean hasNext() {
            if (done) {
                return false;
            }
            if (next != null) {
                return true;
            }
            try {
                final byte[] buffer = new byte[CHUNK_SIZE];
                final int read = in.readNBytes(buffer, 0, CHUNK_SIZE);
                if (read <= 0) {
                    done = true;
                    return false;
                }
                next = (read == CHUNK_SIZE) ? buffer : Arrays.copyOf(buffer, read);
                return true;
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public byte[] next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final byte[] result = next;
            next = null;
            return result;
        }
    }
}
