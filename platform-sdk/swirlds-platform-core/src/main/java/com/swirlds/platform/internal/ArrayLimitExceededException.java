// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.internal;

public class ArrayLimitExceededException extends RuntimeException {
    public ArrayLimitExceededException() {
        super();
    }

    public ArrayLimitExceededException(String message) {
        super(message);
    }
}
