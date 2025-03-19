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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages content scoped to points in an integer sequence; for example, scoped to hinTS construction ids.
 * <p>
 * Stores the content created for sequence value {@code n} in a subdirectory with that number as name.
 *
 * @param <T> the type of content
 */
public class SequentialContentManager<T> {
    private static final Logger log = LogManager.getLogger(SequentialContentManager.class);

    private static final Pattern SEQ_NO_DIR_PATTERN = Pattern.compile("\\d+");

    private final Path path;
    private final String contentDesc;
    private final String contentFileName;
    private final Supplier<T> contentSupplier;
    private final ContentReader<T> contentReader;
    private final ContentWriter<T> contentWriter;

    @FunctionalInterface
    public interface ContentReader<R> {
        @NonNull
        R readContent(@NonNull Path p) throws IOException;
    }

    @FunctionalInterface
    public interface ContentWriter<R> {
        void writeContent(@NonNull R content, @NonNull Path p) throws IOException;
    }

    public SequentialContentManager(
            @NonNull final Path path,
            @NonNull final String contentDesc,
            @NonNull final String contentFileName,
            @NonNull final Supplier<T> contentSupplier,
            @NonNull final ContentReader<T> contentReader,
            @NonNull final ContentWriter<T> contentWriter) {
        this.path = requireNonNull(path);
        this.contentDesc = contentDesc;
        this.contentFileName = requireNonNull(contentFileName);
        this.contentReader = requireNonNull(contentReader);
        this.contentWriter = requireNonNull(contentWriter);
        this.contentSupplier = requireNonNull(contentSupplier);
    }

    /**
     * If there exists at least one numeric subdirectory whose numeric name is an integer not greater than
     * {@code seqNo}; and the largest of these contains a valid content file, returns its contents.
     * <p>
     * Otherwise, creates new content in a new subdirectory named {@code seqNo} and returns it.
     *
     * @param seqNo the sequence number
     * @return the content to use for the given sequence number, preferring an existing one if available
     */
    public T getOrCreateContent(final long seqNo) {
        return findLatestContentFor(seqNo).orElseGet(() -> {
            log.info("No usable {} found for #{}, creating one", contentDesc, seqNo);
            return createContentFor(seqNo);
        });
    }

    /**
     * Creates new content for the given sequence number, throwing an exception if content already exists for
     * that number.
     *
     * @param seqNo the sequence number
     * @return the content
     * @throws IllegalArgumentException if content already exists for the given ID
     * @throws UncheckedIOException if the content cannot be written
     */
    public T createContentFor(final long seqNo) {
        assertNoExtantContentFor(seqNo);
        final var dirPath = path.resolve(String.valueOf(seqNo));
        log.info("Creating new subdirectory {} for #{}", dirPath.toAbsolutePath(), seqNo);
        try {
            Files.createDirectories(dirPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create directory for #" + seqNo, e);
        }
        return writeNewContentAt(pathFor(seqNo));
    }

    /**
     * Removes any content (subdirectories) that are strictly below the given {@code n}. For example, if
     * {@code n} is {@code 10}, then all directories named with a numeric value less than {@code 10} will be removed.
     *
     * @param seqNo the earliest in-use number
     */
    public void purgeContentBefore(final long seqNo) {
        log.info("Purging any content directories below #{}", seqNo);
        if (!Files.isDirectory(path)) {
            log.warn("Base directory {} is not an extant directory, skipping purge", path.toAbsolutePath());
            return;
        }
        try (final var contents = Files.list(path)) {
            contents.filter(Files::isDirectory)
                    .filter(this::isContentDir)
                    .map(this::sequenceNumberOf)
                    .filter(id -> id < seqNo)
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
     * Finds the content in the subdirectory named by the largest integer not greater than the given
     * {@code seqNo}. If such a subdirectory contains a corrupt or missing content file, returns an
     * empty optional.
     *
     * @param seqNo the sequence number
     * @return the content in the best subdirectory, or empty if none exists
     */
    private Optional<T> findLatestContentFor(final long seqNo) {
        if (!Files.isDirectory(path)) {
            return Optional.empty();
        }
        try (final var contents = Files.list(path)) {
            final var content = contents.filter(Files::isDirectory)
                    .filter(this::isContentDir)
                    .map(this::sequenceNumberOf)
                    .filter(id -> id <= seqNo)
                    .max(Long::compareTo)
                    .map(this::pathFor)
                    .flatMap(this::tryToReadContent);
            content.ifPresent(ignore -> log.info("Loaded existing {} for #{}", contentDesc, seqNo));
            return content;
        } catch (IOException e) {
            log.warn("Failed to list directories in {}", path, e);
            return Optional.empty();
        }
    }

    /**
     * Returns the sequence number of the given content directory.
     */
    private long sequenceNumberOf(final Path dir) {
        return Long.parseLong(dir.getFileName().toString());
    }

    /**
     * Returns true if the given directory is a content directory.
     */
    private boolean isContentDir(final Path dir) {
        return SEQ_NO_DIR_PATTERN.matcher(dir.getFileName().toString()).matches();
    }

    /**
     * Returns the path to the content file for the given sequence number.
     * @param seqNo the sequence number
     * @return the path to the content file
     */
    private Path pathFor(final long seqNo) {
        return path.resolve(String.valueOf(seqNo)).resolve(contentFileName);
    }

    /**
     * Writes new content to the given file path, returning the written content.
     *
     * @param p the path to write the new content
     * @return the content
     * @throws IllegalArgumentException if the content cannot be written
     */
    private T writeNewContentAt(@NonNull final Path p) {
        final var newContent = contentSupplier.get();
        try {
            contentWriter.writeContent(newContent, p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return newContent;
    }

    /**
     * Tries to read the content from the given file path.
     *
     * @param p the file path from which to read
     * @return the content, if found
     */
    private Optional<T> tryToReadContent(@NonNull final Path p) {
        if (!Files.exists(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(contentReader.readContent(p));
        } catch (Exception e) {
            log.warn("Unable to read content from {}", p.toAbsolutePath(), e);
            return Optional.empty();
        }
    }

    /**
     * Asserts that no content exists for the given sequence number.
     * @param seqNo the sequence number
     * @throws IllegalArgumentException if content already exists
     */
    private void assertNoExtantContentFor(final long seqNo) {
        final var p = pathFor(seqNo);
        try {
            contentReader.readContent(p);
            throw new IllegalArgumentException("Content already exists for #" + seqNo + " at " + p.toAbsolutePath());
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
