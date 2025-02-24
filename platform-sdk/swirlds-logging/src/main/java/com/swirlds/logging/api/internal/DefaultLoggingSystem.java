// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal;

import com.swirlds.base.internal.BaseExecutorFactory;
import com.swirlds.base.utility.FileSystemUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.source.ConfigSource;
import com.swirlds.config.extensions.sources.PropertyFileConfigSource;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.extensions.emergency.EmergencyLogger;
import com.swirlds.logging.api.extensions.emergency.EmergencyLoggerProvider;
import com.swirlds.logging.api.extensions.handler.LogHandler;
import com.swirlds.logging.api.internal.configuration.ConfigLevelConverter;
import com.swirlds.logging.api.internal.configuration.InternalLoggingConfig;
import com.swirlds.logging.api.internal.configuration.MarkerStateConverter;
import com.swirlds.logging.api.internal.emergency.EmergencyLoggerImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The default logging system is a singleton that is used as the logging system. It acts as a wrapper around a single
 * {@link LoggingSystem} instance. In theory, it is possible to have multiple logging systems, but in practice at runtime
 * this is the only one that should be used. A custom {@link LoggingSystem} instance can for example be created for
 * tests or benchmarks.
 */
public class DefaultLoggingSystem {
    private static final String ENV_PROPERTY_LOG_PATH = "LOG_CONFIG_PATH";

    /**
     * The emergency logger.
     */
    private static final EmergencyLogger EMERGENCY_LOGGER = EmergencyLoggerProvider.getEmergencyLogger();

    /**
     * The singleton instance holder for a more flexible singleton instantiation.
     */
    private static final class InstanceHolder {

        /**
         * The real singleton instance.
         */
        private static final DefaultLoggingSystem INSTANCE = new DefaultLoggingSystem();
    }

    /**
     * Flag that defines if the logging system has been initialized.
     */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * The logging system that is internally used.
     */
    private final LoggingSystem internalLoggingSystem;

    /**
     * The default constructor.
     */
    private DefaultLoggingSystem() {
        try {
            final Configuration configuration = createConfiguration();
            this.internalLoggingSystem = new LoggingSystem(configuration);
            this.internalLoggingSystem.installHandlers();
            this.internalLoggingSystem.installProviders();

            EmergencyLoggerImpl.getInstance().publishLoggedEvents().stream()
                    .map(event -> this.internalLoggingSystem
                            .getLogEventFactory()
                            .createLogEvent(
                                    event.level(),
                                    "EMERGENCY-LOGGER-QUEUE",
                                    event.threadName(),
                                    event.timestamp(),
                                    event.message(),
                                    event.throwable(),
                                    event.marker(),
                                    event.context()))
                    .forEach(internalLoggingSystem::accept);
            initialized.set(true);

            final InternalLoggingConfig loggingConfig = configuration.getConfigData(InternalLoggingConfig.class);
            final long millis = Optional.ofNullable(loggingConfig.reloadConfigPeriod())
                    .orElse(Duration.ofSeconds(10))
                    .toMillis();
            BaseExecutorFactory.getInstance()
                    .scheduleAtFixedRate(this::updateConfiguration, 0, millis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            EMERGENCY_LOGGER.log(Level.ERROR, "Unable to initialize logging system", e);
            throw e;
        }
    }

    private void updateConfiguration() {
        final Configuration configuration = createConfiguration();
        this.internalLoggingSystem.update(configuration);
    }

    @NonNull
    private static Configuration createConfiguration() {
        final String logConfigPath = System.getenv(ENV_PROPERTY_LOG_PATH);
        final Path configFilePath =
                Optional.ofNullable(logConfigPath).map(Path::of).orElseGet(() -> Path.of("log.properties"));
        try {
            if (!FileSystemUtils.waitForPathPresence(configFilePath)) {
                throw new FileNotFoundException("File not found: " + configFilePath);
            }

            final ConfigSource configSource = new PropertyFileConfigSource(configFilePath);
            return ConfigurationBuilder.create()
                    .withSource(configSource)
                    .withConfigDataType(InternalLoggingConfig.class)
                    .withConverter(new MarkerStateConverter())
                    .withConverter(new ConfigLevelConverter())
                    .build();
        } catch (IOException e) {
            EMERGENCY_LOGGER.log(
                    Level.WARN,
                    "Unable to load logging configuration from path: '%s'. Using default configuration."
                            .formatted(configFilePath));
            return ConfigurationBuilder.create()
                    .withConfigDataType(InternalLoggingConfig.class)
                    .build();
        }
    }

    /**
     * Returns the singleton instance.
     *
     * @return The singleton instance.
     */
    @NonNull
    public static DefaultLoggingSystem getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Returns the logger with the given name.
     *
     * @param loggerName The logger name.
     * @return The logger.
     */
    @NonNull
    public Logger getLogger(@NonNull final String loggerName) {
        return internalLoggingSystem.getLogger(loggerName);
    }

    /**
     * Adds the given log handler to the logging system.
     *
     * @param logHandler The log handler.
     */
    public void addHandler(@NonNull final LogHandler logHandler) {
        internalLoggingSystem.addHandler(logHandler);
    }

    /**
     * Removes the given log handler from the logging system.
     *
     * @param logHandler
     */
    public void removeHandler(@NonNull final LogHandler logHandler) {
        internalLoggingSystem.removeHandler(logHandler);
    }

    /**
     * Returns true if the logging system has been initialized.
     *
     * @return True if the logging system has been initialized.
     */
    public boolean isInitialized() {
        return initialized.get();
    }
}
