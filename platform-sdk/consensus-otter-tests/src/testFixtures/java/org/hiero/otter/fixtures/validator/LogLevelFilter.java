// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.Level;
import org.hiero.otter.fixtures.Validator.LogFilter;
import org.hiero.otter.fixtures.logging.StructuredLog;

/**
 * A {@link LogFilter} that filters out log messages below a specified severity {@link Level}.
 *
 * @param level the minimum log level to keep; messages below this level are filtered out
 */
public record LogLevelFilter(@NonNull Level level) implements LogFilter {

    /**
     * Filters out log messages with a severity level lower than the configured {@code level}.
     *
     * @param logMsg the structured log message to evaluate
     *
     * @return {@code true} if the message level is less than the configured level (i.e., should be filtered),
     *         {@code false} otherwise
     */
    @Override
    public boolean filter(@NonNull final StructuredLog logMsg) {
        return logMsg.level().intLevel() < level.intLevel();
    }
}
