// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableFileStoreTest extends FileTestBase {
    private File file;

    @Test
    void throwsIfNullValuesAsArgs() {
        assertThrows(NullPointerException.class, () -> new WritableFileStore(null, writableEntityCounters));
        assertThrows(NullPointerException.class, () -> new WritableFileStore(writableStates, null));
        assertThrows(NullPointerException.class, () -> writableStore.put(null));
    }

    @Test
    void constructorCreatesFileState() {
        final var store = new WritableFileStore(writableStates, writableEntityCounters);
        assertNotNull(store);
    }

    @Test
    void commitsFileChanges() {
        file = createFile();
        assertFalse(writableFileState.contains(fileId));

        writableStore.put(file);

        assertTrue(writableFileState.contains(fileId));
        final var writtenFile = writableFileState.get(fileId);
        assertEquals(file, writtenFile);
    }

    @Test
    void getReturnsFile() {
        file = createFile();
        writableStore.put(file);

        final var maybeReadFile = writableStore.get(fileId);

        assertTrue(maybeReadFile.isPresent());
        final var readFile = maybeReadFile.get();
        assertEquals(file, readFile);
    }

    @Test
    void verifyFileDeleted() {
        file = createFile();
        writableStore.put(file);

        final var maybeReadFile = writableStore.get(fileId);

        assertTrue(maybeReadFile.isPresent());

        writableStore.removeFile(fileId);

        final var readFile = writableStore.get(fileId);
        assertEquals(readFile, Optional.empty());
    }
}
