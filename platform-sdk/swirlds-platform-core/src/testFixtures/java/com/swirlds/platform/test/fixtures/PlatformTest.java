// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.config.PathsConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import org.junit.jupiter.api.io.TempDir;

/**
 * Platform level unit test base class for common setup and teardown.
 */
public abstract class PlatformTest {

    public static final String TEST_MARKER_FILE_DIRECTORY = "marker_files";

    /**
     * Temporary directory provided by JUnit
     * <p>
     * Do not make this value static, otherwise all test methods will share the same temp directory.
     */
    @TempDir
    protected Path tempDir;

    /**
     * Creates a default platform context for the tests
     */
    @NonNull
    protected PlatformContext createDefaultPlatformContext() {
        return createPlatformContext(null, null);
    }

    /**
     * Creates a platform context for the tests with the given builder modifications. Modifications are applied in the
     * following order:
     * <ol>
     *     <li>The config modifications are applied</li>
     *     <li>The temp directory is added to the config for marker files</li>
     *     <li>The platform context builder modifications are applied</li>
     * </ol>
     * <p>
     * Any configuration set by the platform context builder modifying method overrides the configuration created by
     * the config modifier. Best practice is to set configuration through the config modifier and all other platform
     * context variables through the platform context modifier.
     *
     * @param platformContextModifier the function to modify the platform context builder
     * @param configModifier          the function to modify the test config builder
     * @return the platform context
     */
    @NonNull
    protected PlatformContext createPlatformContext(
            @Nullable final Function<TestPlatformContextBuilder, TestPlatformContextBuilder> platformContextModifier,
            @Nullable final Function<TestConfigBuilder, TestConfigBuilder> configModifier) {
        final TestPlatformContextBuilder platformContextBuilder = TestPlatformContextBuilder.create();
        final TestConfigBuilder configBuilder = new TestConfigBuilder();
        if (configModifier != null) {
            configModifier.apply(configBuilder);
        }
        // add temp directory to config for marker files.
        configBuilder
                .withValue(
                        PathsConfig_.MARKER_FILES_DIR,
                        tempDir.resolve(TEST_MARKER_FILE_DIRECTORY).toString())
                .withValue(PathsConfig_.WRITE_PLATFORM_MARKER_FILES, true);
        // add configuration to platform builder.
        platformContextBuilder.withConfiguration(configBuilder.getOrCreateConfig());
        if (platformContextModifier != null) {
            // apply any other modifications to the platform builder.
            platformContextModifier.apply(platformContextBuilder);
        }
        return platformContextBuilder.build();
    }

    /**
     * Gets the temporary directory provided by JUnit
     *
     * @return the temporary directory
     */
    @NonNull
    Path getTempDir() {
        return Objects.requireNonNull(tempDir);
    }

    /**
     * Gets the marker file directory
     *
     * @return the marker file directory
     */
    @NonNull
    protected Path getMarkerFileDirectory() {
        return Objects.requireNonNull(tempDir.resolve(TEST_MARKER_FILE_DIRECTORY));
    }

    /**
     * checks for the presence of the marker file.
     *
     * @param markerFileName the name of the marker file
     * @param exists         true if the marker file should exist, false otherwise
     */
    protected void assertMarkerFile(@NonNull final String markerFileName, final boolean exists) {
        assertThat(exists)
                .isEqualTo(getMarkerFileDirectory()
                        .resolve(markerFileName)
                        .toFile()
                        .exists())
                .withFailMessage("Marker file " + markerFileName + " should " + (exists ? "" : "not ") + "exist");
    }
}
