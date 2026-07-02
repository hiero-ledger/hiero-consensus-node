// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Platform level unit test base class for common setup and teardown.
 */
public class PlatformContextFactory {

    private PlatformContextFactory() {}

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
    public static PlatformContext createPlatformContext(
            @Nullable final Function<TestPlatformContextBuilder, TestPlatformContextBuilder> platformContextModifier,
            @Nullable final Function<TestConfigBuilder, TestConfigBuilder> configModifier) {
        final TestPlatformContextBuilder platformContextBuilder = TestPlatformContextBuilder.create();
        final TestConfigBuilder configBuilder = new TestConfigBuilder();
        if (configModifier != null) {
            configModifier.apply(configBuilder);
        }

        final Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // add configuration to platform builder.
        platformContextBuilder.withConfiguration(configBuilder.getOrCreateConfig());
        if (platformContextModifier != null) {
            // apply any other modifications to the platform builder.
            platformContextModifier.apply(platformContextBuilder);
        }
        return platformContextBuilder.build();
    }
}
