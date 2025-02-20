// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * Describes an incident that is scheduled to occur at a specific time after genesis
 */
public interface PlannedIncident {
    /**
     * Get the amount of time after genesis when the incident should be triggered
     *
     * @return the amount of time after genesis when the incident should be triggered
     */
    @NonNull
    Duration getTimeAfterGenesis();

    /**
     * Get a brief descriptor of the incident for logging purposes
     *
     * @return a descriptor of the incident
     */
    @NonNull
    String getDescriptor();
}
