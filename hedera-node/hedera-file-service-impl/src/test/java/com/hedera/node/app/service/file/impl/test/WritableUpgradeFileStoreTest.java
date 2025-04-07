// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.file.impl.WritableUpgradeFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableUpgradeFileStoreTest extends FileTestBase {
    private File file;
    protected final FileID UPGRADE_FILE_ID = new FileID(SHARD, REALM, 150);

    @Test
    void throwsIfNullValuesAsArgs() {
        assertThrows(NullPointerException.class, () -> new WritableUpgradeFileStore(null));
        assertThrows(NullPointerException.class, () -> writableUpgradeFileStore.add(null));
    }

    @Test
    void constructorCreatesUpgradeFileState() {
        final var store = new WritableUpgradeFileStore(filteredWritableStates);
        assertNotNull(store);
    }

    @Test
    void commitsUpgradeFileExist() {
        file = createUpgradeFile();
        assertFalse(writableUpgradeStates.iterator().hasNext());

        writableUpgradeStates.add(new ProtoBytes(file.contents()));
        writableUpgradeFileStates.put(file.fileId(), file);

        assertEquals(
                UPGRADE_FILE_ID,
                writableUpgradeFileStates.get(fileUpgradeFileId).fileId());
        final var writtenFile = writableUpgradeFileStates.get(fileUpgradeFileId);
        assertEquals(file, writtenFile);
        assertEquals(new ProtoBytes(file.contents()), writableUpgradeStates.peek());
    }

    @Test
    void getReturnsUpgradeFile() {
        file = createUpgradeFile();
        writableUpgradeStates.add(new ProtoBytes(file.contents()));
        writableUpgradeFileStates.put(file.fileId(), file);

        final var readFile = writableUpgradeStates.peek();

        assertEquals(new ProtoBytes(file.contents()), readFile);
        assertEquals(file, writableUpgradeFileStates.get(fileUpgradeFileId));
    }
}
