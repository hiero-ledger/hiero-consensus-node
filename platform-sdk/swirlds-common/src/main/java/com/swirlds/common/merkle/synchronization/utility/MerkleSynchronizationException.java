// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.utility;

/**
 * This exception may be thrown if there is a problem during synchronization of merkle trees.
 */
public class MerkleSynchronizationException extends RuntimeException {

    public MerkleSynchronizationException(String message) {
        super(message);
    }

    public MerkleSynchronizationException(Exception ex) {
        super(ex);
    }

    public MerkleSynchronizationException(Throwable cause) {
        super(cause);
    }

    public MerkleSynchronizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
