// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The result of a shell command.
 *
 * @param exitCode the exit code of the command
 * @param out      text written to stdout
 * @param error    text written to stderr
 */
public record CommandResult(int exitCode, @NonNull String out, @NonNull String error) {

    /**
     * Returns true if the command exited with a zero exit code.
     */
    public boolean isSuccessful() {
        return exitCode == 0;
    }
}
