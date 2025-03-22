// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.io.exceptions;

import java.io.IOException;

/**
 * This exception is thrown during a sync when bad data is received, such a bad checksum.
 */
public class BadIOException extends IOException {
    private static final long serialVersionUID = 1L;

    public BadIOException(String msg) {
        super(msg);
    }
}
