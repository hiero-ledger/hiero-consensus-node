// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages key pairs scoped to points in an integer sequence; for example, scoped to hinTS construction ids.
 * <p>
 * Stores the key pair created for sequence value {@code n} in a subdirectory with that number as name.
 *
 * @param <R> the type of the private key
 * @param <U> the type of the public key
 * @param <P> the type of the key pair
 */
public class KeyPairSequenceManager<R, U, P> {
    private static final Logger log = LogManager.getLogger(KeyPairSequenceManager.class);

    private static final Pattern CONSTRUCTION_ID_DIR_PATTERN = Pattern.compile("\\d+");

    private final Path path;
    private final String privateKeyFileName;
    private final Supplier<R> privateKeySupplier;
    private final PrivateKeyReader<R> privateKeyReader;
    private final PrivateKeyWriter<R> privateKeyWriter;
    private final Function<R, U> computePublicKeyFn;
    private final BiFunction<R, U, P> combinePairFn;

    @FunctionalInterface
    public interface PrivateKeyReader<R> {
        @NonNull
        R readPrivateKey(@NonNull Path path) throws IOException;
    }

    @FunctionalInterface
    public interface PrivateKeyWriter<R> {
        void writePrivateKey(@NonNull R privateKey, @NonNull Path path) throws IOException;
    }

    public KeyPairSequenceManager(
            @NonNull final Path path,
            @NonNull final String privateKeyFileName,
            @NonNull final Supplier<R> privateKeySupplier,
            @NonNull final PrivateKeyReader<R> privateKeyReader,
            @NonNull final PrivateKeyWriter<R> privateKeyWriter,
            @NonNull final Function<R, U> computePublicKeyFn,
            @NonNull final BiFunction<R, U, P> combinePairFn) {
        this.path = requireNonNull(path);
        this.privateKeyFileName = requireNonNull(privateKeyFileName);
        this.privateKeyReader = requireNonNull(privateKeyReader);
        this.privateKeyWriter = requireNonNull(privateKeyWriter);
        this.privateKeySupplier = requireNonNull(privateKeySupplier);
        this.computePublicKeyFn = requireNonNull(computePublicKeyFn);
        this.combinePairFn = requireNonNull(combinePairFn);
    }

    /**
     * If there exists at least one numeric subdirectory whose numeric name is an integer not greater than {@code n};
     * and the largest of these contains a valid private key file, returns the key pair for that private key.
     * <p>
     * Otherwise, creates a new key pair in a new subdirectory named {@code n} and returns it.
     *
     * @param n the sequence number
     * @return the key pair to use for the given sequence number, preferring an existing one if available
     */
    public P getOrCreateKeyPairFor(final long n) {
        return findLatestKeyPairFor(n).orElseGet(() -> {
            log.info("No usable keypair found for #{}, creating one", n);
            return createKeyPairFor(n);
        });
    }

    /**
     * Creates a new key pair for the given sequence number, throwing an exception if a key pair
     * already exists for that number.
     *
     * @param n the sequence number
     * @return the key pair
     * @throws IllegalArgumentException if a key pair already exists for the given ID
     * @throws UncheckedIOException if the key pair cannot be written
     */
    public P createKeyPairFor(final long n) {
        assertNoExtantKeyPairFor(n);
        final var dirPath = path.resolve(String.valueOf(n));
        log.info("Creating new subdirectory {} for #{}", dirPath.toAbsolutePath(), n);
        try {
            Files.createDirectories(dirPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create directory for #" + n, e);
        }
        final var privateKey = writeNewPrivateKeyAt(pkPathFor(n));
        return keyPairFrom(privateKey);
    }

    /**
     * Removes any key pairs (subdirectories) that are strictly below the given {@code n}. For example, if
     * {@code n} is {@code 10}, then all directories named with a numeric value less than {@code 10} will be removed.
     *
     * @param n the earliest in-use number
     */
    public void purgeKeyPairsBefore(final long n) {
        log.info("Purging any key pair directories below #{}", n);
        if (!Files.isDirectory(path)) {
            log.warn("Base directory {} is not an extant directory, skipping purge", path.toAbsolutePath());
            return;
        }
        try (final var contents = Files.list(path)) {
            contents.filter(Files::isDirectory)
                    .filter(this::isKeyPairDir)
                    .map(this::sequenceNumberOf)
                    .filter(id -> id < n)
                    .forEach(m -> {
                        final var dir = path.resolve(String.valueOf(m));
                        log.info("Removing directory {}", dir.toAbsolutePath());
                        try {
                            rm(dir);
                        } catch (UncheckedIOException e) {
                            log.warn("Failed to remove {}", dir.toAbsolutePath(), e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list directories in {}", path.toAbsolutePath(), e);
        }
    }

    /**
     * Finds the keypair in the subdirectory named by the largest integer not greater than the given
     * {@code constructionId}. If such a subdirectory contains a corrupt or missing private key file,
     * returns an empty optional.
     *
     * @param constructionId the construction ID
     * @return the keypair in the best subdirectory, or empty if none exists
     */
    private Optional<P> findLatestKeyPairFor(final long constructionId) {
        if (!Files.isDirectory(path)) {
            return Optional.empty();
        }
        try (final var contents = Files.list(path)) {
            return contents.filter(Files::isDirectory)
                    .filter(this::isKeyPairDir)
                    .map(this::sequenceNumberOf)
                    .filter(id -> id <= constructionId)
                    .max(Long::compareTo)
                    .map(this::pkPathFor)
                    .flatMap(this::tryReadPrivateKey)
                    .map(this::keyPairFrom);
        } catch (IOException e) {
            log.warn("Failed to list directories in {}", path, e);
            return Optional.empty();
        }
    }

    /**
     * Returns the sequence number of the given key pair directory.
     */
    private long sequenceNumberOf(final Path dir) {
        return Long.parseLong(dir.getFileName().toString());
    }

    /**
     * Returns true if the given directory is a key pair directory.
     */
    private boolean isKeyPairDir(final Path dir) {
        return CONSTRUCTION_ID_DIR_PATTERN.matcher(dir.getFileName().toString()).matches();
    }

    /**
     * Combines a private key and a public key into a key pair.
     *
     * @param privateKey the private key
     * @return the key pair
     */
    private P keyPairFrom(@NonNull final R privateKey) {
        return combinePairFn.apply(privateKey, computePublicKeyFn.apply(privateKey));
    }

    /**
     * Returns the path to the private key file for the given sequence number.
     * @param n the sequence number
     * @return the path to the private key file
     */
    private Path pkPathFor(final long n) {
        return path.resolve(String.valueOf(n)).resolve(privateKeyFileName);
    }

    /**
     * Writes a new private key to the given file path, returning the written key.
     *
     * @param pkPath the path to write the private key
     * @return the private key
     * @throws IllegalArgumentException if the key cannot be written
     */
    private R writeNewPrivateKeyAt(@NonNull final Path pkPath) {
        final var privateKey = privateKeySupplier.get();
        try {
            privateKeyWriter.writePrivateKey(privateKey, pkPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return privateKey;
    }

    /**
     * Tries to read the private key from the given file path.
     *
     * @param path the file path from which to read
     * @return the private key, if found
     */
    private Optional<R> tryReadPrivateKey(@NonNull final Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(privateKeyReader.readPrivateKey(path));
        } catch (Exception e) {
            log.warn("Unable to read private key from {}", path.toAbsolutePath(), e);
            return Optional.empty();
        }
    }

    /**
     * Asserts that no key pair exists for the given sequence number.
     * @param n the sequence number
     * @throws IllegalArgumentException if a key pair already exists
     */
    private void assertNoExtantKeyPairFor(final long n) {
        final var pkPath = pkPathFor(n);
        try {
            privateKeyReader.readPrivateKey(pkPath);
            throw new IllegalArgumentException("Key pair already exists for #" + n + " at " + pkPath.toAbsolutePath());
        } catch (Exception ignore) {
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param dir the directory to delete
     */
    static void rm(@NonNull final Path dir) {
        if (Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
