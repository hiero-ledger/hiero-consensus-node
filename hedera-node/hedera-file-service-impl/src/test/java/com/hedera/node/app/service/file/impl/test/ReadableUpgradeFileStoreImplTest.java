// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.file.impl.ReadableUpgradeFileStoreImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.ListReadableQueueState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadableUpgradeFileStoreImplTest extends FileTestBase {
    private ReadableUpgradeFileStoreImpl subject;

    @BeforeEach
    void setUp() {
        subject = new ReadableUpgradeFileStoreImpl(filteredReadableStates);
    }

    @Test
    void getsFileMetadataIfUpgradeFileExists() {
        givenValidUpgradeFile(false, true);
        final var file = subject.peek(fileUpgradeFileId);

        assertNotNull(file);

        assertEquals(fileUpgradeFileId, file.fileId());
        assertEquals(keys, file.keys());

        assertEquals(memo, file.memo());
        assertFalse(file.deleted());
        assertEquals(Bytes.wrap(contents), file.contents());
    }

    @Test
    void missingUpgradeFileIsNull() {
        final var stateData =
                ListReadableQueueState.<ProtoBytes>builder(UPGRADE_DATA_KEY).build();
        final var stateFile = MapWritableKVState.<FileID, File>builder(FILES).build();

        given(filteredReadableStates.<FileID, File>get(FILES)).willReturn(stateFile);
        subject = new ReadableUpgradeFileStoreImpl(filteredReadableStates);
        assertThat(subject.peek(fileUpgradeFileId)).isNull();
    }

    @Test
    void constructorCreatesUpgradeFileState() {
        final var store = new ReadableUpgradeFileStoreImpl(filteredReadableStates);
        assertNotNull(store);
    }

    @Test
    void nullArgsFail() {
        assertThrows(NullPointerException.class, () -> new ReadableUpgradeFileStoreImpl(null));
    }

    @Test
    void validGetFullFileContent() throws IOException {
        givenValidFile();
        assertTrue(subject.getFull(fileUpgradeFileId).length() > 0);
    }

    private Iterator<File> wellKnowUpgradeId() {
        return List.of(upgradeFile).iterator();
    }
}
