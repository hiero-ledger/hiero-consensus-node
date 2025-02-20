// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;

/**
 * Config for ISS testing tool
 *
 * @param transactionsPerSecond the number of transactions per second that should be created (network wide)
 * @param plannedISSs           a list of {@link PlannedIss}s. If multiple ISS events are scheduled, it's important that
 *                              they be arranged in chronological order. Breaking this rule may cause undefined
 *                              behavior.
 * @param plannedLogErrors      a list of {@link PlannedLogError}s. If multiple errors are scheduled, it's important that
 *                              they be arranged in chronological order. Breaking this rule may cause undefined
 *                              behavior.
 */
@ConfigData("issTestingTool")
public record ISSTestingToolConfig(
        @ConfigProperty(defaultValue = "1000") int transactionsPerSecond,
        @ConfigProperty(defaultValue = "[]") List<String> plannedISSs,
        @ConfigProperty(defaultValue = "[]") List<String> plannedLogErrors) {

    /**
     * Get the list of {@link PlannedIss}s
     *
     * @return a list of {@link PlannedIss}s
     */
    @NonNull
    public List<PlannedIss> getPlannedISSs() {
        final List<PlannedIss> parsedISSs = new LinkedList<>();

        for (final String plannedISSString : plannedISSs()) {
            parsedISSs.add(PlannedIss.fromString(plannedISSString));
        }

        return parsedISSs;
    }

    /**
     * Get the list of {@link PlannedLogError}s
     *
     * @return a list of {@link PlannedLogError}s
     */
    @NonNull
    public List<PlannedLogError> getPlannedLogErrors() {
        final List<PlannedLogError> parsedPlannedLogErrors = new LinkedList<>();

        for (final String plannedLogErrorString : plannedLogErrors()) {
            parsedPlannedLogErrors.add(PlannedLogError.fromString(plannedLogErrorString));
        }

        return parsedPlannedLogErrors;
    }
}
