// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config.legacy;

import com.swirlds.logging.legacy.LogMarker;
import org.hiero.base.utility.exceptions.PlatformException;

public class ConfigurationException extends PlatformException {

    public ConfigurationException() {
        super(LogMarker.EXCEPTION);
    }

    public ConfigurationException(final String message) {
        super(message, LogMarker.EXCEPTION);
    }

    public ConfigurationException(final String message, final Throwable cause) {
        super(message, cause, LogMarker.EXCEPTION);
    }

    public ConfigurationException(final Throwable cause) {
        super(cause, LogMarker.EXCEPTION);
    }
}
