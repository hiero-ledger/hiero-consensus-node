// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.io;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String path) {
        super(String.format("Resource not found in path: %s\n", path));
    }
}
