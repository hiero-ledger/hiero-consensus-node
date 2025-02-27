// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config.internal;

import static com.swirlds.logging.legacy.LogMarker.CONFIG;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils;
import com.swirlds.config.extensions.sources.ConfigMapping;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class contains utility methods for the platform config.
 */
public class PlatformConfigUtils {
    private static final Logger logger = LogManager.getLogger(PlatformConfigUtils.class);
    public static final String SETTING_USED_FILENAME = "settingsUsed.txt";
    private static final String ERROR_CONFIGURATION_IS_NULL = "configuration should not be null";
    private static final String ERROR_DIRECTORY_IS_NULL = "directory should not be null";
    private static final String ERROR_STRING_BUILDER_IS_NULL = "stringBuilder should not be null";

    private PlatformConfigUtils() {
        // Utility class
    }

    /**
     * Checks the given configuration for not known configuration and mapped properties.
     *
     * @param configuration the configuration to check
     */
    public static void checkConfiguration(@NonNull final Configuration configuration) {
        Objects.requireNonNull(configuration, ERROR_CONFIGURATION_IS_NULL);
        final Set<String> configNames = getConfigNames(configuration);
        logNotKnownConfigProperties(configuration, configNames);
    }

    /**
     * Logs all configuration properties that are not known by any configuration data type as
     * {@code DEBUG} events; it is harmless to provide extra properties but could be useful to
     * see these messages during development.
     */
    private static void logNotKnownConfigProperties(
            @NonNull final Configuration configuration, @NonNull final Set<String> configNames) {
        ConfigMappings.MAPPINGS.stream().map(ConfigMapping::originalName).forEach(configNames::add);
        configuration
                .getPropertyNames()
                .filter(name -> !configNames.contains(name))
                .forEach(name -> {
                    final String message =
                            "Configuration property '%s' is not used by any configuration data type".formatted(name);
                    logger.debug(CONFIG.getMarker(), message);
                });
    }

    /**
     * Logs all applied mapped properties. And suggests to change the new property name.
     */
    static void logAppliedMappedProperties(@NonNull final Set<String> configNames) {
        final Map<String, String> mappings = ConfigMappings.MAPPINGS.stream()
                .collect(Collectors.toMap(ConfigMapping::originalName, ConfigMapping::mappedName));

        configNames.stream().filter(mappings::containsKey).forEach(name -> {
            final String message = ("Configuration property '%s' was renamed to '%s'. "
                            + "This build is currently backwards compatible with the old name, but this may not be true in "
                            + "a future release, so it is important to switch to the new name.")
                    .formatted(name, mappings.get(name));

            logger.warn(STARTUP.getMarker(), message);
        });
    }

    /**
     * Collects all configuration property names from all sources.
     *
     * @return the set of all configuration property names
     */
    @NonNull
    private static Set<String> getConfigNames(@NonNull final Configuration configuration) {
        return configuration.getConfigDataTypes().stream()
                .flatMap(configDataType -> {
                    final String propertyNamePrefix =
                            ConfigReflectionUtils.getNamePrefixForConfigDataRecord(configDataType);
                    return Arrays.stream(configDataType.getRecordComponents())
                            .map(component -> ConfigReflectionUtils.getPropertyNameForConfigDataProperty(
                                    propertyNamePrefix, component));
                })
                .collect(Collectors.toSet());
    }

    /**
     * Write all the settings to the file settingsUsed.txt, some of which might have been changed by settings.txt.
     *
     * @param directory the directory to write to
     */
    public static void writeSettingsUsed(@NonNull final Path directory, @NonNull final Configuration configuration) {
        Objects.requireNonNull(directory, ERROR_DIRECTORY_IS_NULL);
        Objects.requireNonNull(configuration, ERROR_CONFIGURATION_IS_NULL);

        try (final BufferedWriter writer = Files.newBufferedWriter(directory.resolve(SETTING_USED_FILENAME))) {
            final StringBuilder stringBuilder = new StringBuilder();
            generateSettingsUsed(stringBuilder, configuration);
            writer.write(stringBuilder.toString());

            writer.flush();
        } catch (final IOException e) {
            logger.error(EXCEPTION.getMarker(), "Error in writing to settingsUsed.txt", e);
        }
    }

    /**
     * Generate the settings used, some of which might have been changed by settings.txt.
     *
     * @param stringBuilder the string builder to write to
     * @param configuration the configuration to use
     */
    public static void generateSettingsUsed(
            @NonNull final StringBuilder stringBuilder, @NonNull final Configuration configuration) {
        Objects.requireNonNull(stringBuilder, ERROR_STRING_BUILDER_IS_NULL);
        Objects.requireNonNull(configuration, ERROR_CONFIGURATION_IS_NULL);

        stringBuilder.append("------------- Configuration Overrides -------------");
        stringBuilder.append(System.lineSeparator());
        stringBuilder.append(System.lineSeparator());

        final Set<String> propertyNames =
                configuration.getPropertyNames().collect(Collectors.toCollection(TreeSet::new));
        for (final String propertyName : propertyNames) {
            if (configuration.isListValue(propertyName)) {
                final String value =
                        Objects.requireNonNullElse(configuration.getValues(propertyName), List.of()).stream()
                                .map(Object::toString)
                                .collect(Collectors.joining(", "));
                stringBuilder.append(String.format("%s, %s%n", propertyName, value));
            } else {
                stringBuilder.append(String.format("%s, %s%n", propertyName, configuration.getValue(propertyName)));
            }
        }
    }
}
