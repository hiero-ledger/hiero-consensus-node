// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.level;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.utils.ConfigUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This configuration class enables the customization of logging levels for loggers, allowing users to specify levels
 * for either packages or individual classes. The configuration is read from the {@link Configuration} object.
 * <p>
 * Like for example in spring boot a configuration like this can be used:
 * <p>
 * {@code logging.level.com.swirlds=DEBUG}
 * <p>
 * In that case all packages and classes under the package {@code com.swirlds} will be configured to use the level
 * DEBUG. If a more specific configuration is defined, that configuration will be used instead. For example:
 * <p>
 * {@code logging.level.com.swirlds.logging.api=INFO}
 * <p>
 * In that case all packages and classes under the package {@code com.swirlds.logging.api} will be configured to use the
 * level INFO.
 */
public class HandlerLoggingLevelConfig {

    private static final String PROPERTY_LOGGING_LEVEL = "logging.level";
    private static final String PROPERTY_LOGGING_HANDLER_LEVEL = "logging.handler.%s.level";
    private static final String PROPERTY_PACKAGE_LEVEL = "%s.";
    private static final String PROPERTY_LOGGING_MARKER = "logging.marker";
    private static final String PROPERTY_LOGGING_HANDLER_MARKER = "logging.handler.%s.marker";
    private static final String PROPERTY_LOGGING_HANDLER_INHERIT_LEVELS = "logging.handler.%s.inheritLevels";
    private static final Level DEFAULT_LEVEL = Level.INFO;

    /**
     * The cache for the levels.
     */
    private final Map<String, Level> levelCache = new ConcurrentHashMap<>();

    /**
     * The cache for the markers.
     */
    private final Map<String, MarkerState> markerConfigCache;

    /**
     * The configuration properties.
     */
    private final Map<String, Level> levelConfigProperties;

    private HandlerLoggingLevelConfig(
            Map<String, MarkerState> markerConfigCache, Map<String, Level> levelConfigProperties) {
        this.markerConfigCache = Collections.unmodifiableMap(markerConfigCache);
        this.levelConfigProperties = Collections.unmodifiableMap(levelConfigProperties);
    }

    /**
     * Creates a new logging level configuration based on the given configuration and handler name.
     *
     * @param configuration The configuration.
     */
    public static HandlerLoggingLevelConfig create(final Configuration configuration) {
        return create(configuration, null);
    }

    /**
     * Extracts the configuration for the given handler name based on the given configuration.
     *
     * @param configuration The configuration.
     * @param handlerName   The name of the handler.
     */
    @NonNull
    public static HandlerLoggingLevelConfig create(
            @NonNull final Configuration configuration, @Nullable final String handlerName) {
        Objects.requireNonNull(configuration, "configuration must not be null");

        final ConfigLevel defaultLevel = ConfigUtils.configValueOrElse(
                configuration, PROPERTY_LOGGING_LEVEL, ConfigLevel.class, ConfigLevel.UNDEFINED);
        final Boolean inheritLevels = ConfigUtils.configValueOrElse(
                configuration,
                PROPERTY_LOGGING_HANDLER_INHERIT_LEVELS.formatted(handlerName),
                Boolean.class,
                Boolean.TRUE);

        final String propertyHandler = PROPERTY_LOGGING_HANDLER_LEVEL.formatted(handlerName);
        final ConfigLevel defaultHandlerLevel;
        if (handlerName != null) {
            defaultHandlerLevel = ConfigUtils.configValueOrElse(
                    configuration, propertyHandler, ConfigLevel.class, ConfigLevel.UNDEFINED);
        } else {
            defaultHandlerLevel = ConfigLevel.UNDEFINED;
        }

        final Map<String, Level> levelConfigProperties = new HashMap<>();
        final Map<String, MarkerState> markerConfigStore = new HashMap<>();

        if (defaultLevel == ConfigLevel.UNDEFINED && defaultHandlerLevel == ConfigLevel.UNDEFINED) {
            levelConfigProperties.put("", DEFAULT_LEVEL);
        } else if (defaultHandlerLevel != ConfigLevel.UNDEFINED) {
            levelConfigProperties.put("", defaultHandlerLevel.level());
        } else {
            if (Boolean.TRUE.equals(inheritLevels)) {
                levelConfigProperties.put("", defaultLevel.level());
            } else {
                levelConfigProperties.put("", DEFAULT_LEVEL);
            }
        }
        if (Boolean.TRUE.equals(inheritLevels)) {
            levelConfigProperties.putAll(readLevels(PROPERTY_LOGGING_LEVEL, configuration));
            markerConfigStore.putAll(readMarkers(PROPERTY_LOGGING_MARKER, configuration));
        }
        if (handlerName != null) {
            levelConfigProperties.putAll(readLevels(propertyHandler, configuration));
            markerConfigStore.putAll(
                    readMarkers(PROPERTY_LOGGING_HANDLER_MARKER.formatted(handlerName), configuration));
        }

        return new HandlerLoggingLevelConfig(markerConfigStore, levelConfigProperties);
    }

    @NonNull
    private static Map<String, MarkerState> readMarkers(
            @NonNull final String prefix, @NonNull final Configuration configuration) {
        final Map<String, MarkerState> result = new HashMap<>();
        final String fullPrefix = PROPERTY_PACKAGE_LEVEL.formatted(prefix);

        configuration.getPropertyNames().filter(n -> n.startsWith(fullPrefix)).forEach(configPropertyName -> {
            final String name = configPropertyName.substring(fullPrefix.length());
            final MarkerState markerState =
                    configuration.getValue(configPropertyName, MarkerState.class, MarkerState.UNDEFINED);
            result.put(name, markerState);
        });

        return result;
    }

    /**
     * Reads the levels from the given configuration.
     *
     * @param prefix        prefix of the configuration property
     * @param configuration current configuration
     * @return map of levels and package names
     */
    @NonNull
    private static Map<String, Level> readLevels(
            @NonNull final String prefix, @NonNull final Configuration configuration) {
        final Map<String, Level> result = new HashMap<>();
        final String fullPrefix = PROPERTY_PACKAGE_LEVEL.formatted(prefix);

        configuration.getPropertyNames().filter(n -> n.startsWith(fullPrefix)).forEach(configPropertyName -> {
            final String name = configPropertyName.substring(fullPrefix.length());
            final ConfigLevel level =
                    configuration.getValue(configPropertyName, ConfigLevel.class, ConfigLevel.UNDEFINED);
            if (level != null && level != ConfigLevel.UNDEFINED) {
                result.put(name, level.level());
            }
        });

        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns true if the given level is enabled for the given name.
     *
     * @param name  The name of the logger.
     * @param level The level.
     * @return True if the given level is enabled for the given name.
     */
    public boolean isEnabled(@NonNull final String name, @NonNull final Level level) {
        final Level enabledLevel = levelCache.computeIfAbsent(name, this::getConfiguredLevel);
        return enabledLevel.enabledLoggingOfLevel(level);
    }

    public boolean isEnabled(@NonNull final String name, @NonNull final Level level, @Nullable final Marker marker) {

        if (marker != null) {

            final List<String> allMarkerNames = marker.getAllMarkerNames();
            boolean isEnabled = false;
            boolean found = false;
            for (String markerName : allMarkerNames) {
                final MarkerState markerState = markerConfigCache.get(markerName);
                if (MarkerState.ENABLED.equals(markerState)) {
                    isEnabled = true;
                    found = true;
                    break;
                } else if (MarkerState.DISABLED.equals(markerState)) {
                    found = true;
                }
            }
            if (found) {
                return isEnabled;
            }
        }
        return isEnabled(name, level);
    }

    @NonNull
    private Level getConfiguredLevel(@NonNull final String name) {
        Level configLevel = levelConfigProperties.get(name);
        if (configLevel != null) {
            return configLevel;
        }

        final StringBuilder buffer = new StringBuilder(name);
        for (int i = buffer.length() - 1; i > 0; i--) {
            if ('.' == buffer.charAt(i)) {
                buffer.setLength(i);
                configLevel = levelConfigProperties.get(buffer.toString());
                if (configLevel != null) {
                    return configLevel;
                }
            }
        }
        return levelConfigProperties.get("");
    }
}
