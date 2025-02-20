// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.emergency;

import com.swirlds.logging.api.internal.emergency.EmergencyLoggerImpl;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class is used to get an instance of the emergency logger.
 */
public final class EmergencyLoggerProvider {

    /**
     * Private constructor to prevent instantiation.
     */
    private EmergencyLoggerProvider() {}

    /**
     * Gets an instance of the emergency logger.
     *
     * @return an instance of the emergency logger
     */
    @NonNull
    public static EmergencyLogger getEmergencyLogger() {
        return EmergencyLoggerImpl.getInstance();
    }
}
