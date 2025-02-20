// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.system;

public class SystemExitException extends RuntimeException {
    private final int status;

    public SystemExitException(final int status) {
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
