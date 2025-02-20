// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.exceptions;

public class IllegalChildBoundsException extends IllegalArgumentException {
    public IllegalChildBoundsException(final int lowerIndex, final int upperIndex) {
        super(String.format("Child Bounds not supported: [%d, %d].", lowerIndex, upperIndex));
    }
}
