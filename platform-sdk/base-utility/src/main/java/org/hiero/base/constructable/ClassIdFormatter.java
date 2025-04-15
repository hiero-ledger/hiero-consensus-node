// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable;

import org.hiero.base.io.SerializableDet;

/**
 * This class provides utility methods for converting class IDs into human readable strings.
 */
public final class ClassIdFormatter {

    private ClassIdFormatter() {}

    /**
     * Convert a class ID to a human readable string in the format "123456789(0x75BCD15)"
     *
     * @param classId
     * 		the class ID to convert to a string
     * @return a formatted string
     */
    public static String classIdString(final long classId) {
        return String.format("%d(0x%X)", classId, classId);
    }

    /**
     * Convert a runtime constructable object to a human readable string in the format
     * "com.swirlds.ClassName:123456789(0x75BCD15)"
     *
     * @param object
     * 		the object to form about
     * @return a formatted string
     */
    public static String classIdString(final RuntimeConstructable object) {
        return String.format("%s:%s", object.getClass().getName(), classIdString(object.getClassId()));
    }

    /**
     * Convert a serializable object to a human readable string in the format
     * "com.swirlds.ClassName:123456789(0x75BCD15)v1"
     *
     * @param object
     * 		the object to form about
     * @return a formatted string
     */
    public static String versionedClassIdString(final SerializableDet object) {
        return String.format(
                "%s:%sv%d", object.getClass().getName(), classIdString(object.getClassId()), object.getVersion());
    }
}
