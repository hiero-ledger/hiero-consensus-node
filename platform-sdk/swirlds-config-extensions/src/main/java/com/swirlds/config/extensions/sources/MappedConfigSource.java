// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.sources;

import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link ConfigSource} that can be used as a wrapper for other config sources, providing functionality to define
 * mappings for given properties. This allows the same property value defined for one name in the wrapped
 * {@link ConfigSource} to be accessed by both names.
 * <p>
 * For example, suppose you want to rename the "dbUrl" property to "general.databaseUrl". You can use a
 * {@code MappingConfigSource} to create a mapping between "dbUrl" and "general.databaseUrl", so that you can use both
 * names with the same value during the transition time.
 * </p>
 * <p>
 * Note that multiple mappings defined for a property is not allowed and will throw an {@link IllegalArgumentException}
 * at runtime.
 * </p>
 * <p>
 * Note that adding a mapping to a property not defined in the original {@link ConfigSource} will throw an
 * {@link IllegalArgumentException} at runtime.
 * </p>
 * <p>
 * Note that the ordinal of this {@code ConfigSource} is taken from the original {@link ConfigSource} that was wrapped
 * by this class.
 * </p>
 *
 * @see ConfigSource
 * @see ConfigMapping
 */
public class MappedConfigSource implements ConfigSource {
    private static final String PROPERTY_NOT_FOUND = "Property '{}' not found in original config source";
    private static final String PROPERTY_ALREADY_DEFINED = "Property '%s' already defined";
    private static final String DUPLICATE_PROPERTY = "Property '{}' already found in original config source";
    private static final String PROPERTY_ALREADY_MAPPED = "Property '%s' has already a mapping defined";
    private static final Logger logger = LogManager.getLogger(MappedConfigSource.class);

    private final ConfigSource wrappedSource;

    private final Queue<ConfigMapping> configMappings;
    private final Map<String, String> properties;
    private final Map<String, List<String>> listProperties;

    /**
     * Constructor that takes the wrapped config.
     *
     * @param wrappedSource the wrapped config
     */
    public MappedConfigSource(@NonNull final ConfigSource wrappedSource) {
        this.wrappedSource = Objects.requireNonNull(wrappedSource, "wrappedSource must not be null");
        configMappings = new ConcurrentLinkedQueue<>();
        properties = new HashMap<>();
        listProperties = new HashMap<>();
    }

    /**
     * Adds the mappedName {@code 'mappedName'<->'originalName'}.
     *
     * @param mappedName   the mappedName name
     * @param originalName the original name
     */
    public void addMapping(@NonNull final String mappedName, @NonNull final String originalName) {
        addMapping(new ConfigMapping(mappedName, originalName));
    }

    /**
     * Adds the mappedName {@code 'mappedName'<->'originalName'}.
     *
     * @param configMapping defined mapping
     */
    public void addMapping(@NonNull final ConfigMapping configMapping) {
        Objects.requireNonNull(configMapping, "configMapping must not be null");

        if (configMappings.stream()
                .map(ConfigMapping::mappedName)
                .anyMatch(m -> Objects.equals(m, configMapping.mappedName()))) {
            throw new IllegalArgumentException(PROPERTY_ALREADY_DEFINED.formatted(configMapping.mappedName()));
        }

        if (configMappings.stream()
                .map(ConfigMapping::originalName)
                .anyMatch(o -> Objects.equals(o, configMapping.originalName()))) {
            throw new IllegalArgumentException(PROPERTY_ALREADY_MAPPED.formatted(configMapping.originalName()));
        }

        configMappings.add(configMapping);
        properties.clear();
        listProperties.clear();
    }

    private void generateMapping() {
        if (!properties.isEmpty() || !listProperties.isEmpty()) {
            return;
        }

        final Map<String, String> internalProperties = new HashMap<>();
        final Map<String, List<String>> internalListProperties = new HashMap<>();
        wrappedSource.getPropertyNames().forEach(propertyName -> {
            if (wrappedSource.isListProperty(propertyName)) {
                internalListProperties.put(propertyName, wrappedSource.getListValue(propertyName));
            } else {
                internalProperties.put(propertyName, wrappedSource.getValue(propertyName));
            }
        });

        final Map<String, String> mappedProperties = new HashMap<>();
        final Map<String, List<String>> mappedListProperties = new HashMap<>();

        configMappings.forEach(configMapping -> {
            final String mappedName = configMapping.mappedName();
            final String originalName = configMapping.originalName();

            if (internalProperties.containsKey(mappedName) || internalListProperties.containsKey(mappedName)) {
                logger.warn(DUPLICATE_PROPERTY, mappedName);
            } else if (!internalProperties.containsKey(originalName)
                    && !internalListProperties.containsKey(originalName)) {
                logger.warn(PROPERTY_NOT_FOUND, originalName);
            } else {
                if (wrappedSource.isListProperty(originalName)) {
                    mappedListProperties.put(mappedName, internalListProperties.get(originalName));
                } else {
                    mappedProperties.put(mappedName, internalProperties.get(originalName));
                }
                logger.debug("Added config mapping: {}", configMapping);
            }
        });

        properties.putAll(internalProperties);
        properties.putAll(mappedProperties);
        listProperties.putAll(internalListProperties);
        listProperties.putAll(mappedListProperties);
    }

    @NonNull
    @Override
    public Set<String> getPropertyNames() {
        generateMapping();
        return Stream.concat(properties.keySet().stream(), listProperties.keySet().stream())
                .collect(Collectors.toSet());
    }

    @Nullable
    @Override
    public String getValue(@NonNull final String propertyName) throws NoSuchElementException {
        generateMapping();
        Objects.requireNonNull(propertyName, "propertyName must not be null");
        if (isListProperty(propertyName)) {
            throw new NoSuchElementException("Property " + propertyName + " is a list property");
        }

        return properties.get(propertyName);
    }

    @Override
    public boolean isListProperty(@NonNull final String propertyName) throws NoSuchElementException {
        generateMapping();
        Objects.requireNonNull(propertyName, "propertyName must not be null");
        return listProperties.containsKey(propertyName);
    }

    @NonNull
    @Override
    public List<String> getListValue(@NonNull final String propertyName) throws NoSuchElementException {
        generateMapping();
        Objects.requireNonNull(propertyName, "propertyName must not be null");
        if (!isListProperty(propertyName)) {
            throw new NoSuchElementException("Property " + propertyName + " is not a list property");
        }

        return listProperties.get(propertyName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrdinal() {
        return wrappedSource.getOrdinal();
    }
}
