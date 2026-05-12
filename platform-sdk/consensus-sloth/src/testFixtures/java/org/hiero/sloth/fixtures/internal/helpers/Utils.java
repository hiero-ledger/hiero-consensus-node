// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.internal.helpers;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import org.hiero.sloth.fixtures.app.SlothAppConfig;

/**
 * Utility class for the sloth framework.
 */
public class Utils {

    private Utils() {}

    /**
     * Creates a {@link Configuration} with overridden properties.
     *
     * @param overriddenProperties a map of properties to override in the configuration
     * @return a new {@link Configuration} instance with the specified overridden properties
     */
    @NonNull
    public static Configuration createConfiguration(@NonNull final Map<String, String> overriddenProperties) {
        requireNonNull(overriddenProperties, "Overridden properties must not be null");
        return new TestConfigBuilder()
                .withSource(new SimpleConfigSource(overriddenProperties))
                .withConfigDataType(SlothAppConfig.class)
                .getOrCreateConfig();
    }
}
