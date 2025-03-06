// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.sources;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A config source that reads properties from a YAML file.
 * <br>
 * The config source reads the properties from the YAML file.<br>
 * <strong>Notice:</strong> We assume that the YAML file is having only one level of nesting, meaning that the first level describes
 * the config record and the second level describes the properties.
 * <br>
 * The keys of the properties are the full path of the property in the YAML file, separated by dots.
 * For example:
 * <code>
 *     a:
 *       b:
 *          c: value
 * </code>
 * For the above YAML file, the key for the property would be "a.b" to retrieve the "{c:"value"}". This complies with the way the
 * properties are stored and accessed in the {@link ConfigSource} interface.
 * <br>
 * All list elements are stored as JSON strings and can be deserialized in an {@link com.swirlds.config.api.converter.ConfigConverter}
 */
public class YamlConfigSource implements ConfigSource {

    /**
     * The logger
     */
    private static final Logger logger = LogManager.getLogger(YamlConfigSource.class);

    /**
     * The {@link ObjectMapper} used to read YAML.
     */
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * The map that contains all not-list properties.
     */
    private final Map<String, String> properties;

    /**
     * The map that contains all list properties.
     */
    private final Map<String, List<String>> listProperties;

    /**
     * The ordinal of this config source.
     */
    private final int ordinal;

    /**
     * Creates a new {@link YamlConfigSource} instance with the given file name and default ordinal.
     *
     * @param fileName the name of the file that contains the properties
     * @see ConfigSource#DEFAULT_ORDINAL
     */
    public YamlConfigSource(@NonNull final String fileName) {
        this(fileName, DEFAULT_ORDINAL);
    }

    /**
     * Creates a new {@link YamlConfigSource} instance with the given file name and ordinal.
     *
     * @param fileName the name of the file that contains the properties
     * @param ordinal the ordinal of this config source
     */
    public YamlConfigSource(@NonNull final String fileName, final int ordinal) {
        requireNonNull(fileName, "fileName must not be null");
        this.properties = new HashMap<>();
        this.listProperties = new HashMap<>();
        this.ordinal = ordinal;

        try (final InputStream resource =
                Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName)) {
            if (resource == null) {
                throw new UncheckedIOException(new IOException("Resource not found: " + fileName));
            }
            processYamlFile(resource);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read YAML file " + fileName, e);
        }
    }

    /**
     * Creates a new {@link YamlConfigSource} instance with the given file path and default ordinal.
     *
     * @param filePath the name of the file that contains the properties
     * @see ConfigSource#DEFAULT_ORDINAL
     */
    public YamlConfigSource(@NonNull final Path filePath) {
        this(filePath, DEFAULT_ORDINAL);
    }

    /**
     * Creates a new {@link YamlConfigSource} instance with the given file path and ordinal.
     *
     * @param filePath the name of the file that contains the properties
     * @param ordinal the ordinal of this config source
     */
    public YamlConfigSource(@NonNull final Path filePath, final int ordinal) {
        requireNonNull(filePath, "filePath must not be null");
        this.properties = new HashMap<>();
        this.listProperties = new HashMap<>();
        this.ordinal = ordinal;

        if (!Files.exists(filePath)) {
            logger.warn("File {} does not exist, no properties will be loaded", filePath);
            return;
        }

        try (final InputStream resource = Files.newInputStream(filePath)) {
            processYamlFile(resource);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read YAML file " + filePath, e);
        }
    }

    private void processYamlFile(@NonNull final InputStream resource) throws IOException {
        Objects.requireNonNull(resource, "resource must not be null");
        final JsonNode rootNode = YAML_MAPPER.readTree(resource);

        if (!rootNode.isObject()) {
            throw new IllegalArgumentException("Root YAML node must be an object");
        }

        // Process first level (config sections)
        rootNode.fields().forEachRemaining(configEntry -> {
            final String configName = configEntry.getKey();
            final JsonNode configNode = configEntry.getValue();

            if (!configNode.isObject()) {
                throw new IllegalArgumentException("Config section '" + configName + "' must be an object");
            }

            // Process second level (properties)
            configNode.fields().forEachRemaining(propertyEntry -> {
                final String propertyName = configName + "." + propertyEntry.getKey();
                final JsonNode propertyValue = propertyEntry.getValue();

                if (propertyValue.isArray()) {
                    // Handle array values
                    List<String> values = StreamSupport.stream(
                                    Spliterators.spliteratorUnknownSize(propertyValue.elements(), 0), false)
                            .map(this::toValueString)
                            .toList();
                    listProperties.put(propertyName, values);
                } else {
                    // Handle single values and objects (stored as raw JSON string)
                    properties.put(propertyName, toValueString(propertyValue));
                }
            });
        });
    }

    private String toValueString(@NonNull final JsonNode node) {
        // if it's a simple field we parse the value
        if (node.isValueNode()) {
            return node.asText();
        }
        // if it's an object we don't parse it, we just return the raw string representation
        return node.toString();
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Set<String> getPropertyNames() {
        return Stream.concat(properties.keySet().stream(), listProperties.keySet().stream())
                .collect(Collectors.toSet());
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public String getValue(@NonNull final String propertyName) throws NoSuchElementException {
        requireNonNull(propertyName, "propertyName must not be null");
        if (isListProperty(propertyName)) {
            throw new NoSuchElementException("Property " + propertyName + " is a list property");
        }
        return properties.get(propertyName);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isListProperty(@NonNull final String propertyName) throws NoSuchElementException {
        requireNonNull(propertyName, "propertyName must not be null");
        return listProperties.containsKey(propertyName);
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public List<String> getListValue(@NonNull final String propertyName) throws NoSuchElementException {
        requireNonNull(propertyName, "propertyName must not be null");
        if (!isListProperty(propertyName)) {
            throw new NoSuchElementException("Property " + propertyName + " is not a list property");
        }
        return listProperties.get(propertyName);
    }

    /** {@inheritDoc} */
    @Override
    public int getOrdinal() {
        return ordinal;
    }
}
