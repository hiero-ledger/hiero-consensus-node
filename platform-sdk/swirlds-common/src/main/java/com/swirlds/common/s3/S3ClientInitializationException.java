// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.s3;

public class S3ClientInitializationException extends S3ClientException {
    public S3ClientInitializationException() {
        super();
    }

    public S3ClientInitializationException(final String message) {
        super(message);
    }

    public S3ClientInitializationException(final Throwable cause) {
        super(cause);
    }

    public S3ClientInitializationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
