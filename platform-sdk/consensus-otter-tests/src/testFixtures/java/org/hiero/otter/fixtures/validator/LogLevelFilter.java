// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.Level;
import org.hiero.otter.fixtures.Validator.LogFilter;
import org.hiero.otter.fixtures.logging.StructuredLog;

public record LogLevelFilter(@NonNull Level level) implements LogFilter {
    @Override
    public boolean filter(@NonNull final StructuredLog logMsg) {
        return logMsg.level().intLevel() < level.intLevel();
    }
}
