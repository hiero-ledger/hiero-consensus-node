// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.Validator.LogFilter;
import org.hiero.otter.fixtures.logging.StructuredLog;

public record LogNodeFilter(boolean include, @NonNull Set<Long> nodeIds) implements LogFilter {
    @Override
    public boolean filter(@NonNull final StructuredLog logMsg) {
        return include != nodeIds.contains(logMsg.nodeId());
    }
}
