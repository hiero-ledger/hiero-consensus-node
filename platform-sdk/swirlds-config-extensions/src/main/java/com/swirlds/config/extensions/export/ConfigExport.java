// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.export;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Class that provides functionality to print information about the config.
 */
public final class ConfigExport {

    private static final String ERROR_CONFIGURATION_IS_NULL = "configuration should not be null";
    private static final String ERROR_PRINT_STREAM_IS_NULL = "printStream should not be null";
    private static final String ERROR_BUILDER_IS_NULL = "builder should not be null";
    private static final String ERROR_LINE_CONSUMER_IS_NULL = "lineConsumer should not be null";

    private ConfigExport() {}

    /**
     * Provides information about the config with 1 line per config property to the consumer. The format of one line
     * looks like this:
     * <p>
     * <code>name, value</code>
     * </p>
     *
     * @param configuration the configuration
     * @param lineConsumer  the line consumer
     */
    public static void printConfig(
            @NonNull final Configuration configuration, @NonNull final Consumer<String> lineConsumer) {
        Objects.requireNonNull(configuration, ERROR_CONFIGURATION_IS_NULL);
        Objects.requireNonNull(lineConsumer, ERROR_LINE_CONSUMER_IS_NULL);

        // Properties defined in record configs, including values overridden by configured sources
        final Map<String, Object> recordProperties = getPropertiesForConfigDataRecords(configuration);

        // Properties defined in property file but do not exist in record configs
        final Map<String, Object> nonRecordProperties = new HashMap<>();
        configuration
                .getPropertyNames()
                .filter(name -> !recordProperties.containsKey(name))
                .forEach(name -> nonRecordProperties.put(name, configuration.getValue(name)));

        final Set<Object> allConfigValues = combine(recordProperties.values(), nonRecordProperties.values());
        final int maxValueLength = getMaxPropertyLength(allConfigValues);

        // Write all record defined values first, in alphabetical order
        recordProperties.keySet().stream().sorted().forEach(name -> {
            final Object value = recordProperties.get(name);
            final String line = buildLine(name, value, maxValueLength, "");
            lineConsumer.accept(line);
        });

        // Write all values not defined in records next, in alphabetical order
        nonRecordProperties.keySet().stream().sorted().forEach(name -> {
            final Object value = nonRecordProperties.get(name);
            final String line = buildLine(name, value, maxValueLength, "  [NOT USED IN RECORD]");
            lineConsumer.accept(line);
        });
    }

    /**
     * Writes information about the config with 1 line per config property to the given stream. The format of one line
     * looks like this:
     * <p>
     * <code>name,value    ([NOT USED IN RECORD])</code>
     * </p>
     *
     * @param configuration the configuration
     * @param printStream   the OutputStream in that the info should be written
     * @throws IOException if writing to the stream fails
     */
    public static void printConfig(@NonNull final Configuration configuration, @NonNull final OutputStream printStream)
            throws IOException {
        Objects.requireNonNull(configuration, ERROR_CONFIGURATION_IS_NULL);
        Objects.requireNonNull(printStream, ERROR_PRINT_STREAM_IS_NULL);
        final StringBuilder builder = new StringBuilder();
        printConfig(configuration, line -> builder.append(line).append(System.lineSeparator()));
        printStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static <T> Set<T> combine(final Collection<T> set1, final Collection<T> set2) {
        return Stream.concat(set1.stream(), set2.stream()).collect(Collectors.toSet());
    }

    private static String buildLine(
            final String name, final Object value, final int maxValueLength, final String suffix) {
        return name + ", " + value + createSpaces(value.toString(), maxValueLength) + suffix;
    }

    public static void addConfigContents(
            @NonNull final Configuration configuration, @NonNull final StringBuilder builder) {
        Objects.requireNonNull(configuration, ERROR_CONFIGURATION_IS_NULL);
        Objects.requireNonNull(builder, ERROR_BUILDER_IS_NULL);
        printConfig(configuration, line -> builder.append(line).append(System.lineSeparator()));
    }

    private static Map<String, Object> getPropertiesForConfigDataRecords(final Configuration configuration) {
        final Map<String, Object> map = new HashMap<>();
        configuration
                .getConfigDataTypes()
                .forEach(configDataType -> putValuesInMap(configuration.getConfigData(configDataType), map));
        return map;
    }

    private static void putValuesInMap(final Record configData, final Map<String, Object> map) {
        final Class<? extends Record> configDataType = configData.getClass();
        final String propertyNamePrefix = ConfigReflectionUtils.getNamePrefixForConfigDataRecord(configDataType);
        Arrays.stream(configDataType.getRecordComponents()).forEach(component -> {
            final String name =
                    ConfigReflectionUtils.getPropertyNameForConfigDataProperty(propertyNamePrefix, component);
            final Object value = getComponentValue(configData, component);
            map.put(name, value);
        });
    }

    private static Object getComponentValue(final Record configData, final RecordComponent component) {
        try {
            return component.getAccessor().invoke(configData);
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Can not access config value for record type '" + component.getClass() + "."
                            + component.getAccessor().getName() + "'",
                    e);
        }
    }

    private static String createSpaces(final String value, final int maxLength) {
        return IntStream.range(value.length(), maxLength).mapToObj(i -> " ").reduce("", (a, b) -> a + b);
    }

    private static int getMaxPropertyLength(final Set<?> values) {
        return values.stream()
                .map(String::valueOf)
                .mapToInt(String::length)
                .max()
                .orElse(0);
    }
}
