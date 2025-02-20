// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.FileSystems;
import org.junit.jupiter.api.Test;

final class RecordsInjectionModuleTest {
    @Test
    void testProvideFileSystem() {
        assertEquals(FileSystems.getDefault(), BlockRecordInjectionModule.provideFileSystem());
    }
}
