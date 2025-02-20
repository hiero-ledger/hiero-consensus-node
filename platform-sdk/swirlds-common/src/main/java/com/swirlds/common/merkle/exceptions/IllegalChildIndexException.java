// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.exceptions;

public class IllegalChildIndexException extends IllegalArgumentException {
    public IllegalChildIndexException(final int lowerIndex, final int upperIndex, final int index) {
        super(String.format("Child Index range supported: [%d, %d]. Requested: %d", lowerIndex, upperIndex, index));
    }
}
