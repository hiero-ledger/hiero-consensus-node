// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.IOException;

// Misc json ops
public final class JsonUtils {

    private JsonUtils() {}

    public static void write(
            @NonNull final BufferedWriter writer, @NonNull final String value, boolean PRETTY_PRINT_ENABLED)
            throws IOException {
        if (PRETTY_PRINT_ENABLED) {
            writer.write(value);
        } else {
            writer.write(value.replaceAll("[\\p{C}\\s]", ""));
            writer.newLine();
        }
    }
}
