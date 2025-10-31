// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.scratchpad.internal;

import static com.swirlds.common.io.utility.LegacyTemporaryFileBuilder.buildTemporaryFile;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.formatting.TextTable;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.scratchpad.Scratchpad;
import com.swirlds.platform.scratchpad.ScratchpadType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.locks.AutoClosableLock;
import org.hiero.base.concurrent.locks.Locks;
import org.hiero.base.concurrent.locks.locked.Locked;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.node.NodeId;

/**
 * A utility for "taking notes" that are preserved across restart boundaries.
 * <p>
 * A scratchpad instance is thread safe. All read operations and write operations against a scratchpad are atomic. Any
 * write that has completed is guaranteed to be visible to all subsequent reads, regardless of crashes/restarts.
 *
 * @param <K> the enum type that defines the scratchpad fields
 */
public class StandardScratchpad<K extends Enum<K> & ScratchpadType> implements Scratchpad<K> {

    private static final Logger logger = LogManager.getLogger(StandardScratchpad.class);

    /**
     * The directory where scratchpad files are written. Files are written with the format "0.scr", "1.scr", etc., with
     * the number increasing by one each time a file is written. If multiple files are present, the file with the
     * highest number is the most recent and is always used. Multiple files will only be present if the platform crashes
     * after writing a file but before it has the opportunity to delete the previous file.
     */
    public static final String SCRATCHPAD_DIRECTORY_NAME = "scratchpad";

    /**
     * The file extension used for scratchpad files.
     */
    public static final String SCRATCHPAD_FILE_EXTENSION = ".scratchpad";

    private final Set<K> fields;
    private final String id;
    private final Configuration configuration;

    private final Map<K, SelfSerializable> data = new HashMap<>();
    private final AutoClosableLock lock = Locks.createAutoLock();
    private final Path scratchpadDirectory;
    private long nextScratchpadIndex;

    private final int fileVersion = 1;

    /**
     * Create a new scratchpad.
     *
     * @param configuration the configuration to use
     * @param selfId the ID of this node
     * @param clazz the enum class that defines the scratchpad fields
     * @param id the unique ID of this scratchpad (creating multiple scratchpad instances on the same node with the same
     * unique ID has undefined (and possibly undesirable) behavior. Must not contain any non-alphanumeric characters,
     * with the exception of the following characters: "_", "-", and ".". Must not be empty.
     */
    public StandardScratchpad(
            @NonNull final Configuration configuration,
            @NonNull final NodeId selfId,
            @NonNull final Class<K> clazz,
            @NonNull final String id) {
        this.configuration = configuration;
        final StateCommonConfig stateConfig = configuration.getConfigData(StateCommonConfig.class);
        scratchpadDirectory = stateConfig
                .savedStateDirectory()
                .resolve(SCRATCHPAD_DIRECTORY_NAME)
                .resolve(Long.toString(selfId.id()))
                .resolve(id);

        if (id.isEmpty()) {
            throw new IllegalArgumentException("scratchpad ID must not be empty");
        }
        if (!id.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "scratchpad ID must only contain alphanumeric characters, '_', '-', " + "and '.'");
        }

        this.id = id;
        fields = EnumSet.allOf(clazz);

        final Map<Integer, K> indexToFieldMap = new HashMap<>();
        for (final K key : fields) {
            final K previous = indexToFieldMap.put(key.getFieldId(), key);
            if (previous != null) {
                throw new RuntimeException("duplicate scratchpad field ID: " + key.getFieldId());
            }
        }

        loadFromDisk(indexToFieldMap);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void logContents() {
        final TextTable table = new TextTable().setBordersEnabled(false);

        try (final Locked ignored = lock.lock()) {
            for (final K field : fields) {
                final SelfSerializable value = data.get(field);
                if (value == null) {
                    table.addToRow(field.name(), "null");
                } else {
                    table.addRow(field.name(), value.toString());
                }
            }
        }

        logger.info(
                STARTUP.getMarker(),
                """
                        Scratchpad {} contents:
                        {}""",
                id,
                table.render());
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public <V extends SelfSerializable> V get(@NonNull final K key) {
        try (final Locked ignored = lock.lock()) {
            return (V) data.get(key);
        }
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public <V extends SelfSerializable> V set(@NonNull final K key, @Nullable final V value) {
        logger.info(STARTUP.getMarker(), "Setting scratchpad field {}:{} to {}", id, key, value);
        try (final Locked ignored = lock.lock()) {
            final V previous = (V) data.put(key, value);
            flush();
            return previous;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void atomicOperation(@NonNull final Consumer<Map<K, SelfSerializable>> operation) {
        try (final Locked ignored = lock.lock()) {
            operation.accept(data);
            flush();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void atomicOperation(@NonNull final Function<Map<K, SelfSerializable>, Boolean> operation) {
        try (final Locked ignored = lock.lock()) {
            final boolean modified = operation.apply(data);
            if (modified) {
                flush();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        logger.info(STARTUP.getMarker(), "Clearing scratchpad {}", id);
        try (final Locked ignored = lock.lock()) {
            data.clear();
            FileUtils.deleteDirectory(scratchpadDirectory);
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to clear scratchpad", e);
        }
    }

    /**
     * Parse the scratchpad file from disk.
     *
     * @param indexToFieldMap a map from field ID to the enum field
     */
    private void loadFromDisk(final Map<Integer, K> indexToFieldMap) {
        try {
            final List<Path> files = getScratchpadFiles();
            if (files.isEmpty()) {
                return;
            }

            // Delete all files except for the most recent one
            for (int index = 0; index < files.size() - 1; index++) {
                Files.delete(files.get(index));
            }

            final Path scratchpadFile = files.get(files.size() - 1);
            nextScratchpadIndex = getFileIndex(scratchpadFile) + 1;

            try (final SerializableDataInputStream in = new SerializableDataInputStream(
                    new BufferedInputStream(new FileInputStream(scratchpadFile.toFile())))) {

                final int fileVersion = in.readInt();
                if (fileVersion != this.fileVersion) {
                    throw new RuntimeException("scratchpad file version mismatch");
                }

                int fieldCount = in.readInt();

                for (int index = 0; index < fieldCount; index++) {
                    final int fieldId = in.readInt();
                    final K key = indexToFieldMap.get(fieldId);
                    if (key == null) {
                        throw new IOException("scratchpad file contains unknown field " + fieldId);
                    }

                    final SelfSerializable value = in.readSerializable();
                    data.put(key, value);
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException("unable to load scratchpad", e);
        }
    }

    /**
     * Write the data in the scratchpad to a temporary file.
     *
     * @return the path to the temporary file that was written
     */
    @NonNull
    private Path flushToTemporaryFile() throws IOException {
        final Path temporaryFile = buildTemporaryFile(configuration);
        try (final SerializableDataOutputStream out = new SerializableDataOutputStream(
                new BufferedOutputStream(new FileOutputStream(temporaryFile.toFile(), false)))) {

            out.writeInt(fileVersion);

            int fieldCount = 0;
            for (final K keys : fields) {
                if (data.get(keys) != null) {
                    fieldCount++;
                }
            }
            out.writeInt(fieldCount);

            for (final K key : fields) {
                final SelfSerializable value = data.get(key);
                if (value != null) {
                    out.writeInt(key.getFieldId());
                    out.writeSerializable(value, true);
                }
            }
        }

        return temporaryFile;
    }

    /**
     * Generate the path to the next scratchpad file.
     *
     * @return the path to the next scratchpad file
     */
    @NonNull
    private Path generateNextFilePath() {
        return scratchpadDirectory.resolve((nextScratchpadIndex++) + SCRATCHPAD_FILE_EXTENSION);
    }

    /**
     * Get the file index of a scratchpad file.
     *
     * @param path the path to the scratchpad file
     * @return the file index
     */
    private long getFileIndex(@NonNull final Path path) {
        final String fileName = path.getFileName().toString();
        return Long.parseLong(fileName.substring(0, fileName.indexOf('.')));
    }

    /**
     * Get a list of all scratchpad files currently on disk sorted from lowest index to highest index.
     */
    @NonNull
    private List<Path> getScratchpadFiles() {
        if (!Files.exists(scratchpadDirectory)) {
            return List.of();
        }

        final List<Path> files = new ArrayList<>();

        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(scratchpadDirectory)) {
            for (final Path path : stream) {
                if (path.toString().endsWith(SCRATCHPAD_FILE_EXTENSION)) {
                    files.add(path);
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException("unable to list scratchpad files", e);
        }

        files.sort((a, b) -> Long.compare(getFileIndex(a), getFileIndex(b)));

        return files;
    }

    /**
     * Flush the scratchpad to disk atomically. Blocks until complete.
     */
    private void flush() {
        try {
            final List<Path> scratchpadFiles = getScratchpadFiles();

            final Path temporaryFile = flushToTemporaryFile();

            if (!Files.exists(scratchpadDirectory)) {
                Files.createDirectories(scratchpadDirectory);
            }
            Files.move(temporaryFile, generateNextFilePath(), ATOMIC_MOVE);

            for (final Path scratchpadFile : scratchpadFiles) {
                Files.delete(scratchpadFile);
            }
        } catch (final IOException e) {
            throw new RuntimeException("unable to flush scratchpad", e);
        }
    }

    // FUTURE WORK:
    //  - pcli command to look inside a scratchpad file
    //  - pcli command to edit a scratchpad file
}
