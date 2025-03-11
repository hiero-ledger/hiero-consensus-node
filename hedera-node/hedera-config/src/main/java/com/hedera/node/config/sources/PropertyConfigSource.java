// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.sources;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;

/**
 * A config source that wraps a {@link Properties} object.
 */
public class PropertyConfigSource implements ConfigSource {

    private final Properties properties;

    private final int ordinal;

    public PropertyConfigSource(@NonNull final Properties properties, final int ordinal) {
        requireNonNull(properties, "properties must not be null");
        this.properties = new Properties();
        properties.forEach((key, value) -> this.properties.put(key.toString(), value.toString()));
        this.ordinal = ordinal;
    }

    /**
     * Create a new instance based on a property file available as a resource on the class path.
     *
     * @param resourceName the name of the resource as needed by
     *        {@code Thread.currentThread().getContextClassLoader().getResourceAsStream}
     * @param ordinal the ordinal as used to determine priority for this config source.
     */
    public PropertyConfigSource(@NonNull final String resourceName, final int ordinal) {
        this(loadProperties(resourceName), ordinal);
    }

    public PropertyConfigSource(@NonNull final Properties properties) {
        this(properties, ConfigSource.DEFAULT_ORDINAL);
    }

    private static Properties loadProperties(@NonNull final String resourceName) {
        requireNonNull(resourceName, "resourceName must not be null");
        // It is important to use the Thread's context class loader because the resource we want to load might
        // be part of another module.
        try (final var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new UncheckedIOException(
                        "Property file " + resourceName + " could not be found", new IOException());
            }
            final var props = new Properties();
            props.load(in);
            return props;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load resource " + resourceName + " as property file", e);
        }
    }

    @NonNull
    @Override
    public Set<String> getPropertyNames() {
        return properties.stringPropertyNames();
    }

    @Nullable
    @Override
    public String getValue(@NonNull String key) throws NoSuchElementException {
        requireNonNull(key, "key must not be null");
        return properties.getProperty(key);
    }

    @Override
    public boolean isListProperty(@NonNull final String propertyName) throws NoSuchElementException {
        return false;
    }

    @NonNull
    @Override
    public List<String> getListValue(@NonNull final String propertyName) throws NoSuchElementException {
        return List.of();
    }

    @Override
    public int getOrdinal() {
        return ordinal;
    }
}
