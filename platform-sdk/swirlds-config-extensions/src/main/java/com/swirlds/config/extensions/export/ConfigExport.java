// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.export;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
        final Map<String, Object> recordProperties =
                ConfigReflectionUtils.getAllPropertiesAsMap(configuration, property -> true);

        // Properties defined in property file but do not exist in record configs
        final Map<String, Object> nonRecordProperties = new HashMap<>();
        configuration
                .getPropertyNames()
                .filter(name -> !recordProperties.containsKey(name))
                .forEach(name -> nonRecordProperties.put(name, configuration.getValue(name)));

        // Write all record defined values first, in alphabetical order
        recordProperties.keySet().stream().sorted().forEach(name -> {
            final Object value = recordProperties.get(name);
            final String line = buildLine(name, value, "");
            lineConsumer.accept(line);
        });

        // Write all values not defined in records next, in alphabetical order
        nonRecordProperties.keySet().stream().sorted().forEach(name -> {
            final Object value = nonRecordProperties.get(name);
            final String line = buildLine(name, value, "  [NOT USED IN RECORD]");
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

    private static String buildLine(final String name, final Object value, final String suffix) {
        final String valueString = String.valueOf(value);
        return name + ", " + valueString + suffix;
    }

    public static void addConfigContents(
            @NonNull final Configuration configuration, @NonNull final StringBuilder builder) {
        Objects.requireNonNull(configuration, ERROR_CONFIGURATION_IS_NULL);
        Objects.requireNonNull(builder, ERROR_BUILDER_IS_NULL);
        printConfig(configuration, line -> builder.append(line).append(System.lineSeparator()));
    }
}
