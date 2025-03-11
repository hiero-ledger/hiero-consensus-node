// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.exceptions;

/**
 * This type of exception is thrown if a problem is encountered during a merkle route operation.
 */
public class MerkleRouteException extends RuntimeException {

    public MerkleRouteException() {
        super();
    }

    public MerkleRouteException(String message) {
        super(message);
    }

    public MerkleRouteException(String message, Throwable cause) {
        super(message, cause);
    }

    public MerkleRouteException(Throwable cause) {
        super(cause);
    }
}
