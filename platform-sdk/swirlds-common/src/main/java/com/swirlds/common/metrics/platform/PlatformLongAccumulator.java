// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.common.metrics.PlatformMetric;
import com.swirlds.metrics.impl.DefaultLongAccumulator;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A long accumulator metric that is associated with the platform.
 */
public class PlatformLongAccumulator extends DefaultLongAccumulator implements PlatformMetric {

    /**
     * Constructs a new PlatformLongAccumulator with the given configuration.
     * @param config the configuration for this long accumulator
     */
    public PlatformLongAccumulator(@NonNull final Config config) {
        super(config);
    }
}
