// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Thrown when a {@code .graph} file cannot be parsed.
 * <p>
 * Every message names the line it came from. A fixture file is read far more often than it is written, usually by
 * someone debugging a failure rather than its author, so a parse error that does not say where it is costs more than
 * the check is worth.
 */
public class GraphParseException extends RuntimeException {

    /**
     * @param line    the one-based line number the error was found on
     * @param message what is wrong with it
     */
    public GraphParseException(final int line, @NonNull final String message) {
        super("line %d: %s".formatted(line, message));
    }
}
