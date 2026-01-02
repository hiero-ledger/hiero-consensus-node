// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import edu.umd.cs.findbugs.annotations.NonNull;

public class FileUtils {

    /**
     * Returns the user directory path specified by the {@code user.dir} system property.
     *
     * @return the user directory path
     */
    public static @NonNull String getUserDir() {
        return System.getProperty("user.dir");
    }
}
