// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Tests for {@link WorkingDirUtils#workingDirFor(long, String)}.
 *
 * <p>Isolated because it mutates the JVM-global {@code hapi.spec.subtask.name} system
 * property that {@code workingDirFor()} reads.
 */
@Isolated
class WorkingDirUtilsTest {

    @AfterEach
    void clearSubtaskProperty() {
        System.clearProperty(WorkingDirUtils.SUBTASK_NAME_PROPERTY);
    }

    @Test
    @DisplayName("Default scope is used when scope is null and subtask is not set")
    void defaultScopeWithoutSubtask() {
        final Path result = WorkingDirUtils.workingDirFor(0, null);
        assertEquals(Path.of("build/hapi-test/node0"), result);
    }

    @Test
    @DisplayName("Explicit scope is used when provided and subtask is not set")
    void explicitScopeWithoutSubtask() {
        final Path result = WorkingDirUtils.workingDirFor(2, "concurrent");
        assertEquals(Path.of("build/concurrent-test/node2"), result);
    }

    @Test
    @DisplayName("Subtask name is inserted as intermediate directory when property is set")
    void defaultScopeWithSubtask() {
        System.setProperty(WorkingDirUtils.SUBTASK_NAME_PROPERTY, "hapiTestMisc");
        final Path result = WorkingDirUtils.workingDirFor(0, null);
        assertEquals(Path.of("build/hapi-test/hapiTestMisc/node0"), result);
    }

    @Test
    @DisplayName("Subtask name works with explicit scope")
    void explicitScopeWithSubtask() {
        System.setProperty(WorkingDirUtils.SUBTASK_NAME_PROPERTY, "hapiTestMiscEmbedded");
        final Path result = WorkingDirUtils.workingDirFor(1, "concurrent");
        assertEquals(Path.of("build/concurrent-test/hapiTestMiscEmbedded/node1"), result);
    }

    @Test
    @DisplayName("Blank subtask name is ignored")
    void blankSubtaskIsIgnored() {
        System.setProperty(WorkingDirUtils.SUBTASK_NAME_PROPERTY, "   ");
        final Path result = WorkingDirUtils.workingDirFor(0, null);
        assertEquals(Path.of("build/hapi-test/node0"), result);
    }

    @Test
    @DisplayName("Path traversal in subtask name is rejected")
    void pathTraversalIsRejected() {
        System.setProperty(WorkingDirUtils.SUBTASK_NAME_PROPERTY, "../escape");
        assertThrows(IllegalArgumentException.class, () -> WorkingDirUtils.workingDirFor(0, null));
    }
}
