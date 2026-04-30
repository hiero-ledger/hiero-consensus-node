// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapUtils.CONFIGURATION;

import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.test.fixtures.TestFileSystemManager;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.consensus.metrics.noop.NoOpMetrics;

/**
 * Utility methods for creating {@link VirtualMapStateImpl} instances for use in tests.
 */
public final class VirtualMapStateTestUtils {

    private static volatile FileSystemManager fallbackFileSystemManager;

    private static FileSystemManager fallbackFileSystemManager() {
        FileSystemManager fsm = fallbackFileSystemManager;
        if (fsm == null) {
            synchronized (VirtualMapStateTestUtils.class) {
                fsm = fallbackFileSystemManager;
                if (fsm == null) {
                    try {
                        final Path root = Files.createTempDirectory("VirtualMapStateTestUtils");
                        Runtime.getRuntime()
                                .addShutdownHook(
                                        new Thread(() -> FileUtils.rethrowIO(() -> FileUtils.deleteDirectory(root))));
                        fsm = new TestFileSystemManager(root);
                        fallbackFileSystemManager = fsm;
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }
        return fsm;
    }

    /**
     * Creates a virtual map state with the given virtual map.
     * @param virtualMap the virtual map to use.
     * @return the created virtual map state.
     */
    public static VirtualMapStateImpl createTestStateWithVM(@NonNull final VirtualMap virtualMap) {
        return new VirtualMapStateImpl(virtualMap, new NoOpMetrics());
    }

    /**
     * Creates a virtual map state with a default label, backed by a process-wide
     * {@link TestFileSystemManager}. Prefer {@link #createTestState(FileSystemManager)} so each
     * test owns its file system manager.
     */
    public static VirtualMapStateImpl createTestState() {
        return createTestState(fallbackFileSystemManager());
    }

    /**
     * Creates a virtual map state with a default label.
     * @param fileSystemManager the {@link FileSystemManager} to use for the underlying virtual map.
     * @return the created virtual map state.
     */
    public static VirtualMapStateImpl createTestState(@NonNull final FileSystemManager fileSystemManager) {
        return new VirtualMapStateImpl(CONFIGURATION, fileSystemManager, new NoOpMetrics());
    }

    private VirtualMapStateTestUtils() {}
}
