// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.common.metrics.PlatformMetric;
import com.swirlds.metrics.impl.DefaultIntegerAccumulator;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An integer accumulator metric that is associated with the platform.
 */
public class PlatformIntegerAccumulator extends DefaultIntegerAccumulator implements PlatformMetric {

    /**
     * Constructs a new PlatformIntegerAccumulator with the given configuration.
     * @param config the configuration for this integer accumulator
     */
    public PlatformIntegerAccumulator(@NonNull final Config config) {
        super(config);
    }
}
