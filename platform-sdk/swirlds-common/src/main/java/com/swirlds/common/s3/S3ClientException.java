// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.s3;

/**
 * A checked exception to act as a base for all S3 client exceptions.
 */
public class S3ClientException extends Exception {
    public S3ClientException() {
        super();
    }

    public S3ClientException(final String message) {
        super(message);
    }

    public S3ClientException(final Throwable cause) {
        super(cause);
    }

    public S3ClientException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
