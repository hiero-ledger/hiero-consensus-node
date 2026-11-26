// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.system.SystemExitCode.NODE_ALREADY_RUNNING;
import static org.hiero.consensus.system.SystemExitCode.NODE_ID_NOT_PROVIDED;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import com.swirlds.platform.config.legacy.ConfigurationException;
import java.nio.file.Path;
import org.hiero.consensus.system.SystemExitUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class ServicesMainTest {
    @TempDir
    private Path tempDir;

    // no local nodes specified, no environment nodes specified
    @Test
    void throwsExceptionOnNoNodesToRun() {
        String[] args = {};
        try (MockedStatic<SystemExitUtils> systemExitUtilsMockedStatic = mockStatic(SystemExitUtils.class)) {
            assertThatThrownBy(() -> ServicesMain.main(args)).isInstanceOf(ConfigurationException.class);
            systemExitUtilsMockedStatic.verify(() -> SystemExitUtils.exitSystem(
                    NODE_ID_NOT_PROVIDED, "No node id specified on command line. Use -local <nodeId>"));
        }
    }

    // more than one local node specified on the commandline
    @Test
    void hardExitOnTooManyCliNodes() {
        String[] args = {"-local", "1", "2"}; // both "1" and "2" match entries in address book

        try (MockedStatic<SystemExitUtils> systemExitUtilsMockedStatic = mockStatic(SystemExitUtils.class)) {
            assertThatThrownBy(() -> ServicesMain.main(args)).isInstanceOf(ConfigurationException.class);
            systemExitUtilsMockedStatic.verify(() -> SystemExitUtils.exitSystem(NODE_ID_NOT_PROVIDED));
        }
    }

    @Test
    void createsMissingSavedStateDirAndLocksIt() throws Exception {
        final var savedStateDir = tempDir.resolve("data").resolve("saved");

        try (final var lock = ServicesMain.tryLockSavedStateDir(savedStateDir)) {
            assertThat(lock).isNotNull();
            assertThat(lock.isValid()).isTrue();
            assertThat(savedStateDir.resolve(ServicesMain.NODE_LOCK_FILE_NAME)).exists();
        }
    }

    @Test
    void cannotLockSavedStateDirTwice() throws Exception {
        try (final var lock = ServicesMain.tryLockSavedStateDir(tempDir)) {
            assertThat(lock).isNotNull();
            assertThat(ServicesMain.tryLockSavedStateDir(tempDir)).isNull();
        }
    }

    @Test
    void canLockSavedStateDirAgainAfterRelease() throws Exception {
        try (final var lock = ServicesMain.tryLockSavedStateDir(tempDir)) {
            assertThat(lock).isNotNull();
        }
        try (final var lock = ServicesMain.tryLockSavedStateDir(tempDir)) {
            assertThat(lock).isNotNull();
        }
    }

    @Test
    void hardExitWhenSavedStateDirIsAlreadyLocked() throws Exception {
        try (final var lock = ServicesMain.tryLockSavedStateDir(tempDir);
                MockedStatic<SystemExitUtils> systemExitUtilsMockedStatic = mockStatic(SystemExitUtils.class)) {
            assertThat(lock).isNotNull();
            assertThatThrownBy(() -> ServicesMain.acquireNodeLockOrExit(tempDir))
                    .isInstanceOf(IllegalStateException.class);
            systemExitUtilsMockedStatic.verify(() -> SystemExitUtils.exitSystem(eq(NODE_ALREADY_RUNNING), anyString()));
        }
    }
}
