// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility.exceptions;

import com.swirlds.logging.legacy.LogMarker;

public class PlatformException extends RuntimeException {

    private final LogMarker logMarker;

    public PlatformException(final LogMarker logMarker) {
        this.logMarker = logMarker;
    }

    public PlatformException(final String message, final LogMarker logMarker) {
        super(message);
        this.logMarker = logMarker;
    }

    public PlatformException(final String message, final Throwable cause, final LogMarker logMarker) {
        super(message, cause);
        this.logMarker = logMarker;
    }

    public PlatformException(final Throwable cause, final LogMarker logMarker) {
        super(cause);
        this.logMarker = logMarker;
    }

    public LogMarker getLogMarkerInfo() {
        return logMarker;
    }
}
