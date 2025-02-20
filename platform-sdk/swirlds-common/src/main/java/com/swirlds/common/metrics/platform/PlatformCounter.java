// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.common.metrics.PlatformMetric;
import com.swirlds.metrics.impl.DefaultCounter;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A counter metric that is associated with the platform.
 */
public class PlatformCounter extends DefaultCounter implements PlatformMetric {

    /**
     * Constructs a new PlatformCounter with the given configuration.
     * @param config the configuration for this counter
     */
    public PlatformCounter(@NonNull final Config config) {
        super(config);
    }
}
