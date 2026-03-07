// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.s3;

public class S3ClientInitializationException extends S3ClientException {
    public S3ClientInitializationException() {
        super();
    }

    public S3ClientInitializationException(final Throwable cause) {
        super(cause);
    }
}
