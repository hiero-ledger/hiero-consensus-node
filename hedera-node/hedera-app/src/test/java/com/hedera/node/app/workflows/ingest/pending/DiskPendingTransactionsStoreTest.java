// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest.pending;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskPendingTransactionsStoreTest {
    private static final Bytes TX_A = Bytes.wrap(new byte[] {1, 2, 3});
    private static final Bytes TX_B = Bytes.wrap(new byte[] {4, 5});

    @TempDir
    Path dir;

    private DiskPendingTransactionsStore subject;

    @BeforeEach
    void setUp() {
        subject = new DiskPendingTransactionsStore(dir.resolve("pending"));
    }

    @Test
    void savesAndLoadsInOrder() throws Exception {
        subject.save(new SavedPendingTransactions(42L, List.of(TX_A, TX_B)));

        assertThat(subject.load()).contains(new SavedPendingTransactions(42L, List.of(TX_A, TX_B)));
    }

    @Test
    void loadIsEmptyWhenNothingSaved() {
        assertThat(subject.load()).isEmpty();
    }

    @Test
    void saveReplacesPreviousFile() throws Exception {
        subject.save(new SavedPendingTransactions(1L, List.of(TX_A)));
        subject.save(new SavedPendingTransactions(2L, List.of(TX_B)));

        assertThat(subject.load()).contains(new SavedPendingTransactions(2L, List.of(TX_B)));
    }

    @Test
    void ignoresLeftoverTmpFile() throws Exception {
        Files.createDirectories(dir.resolve("pending"));
        Files.write(dir.resolve("pending").resolve(DiskPendingTransactionsStore.FILE_NAME + ".tmp"), new byte[] {9});

        assertThat(subject.load()).isEmpty();
    }

    @Test
    void deletesFileWithUnknownVersion() throws Exception {
        final var file = dir.resolve("pending").resolve(DiskPendingTransactionsStore.FILE_NAME);
        Files.createDirectories(file.getParent());
        try (final var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(99);
        }

        assertThat(subject.load()).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void deletesTruncatedFile() throws Exception {
        subject.save(new SavedPendingTransactions(42L, List.of(TX_A, TX_B)));
        final var file = dir.resolve("pending").resolve(DiskPendingTransactionsStore.FILE_NAME);
        final var bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 1));

        assertThat(subject.load()).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void deleteRemovesFile() throws Exception {
        subject.save(new SavedPendingTransactions(42L, List.of(TX_A)));

        assertThat(subject.delete()).isTrue();
        assertThat(subject.load()).isEmpty();
    }
}
