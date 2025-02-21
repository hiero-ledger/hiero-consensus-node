// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.exceptions;

/**
 * This exception is thrown when a child is added to a MerkleInternal node that is not of the expected type.
 */
public class IllegalChildTypeException extends IllegalArgumentException {
    public IllegalChildTypeException(int index, long classId, final String parentClassName) {
        super(String.format(
                "Invalid class ID %d(0x%08X) at index %d for parent with class %s",
                classId, classId, index, parentClassName));
    }
}
