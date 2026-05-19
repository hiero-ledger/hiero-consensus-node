// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static java.util.Objects.requireNonNull;
import static org.hiero.base.file.FileUtils.getAbsolutePath;

import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shared filesystem conventions for dev TSS key material.
 */
public final class TssKeyFiles {
    public static final String HINTS_SUB_DIRECTORY = "hints";
    public static final String WRAPS_SUB_DIRECTORY = "wraps";
    public static final String BLS_PRIVATE_KEY_FILE = "bls.bin";
    public static final String SCHNORR_KEY_PAIR_FILE = "schnorr.bin";

    private static final Pattern SEQ_NO_DIR_PATTERN = Pattern.compile("\\d+");

    private TssKeyFiles() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads the latest BLS private key with sequence number at most {@code seqNo}, if present.
     */
    public static Optional<Bytes> readBlsPrivateKey(@NonNull final Configuration config, final long seqNo) {
        requireNonNull(config);
        return latestContentPath(basePath(config).resolve(HINTS_SUB_DIRECTORY), seqNo, BLS_PRIVATE_KEY_FILE)
                .map(TssKeyFiles::readBytes);
    }

    /**
     * Writes the BLS private key at the exact sequence number.
     */
    public static void writeBlsPrivateKey(
            @NonNull final Configuration config, final long seqNo, @NonNull final Bytes key) {
        requireNonNull(config);
        requireNonNull(key);
        writeBytes(pathFor(basePath(config).resolve(HINTS_SUB_DIRECTORY), seqNo, BLS_PRIVATE_KEY_FILE), key);
    }

    /**
     * Reads the latest Schnorr key pair with sequence number at most {@code seqNo}, if present.
     */
    public static Optional<SchnorrKeyPair> readSchnorrKeyPair(@NonNull final Configuration config, final long seqNo) {
        requireNonNull(config);
        return latestContentPath(basePath(config).resolve(WRAPS_SUB_DIRECTORY), seqNo, SCHNORR_KEY_PAIR_FILE)
                .map(TssKeyFiles::readSchnorrKeyPair);
    }

    /**
     * Writes the Schnorr key pair at the exact sequence number.
     */
    public static void writeSchnorrKeyPair(
            @NonNull final Configuration config, final long seqNo, @NonNull final SchnorrKeyPair keyPair) {
        requireNonNull(config);
        requireNonNull(keyPair);
        writeBytes(
                pathFor(basePath(config).resolve(WRAPS_SUB_DIRECTORY), seqNo, SCHNORR_KEY_PAIR_FILE),
                Bytes.wrap(keyPair.toDelimitedBytes()));
    }

    private static Path basePath(@NonNull final Configuration config) {
        return getAbsolutePath(config.getConfigData(TssConfig.class).tssKeysPath());
    }

    private static Optional<Path> latestContentPath(
            @NonNull final Path basePath, final long seqNo, @NonNull final String fileName) {
        requireNonNull(basePath);
        requireNonNull(fileName);
        if (!Files.isDirectory(basePath)) {
            return Optional.empty();
        }
        try (final var contents = Files.list(basePath)) {
            return contents.filter(Files::isDirectory)
                    .filter(TssKeyFiles::isSequenceDirectory)
                    .map(TssKeyFiles::sequenceNumberOf)
                    .filter(id -> id <= seqNo)
                    .sorted((a, b) -> Long.compare(b, a))
                    .map(id -> pathFor(basePath, id, fileName))
                    .filter(Files::exists)
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list TSS keys in " + basePath.toAbsolutePath(), e);
        }
    }

    private static Path pathFor(@NonNull final Path basePath, final long seqNo, @NonNull final String fileName) {
        return basePath.resolve(String.valueOf(seqNo)).resolve(fileName);
    }

    private static boolean isSequenceDirectory(@NonNull final Path dir) {
        return SEQ_NO_DIR_PATTERN.matcher(dir.getFileName().toString()).matches();
    }

    private static long sequenceNumberOf(@NonNull final Path dir) {
        return Long.parseLong(dir.getFileName().toString());
    }

    private static Bytes readBytes(@NonNull final Path path) {
        try {
            return Bytes.wrap(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read TSS key from " + path.toAbsolutePath(), e);
        }
    }

    private static SchnorrKeyPair readSchnorrKeyPair(@NonNull final Path path) {
        return SchnorrKeyPair.fromDelimited(readBytes(path).toByteArray());
    }

    private static void writeBytes(@NonNull final Path path, @NonNull final Bytes bytes) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write TSS key to " + path.toAbsolutePath(), e);
        }
    }

    /**
     * A Schnorr key pair in the same delimited format used by {@code ProofKeysAccessorImpl}.
     *
     * @param privateKey the private key
     * @param publicKey the public key
     */
    public record SchnorrKeyPair(
            @NonNull Bytes privateKey, @NonNull Bytes publicKey) {
        public SchnorrKeyPair {
            requireNonNull(privateKey);
            requireNonNull(publicKey);
        }

        /**
         * Translates a byte array into a {@link SchnorrKeyPair}.
         */
        public static SchnorrKeyPair fromDelimited(@NonNull final byte[] bytes) {
            requireNonNull(bytes);
            if (bytes.length < 2) {
                throw new IllegalArgumentException("Invalid Schnorr key pair, too few bytes");
            }
            final var privateLength = bytes[0] & 0xFF;
            final var publicLengthIndex = 1 + privateLength;
            if (publicLengthIndex >= bytes.length) {
                throw new IllegalArgumentException("Invalid Schnorr key pair, missing public key length");
            }
            final var publicLength = bytes[publicLengthIndex] & 0xFF;
            if (bytes.length != privateLength + publicLength + 2) {
                throw new IllegalArgumentException("Invalid Schnorr key pair, trailing or missing bytes");
            }
            final var privateKey = new byte[privateLength];
            System.arraycopy(bytes, 1, privateKey, 0, privateLength);
            final var publicKey = new byte[publicLength];
            System.arraycopy(bytes, publicLengthIndex + 1, publicKey, 0, publicLength);
            return new SchnorrKeyPair(Bytes.wrap(privateKey), Bytes.wrap(publicKey));
        }

        /**
         * Converts this key pair to the delimited byte format used on disk.
         */
        public byte[] toDelimitedBytes() {
            final var privateLength = Math.toIntExact(privateKey.length());
            final var publicLength = Math.toIntExact(publicKey.length());
            if (privateLength > 255 || publicLength > 255) {
                throw new IllegalArgumentException("Schnorr key lengths must fit in one byte");
            }
            final var bytes = new byte[privateLength + publicLength + 2];
            bytes[0] = (byte) privateLength;
            System.arraycopy(privateKey.toByteArray(), 0, bytes, 1, privateLength);
            bytes[privateLength + 1] = (byte) publicLength;
            System.arraycopy(publicKey.toByteArray(), 0, bytes, privateLength + 2, publicLength);
            return bytes;
        }
    }
}
