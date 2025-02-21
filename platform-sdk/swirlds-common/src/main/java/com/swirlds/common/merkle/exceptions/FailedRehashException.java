// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.exceptions;

/**
 * This type of exception is thrown if there is a problem with rehashing of a merkle node.
 */
public class FailedRehashException extends RuntimeException {
    public FailedRehashException(final Throwable cause) {
        super(cause);
    }
}
