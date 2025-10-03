// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.helpers;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.Marker;
import org.hiero.otter.fixtures.app.services.consistency.ConsistencyServiceConfig;

/**
 * Utility class for the Otter framework.
 */
public class Utils {

    private Utils() {}

    /**
     * Gathers markers in a {@link Set}
     *
     * @param first the first log marker
     * @param rest additional optional log markers
     * @return a set of markers collected from the provided log markers
     */
    public static Set<Marker> collectMarkers(@NonNull final LogMarker first, @Nullable final LogMarker... rest) {
        final Stream<LogMarker> restStream = rest == null ? Stream.empty() : Arrays.stream(rest);
        return Stream.concat(Stream.of(first), restStream)
                .map(LogMarker::getMarker)
                .collect(Collectors.toSet());
    }

    /**
     * Gathers elements in a {@link Set}
     *
     * @param first the first element
     * @param rest additional optional elements
     * @param <T> the type of the elements
     * @return a set of elements collected from the provided elements
     */
    @NonNull
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> Set<T> collect(@NonNull final T first, @Nullable final T... rest) {
        final Stream<T> restStream = rest == null ? Stream.empty() : Arrays.stream(rest);
        return Stream.concat(Stream.of(first), restStream).collect(Collectors.toSet());
    }

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
                .withConfigDataType(ConsistencyServiceConfig.class)
                .getOrCreateConfig();
    }
}
