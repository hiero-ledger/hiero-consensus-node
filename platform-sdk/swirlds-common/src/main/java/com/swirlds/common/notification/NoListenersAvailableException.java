// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

public class NoListenersAvailableException extends DispatchException {

    private static final String DEFAULT_MESSAGE = "Unable to dispatch when no listeners have been registered";

    public NoListenersAvailableException() {
        super(DEFAULT_MESSAGE);
    }

    public NoListenersAvailableException(final String message) {
        super(message);
    }

    public NoListenersAvailableException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public NoListenersAvailableException(final Throwable cause) {
        super(cause);
    }
}
