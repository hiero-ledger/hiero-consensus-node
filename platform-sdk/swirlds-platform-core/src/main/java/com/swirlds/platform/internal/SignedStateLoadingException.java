// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.internal;

public class SignedStateLoadingException extends RuntimeException {
    public SignedStateLoadingException() {
        super();
    }

    public SignedStateLoadingException(String message) {
        super(message);
    }

    public SignedStateLoadingException(String message, Throwable cause) {
        super(message, cause);
    }

    public SignedStateLoadingException(Throwable cause) {
        super(cause);
    }

    public SignedStateLoadingException(
            String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
