// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.io.exceptions;

import java.io.IOException;

/**
 * Thrown if a class the class loader can not find a class with the given class Id.
 */
public class ClassNotFoundException extends IOException {
    public ClassNotFoundException(final long classId) {
        super(String.format("No class with id=%d(0x%08X) found", classId, classId));
    }
}
