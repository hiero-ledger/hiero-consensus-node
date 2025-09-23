// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.cache;

import com.hedera.node.config.data.S3Config;
import com.swirlds.common.s3.S3Client;
import com.swirlds.common.s3.S3ClientInitializationException;
import com.swirlds.common.s3.S3ResponseException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An S3 client for uploading files into a configured bucket.
 */
public class S3Uploader {
    private final S3Client s3Client;
    private final S3Config s3Config;
    private static final String ZIP_CONTENT_TYPE = "application/zip";

    public S3Uploader(final @NonNull S3Config config) {
        this.s3Config = Objects.requireNonNull(config);
        try {
            s3Client = new S3Client(
                    s3Config.regionName(),
                    s3Config.endpointUrl(),
                    s3Config.bucketName(),
                    s3Config.accessKey(),
                    s3Config.secretKey());
        } catch (S3ClientInitializationException e) {
            throw new IllegalStateException(e);
        }
    }

    public void uploadFilesAsZippedFolder(final @NonNull String key, final @NonNull List<Path> files) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(files, "files must not be null");
        try {
            final byte[] byteArray = zipCompress(files);
            uploadFileContent(key, byteArray, ZIP_CONTENT_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("Error uploading file:" + key + " to:" + s3Config.bucketName(), e);
        }
    }

    public void uploadFile(final @NonNull String key, final @NonNull Path path, final @NonNull String contentType) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        try {
            final byte[] byteArray = Files.readAllBytes(path);
            uploadFileContent(key, byteArray, contentType);
        } catch (IOException e) {
            throw new IllegalStateException("Error uploading file:" + path + " to:" + s3Config.bucketName(), e);
        }
    }

    public void uploadFileContent(
            final @NonNull String key, final @NonNull byte[] byteArray, final @NonNull String contentType)
            throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(byteArray, "byteArray must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        try {
            s3Client.uploadFile(key, s3Config.storageClass(), SingleElementIterator.of(byteArray), contentType);
        } catch (IOException | S3ResponseException e) {
            throw new IllegalStateException("Error uploading file:" + key + " to:" + s3Config.bucketName(), e);
        }
    }

    private static byte[] zipCompress(@NonNull final List<Path> inputFiles) throws IOException {
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                final ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (final Path file : inputFiles) {
                if (Files.isRegularFile(file)) {
                    final ZipEntry entry = new ZipEntry(file.getFileName().toString());
                    zos.putNextEntry(entry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                }
            }
            zos.finish();
            return baos.toByteArray();
        }
    }

    private static class SingleElementIterator<T> implements Iterator<T> {
        private final T element;
        private boolean hasNext = true;

        static <T> SingleElementIterator<T> of(T element) {
            return new SingleElementIterator<>(element);
        }

        SingleElementIterator(T element) {
            this.element = element;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public T next() {
            if (hasNext) {
                hasNext = false;
                return element;
            } else {
                throw new NoSuchElementException();
            }
        }
    }
}
