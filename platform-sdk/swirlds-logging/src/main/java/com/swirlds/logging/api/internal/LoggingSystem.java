// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal;

import com.swirlds.base.internal.BaseExecutorFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.ConfigUtils;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.emergency.EmergencyLogger;
import com.swirlds.logging.api.extensions.emergency.EmergencyLoggerProvider;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogEventConsumer;
import com.swirlds.logging.api.extensions.event.LogEventFactory;
import com.swirlds.logging.api.extensions.handler.LogHandler;
import com.swirlds.logging.api.extensions.handler.LogHandlerFactory;
import com.swirlds.logging.api.extensions.provider.LogProvider;
import com.swirlds.logging.api.extensions.provider.LogProviderFactory;
import com.swirlds.logging.api.internal.event.SimpleLogEventFactory;
import com.swirlds.logging.api.internal.level.HandlerLoggingLevelConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The implementation of the logging system.
 */
public class LoggingSystem implements LogEventConsumer {
    private static final int FLUSH_FREQUENCY = 1000;
    private static final String LOGGING_HANDLER_PREFIX = "logging.handler.";
    private static final int LOGGING_HANDLER_PREFIX_LENGTH = LOGGING_HANDLER_PREFIX.length();
    private static final String LOGGING_HANDLER_TYPE = LOGGING_HANDLER_PREFIX + "%s.type";

    /**
     * The emergency logger that is used to log errors that occur during the logging process.
     */
    private static final EmergencyLogger EMERGENCY_LOGGER = EmergencyLoggerProvider.getEmergencyLogger();

    /**
     * The name of the root logger.
     */
    public static final String ROOT_LOGGER_NAME = "";

    /**
     * The Configuration object
     */
    private volatile Configuration configuration;

    /**
     * The handlers of the logging system.
     */
    private final List<LogHandler> handlers;

    /**
     * The already created loggers of the logging system.
     */
    private final Map<String, LoggerImpl> loggers;

    /**
     * The level configuration of the logging system that checks if a specific logger is enabled for a specific level.
     */
    private final AtomicReference<HandlerLoggingLevelConfig> levelConfig;

    /**
     * The factory that is used to create log events.
     */
    private final LogEventFactory logEventFactory = new SimpleLogEventFactory();

    /**
     * Creates a new logging system.
     *
     * @param configuration the configuration of the logging system
     */
    public LoggingSystem(@NonNull final Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.handlers = new CopyOnWriteArrayList<>();
        this.loggers = new ConcurrentHashMap<>();
        this.levelConfig = new AtomicReference<>(HandlerLoggingLevelConfig.create(configuration, null));
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopAndFinalize)); // makes sure all things get to disk
        BaseExecutorFactory.getInstance() // Add a forced flush for the handlers at regular cadence
                .scheduleAtFixedRate(this::flushHandlers, FLUSH_FREQUENCY, FLUSH_FREQUENCY, TimeUnit.MILLISECONDS);
    }

    /**
     * Flush all active handlers.
     * Logs to the {@code EMERGENCY_LOGGER} in case of error
     */
    private void flushHandlers() {
        handlers.forEach(h -> {
            try {
                if (h.isActive()) {
                    h.flush();
                }
            } catch (Exception e) {
                EMERGENCY_LOGGER.log(Level.WARN, "Unexpected error flushing " + h.getName(), e);
            }
        });
    }

    /**
     * Updates the logging system with the given configuration.
     *
     * @param configuration the configuration to update the logging system with
     * @implNote Currently only the level and marker configuration is updated. New handlers are not added and existing
     * handlers are not removed for now.
     */
    public synchronized void update(final @NonNull Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        if (ConfigUtils.haveEqualProperties(this.configuration, configuration)) {
            return;
        }
        this.configuration = configuration;
        this.levelConfig.set(HandlerLoggingLevelConfig.create(configuration, null));
        this.handlers.forEach(handler -> handler.update(configuration));
    }

    /**
     * Adds a new handler to the logging system.
     *
     * @param handler the handler to add
     */
    public void addHandler(@NonNull final LogHandler handler) {
        if (handler == null) {
            EMERGENCY_LOGGER.logNPE("handler");
        } else {
            handlers.add(handler);
        }
    }

    /**
     * Removes a handler from the logging system.
     *
     * @param handler the handler to remove
     */
    public void removeHandler(@NonNull final LogHandler handler) {
        if (handler == null) {
            EMERGENCY_LOGGER.logNPE("handler");
        } else {
            handlers.remove(handler);
        }
    }

    /**
     * Returns the logger with the given name.
     *
     * @param name the name of the logger
     * @return the logger with the given name
     */
    @NonNull
    public LoggerImpl getLogger(@NonNull final String name) {
        if (name == null) {
            EMERGENCY_LOGGER.logNPE("name");
            return loggers.computeIfAbsent(ROOT_LOGGER_NAME, n -> new LoggerImpl(n, logEventFactory, this));
        }
        return loggers.computeIfAbsent(name.trim(), n -> new LoggerImpl(n, logEventFactory, this));
    }

    /**
     * Checks if the logger with the given name is enabled for the given level.
     *
     * @param name  the name of the logger
     * @param level the level to check
     * @return true, if the logger with the given name is enabled for the given level, otherwise false
     */
    public boolean isEnabled(@NonNull final String name, @NonNull final Level level, @Nullable final Marker marker) {
        if (name == null) {
            EMERGENCY_LOGGER.logNPE("name");
            return isEnabled(ROOT_LOGGER_NAME, level, marker);
        }
        if (level == null) {
            EMERGENCY_LOGGER.logNPE("level");
            return true;
        }
        for (final LogHandler handler : handlers) {
            if (handler.isEnabled(name, level, marker)) {
                return true;
            }
        }

        if (marker == null) { // favor inlining
            return levelConfig.get().isEnabled(name, level);
        } else {
            return levelConfig.get().isEnabled(name, level, marker);
        }
    }

    /**
     * Process the event if any of the handlers is able to handle it
     *
     * @param event the event to process
     */
    public void accept(@NonNull final LogEvent event) {
        if (event == null) {
            EMERGENCY_LOGGER.logNPE("event");
            return;
        }
        try {
            boolean handled = false;
            for (LogHandler logHandler : handlers) {
                if (logHandler.isEnabled(event.loggerName(), event.level(), event.marker())) {
                    try {
                        logHandler.handle(event);
                        handled = true;
                    } catch (final Throwable throwable) {
                        EMERGENCY_LOGGER.log(
                                Level.ERROR,
                                "Exception in handling log event by logHandler "
                                        + logHandler.getClass().getName(),
                                throwable);
                    }
                }
            }
            if (!handled && levelConfig.get().isEnabled(event.loggerName(), event.level(), event.marker())) {
                EMERGENCY_LOGGER.log(event);
            }
        } catch (final Throwable throwable) {
            EMERGENCY_LOGGER.log(Level.ERROR, "Exception in handling log event", throwable);
        }
    }

    /**
     * Loads all {@link LogProviderFactory} instances by SPI / {@link ServiceLoader} and installs them into the logging
     * system.
     */
    public void installProviders() {
        final ServiceLoader<LogProviderFactory> serviceLoader = ServiceLoader.load(LogProviderFactory.class);
        final List<LogProvider> providers = serviceLoader.stream()
                .map(Provider::get)
                .map(factory -> factory.create(configuration))
                .filter(LogProvider::isActive)
                .toList();
        providers.forEach(p -> p.install(getLogEventFactory(), this));
        EMERGENCY_LOGGER.log(Level.DEBUG, providers.size() + " logging providers installed: " + providers);
    }

    /**
     * Loads all {@link LogHandlerFactory} instances by SPI / {@link ServiceLoader} and installs them into the logging
     * system.
     */
    public void installHandlers() {
        final Map<String, LogHandlerFactory> servicesMap = ServiceLoader.load(LogHandlerFactory.class).stream()
                .map(Provider::get)
                .collect(Collectors.toUnmodifiableMap(LogHandlerFactory::getTypeName, Function.identity()));
        final Set<String> handlerNames = configuration
                .getPropertyNames()
                .filter(property -> property.startsWith(LOGGING_HANDLER_PREFIX))
                .map(property -> {
                    final int index = property.indexOf('.', LOGGING_HANDLER_PREFIX_LENGTH);
                    return property.substring(LOGGING_HANDLER_PREFIX_LENGTH, index);
                })
                .collect(Collectors.toUnmodifiableSet());

        final List<LogHandler> handlers = handlerNames.stream()
                .map(handlerName -> {
                    final String handlerType =
                            configuration.getValue(LOGGING_HANDLER_TYPE.formatted(handlerName), (String) null);
                    if (handlerType != null) {
                        final LogHandlerFactory handlerFactory = servicesMap.get(handlerType);
                        if (handlerFactory != null) {
                            return handlerFactory.create(handlerName, configuration);
                        }
                        EMERGENCY_LOGGER.log(
                                Level.ERROR,
                                "No handler type '%s' found for logging handler '%s'"
                                        .formatted(handlerType, handlerName));
                        return null;
                    }
                    EMERGENCY_LOGGER.log(
                            Level.ERROR, "No 'type' attribute for logging handler '%s' found".formatted(handlerName));
                    return null;
                })
                .filter(Objects::nonNull)
                .filter(LogHandler::isActive)
                .toList();

        handlers.forEach(this::addHandler);

        EMERGENCY_LOGGER.log(Level.DEBUG, handlers.size() + " logging handlers installed: " + handlers);
    }

    /**
     * Stops and finalizes the logging system.
     */
    public void stopAndFinalize() {
        handlers.forEach(LogHandler::stopAndFinalize);
    }

    /**
     * Returns the log event factory of the logging system.
     *
     * @return the log event factory of the logging system
     */
    @NonNull
    public LogEventFactory getLogEventFactory() {
        return logEventFactory;
    }

    /**
     * Returns the handler list for testing purposes
     *
     * @return handler list
     */
    @NonNull
    public List<LogHandler> getHandlers() {
        return Collections.unmodifiableList(handlers);
    }
}
