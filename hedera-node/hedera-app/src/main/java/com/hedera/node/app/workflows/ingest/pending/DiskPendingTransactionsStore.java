// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest.pending;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Saves pending user transactions in one versioned file. Writes go to a temp file that is synced and then atomically
 * renamed, so a crash never leaves a half-written file.
 */
public final class DiskPendingTransactionsStore implements PendingTransactionsStore {
    private static final Logger logger = LogManager.getLogger(DiskPendingTransactionsStore.class);

    static final String FILE_NAME = "pending-transactions.bin";
    private static final int FORMAT_VERSION = 1;
    // Guards against garbage lengths only; real transactions are far smaller
    private static final int MAX_TRANSACTION_BYTES = 32 * 1024 * 1024;

    private final Path file;
    private final Path tmpFile;

    /**
     * @param directory the directory holding the file
     */
    public DiskPendingTransactionsStore(@NonNull final Path directory) {
        requireNonNull(directory);
        this.file = directory.resolve(FILE_NAME);
        this.tmpFile = directory.resolve(FILE_NAME + ".tmp");
    }

    @Override
    public void save(@NonNull final SavedPendingTransactions saved) throws IOException {
        requireNonNull(saved);
        Files.createDirectories(file.getParent());
        try (final var channel = FileChannel.open(tmpFile, CREATE, WRITE, TRUNCATE_EXISTING);
                final var out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)))) {
            out.writeInt(FORMAT_VERSION);
            out.writeLong(saved.freezeRound());
            out.writeInt(saved.transactions().size());
            for (final var transaction : saved.transactions()) {
                out.writeInt((int) transaction.length());
                transaction.writeTo(out);
            }
            out.flush();
            channel.force(true);
        }
        Files.move(tmpFile, file, ATOMIC_MOVE, REPLACE_EXISTING);
    }

    @Override
    public @NonNull Optional<SavedPendingTransactions> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (final var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            final int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported format version " + version);
            }
            final long freezeRound = in.readLong();
            final int count = in.readInt();
            if (count < 0) {
                throw new IOException("Negative transaction count " + count);
            }
            final List<Bytes> transactions = new ArrayList<>(Math.min(count, 1024));
            for (int i = 0; i < count; i++) {
                final int length = in.readInt();
                if (length < 0 || length > MAX_TRANSACTION_BYTES) {
                    throw new IOException("Invalid transaction length " + length);
                }
                final var bytes = new byte[length];
                in.readFully(bytes);
                transactions.add(Bytes.wrap(bytes));
            }
            return Optional.of(new SavedPendingTransactions(freezeRound, transactions));
        } catch (final IOException e) {
            logger.warn("Deleting unreadable pending transactions file {}", file, e);
            delete();
            return Optional.empty();
        }
    }

    @Override
    public boolean delete() {
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(tmpFile);
            return true;
        } catch (final IOException e) {
            logger.warn("Unable to delete pending transactions file {}", file, e);
            return false;
        }
    }
}
