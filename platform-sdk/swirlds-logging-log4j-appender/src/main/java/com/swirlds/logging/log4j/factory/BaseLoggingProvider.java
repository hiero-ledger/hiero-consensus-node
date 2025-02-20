// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.log4j.factory;

import com.google.auto.service.AutoService;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Loggers;
import com.swirlds.logging.api.extensions.emergency.EmergencyLogger;
import com.swirlds.logging.api.extensions.emergency.EmergencyLoggerProvider;
import org.apache.logging.log4j.spi.Provider;

/**
 * This class is a factory registering the {@link BaseLoggerContextFactory} as the provider for the Log4J2 logging system.
 * <p>
 * This will allow the {@link BaseLoggerContextFactory} to be used as backend for the slf4j api
 */
@AutoService(Provider.class)
public class BaseLoggingProvider extends Provider {
    /**
     * The priority of this provider.
     */
    private static final int PRIORITY = 15;
    /**
     * The version of the Log4J2 API that this provider supports.
     */
    private static final String SUPPORTED_VERSION = "2.6.0";
    /**
     * The swirlds emergency logger.
     */
    private static final EmergencyLogger EMERGENCY_LOGGER = EmergencyLoggerProvider.getEmergencyLogger();
    /**
     * Creates a new logging provider and logs to force the initialisation of the base logging system
     */
    public BaseLoggingProvider() {
        super(PRIORITY, SUPPORTED_VERSION, BaseLoggerContextFactory.class);
        if (!Loggers.init()) {
            EMERGENCY_LOGGER.log(Level.ERROR, "Failed to initialise the base logging system");
        }
    }
}
