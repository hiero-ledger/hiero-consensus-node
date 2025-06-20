// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.exceptions;

public class IllegalChildCountException extends IllegalArgumentException {
    public IllegalChildCountException(
            long classId, int version, int minimumChildCount, int maximumChildCount, int givenChildCount) {
        super(String.format(
                "Node with class ID %d(0x%08X) at version %d requires at least %d children and no "
                        + "more than %d children, but %d children were provided.",
                classId, classId, version, minimumChildCount, maximumChildCount, givenChildCount));
    }
}
