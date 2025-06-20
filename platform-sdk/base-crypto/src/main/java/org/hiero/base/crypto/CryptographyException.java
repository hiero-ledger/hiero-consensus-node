// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.swirlds.logging.legacy.LogMarker;
import org.hiero.base.exceptions.PlatformException;

public class CryptographyException extends PlatformException {
    private static final LogMarker DEFAULT_MARKER = LogMarker.EXCEPTION;

    public CryptographyException(final LogMarker logMarker) {
        super(logMarker);
    }

    public CryptographyException(final String message, final LogMarker logMarker) {
        super(message, logMarker);
    }

    public CryptographyException(final String message, final Throwable cause, final LogMarker logMarker) {
        super(message, cause, logMarker);
    }

    public CryptographyException(final Throwable cause, final LogMarker logMarker) {
        super(cause, logMarker);
    }

    public CryptographyException(final Throwable cause) {
        super(cause, DEFAULT_MARKER);
    }
}
