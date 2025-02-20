// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.common.metrics.PlatformMetric;
import com.swirlds.metrics.impl.DefaultDoubleAccumulator;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A double accumulator metric that is associated with the platform.
 */
public class PlatformDoubleAccumulator extends DefaultDoubleAccumulator implements PlatformMetric {

    /**
     * Constructs a new PlatformDoubleAccumulator with the given configuration.
     * @param config the configuration for this double accumulator
     */
    public PlatformDoubleAccumulator(@NonNull final Config config) {
        super(config);
    }
}
