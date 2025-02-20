// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.common.metrics.PlatformMetric;
import com.swirlds.metrics.impl.DefaultLongGauge;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A long gauge metric that is associated with the platform.
 */
public class PlatformLongGauge extends DefaultLongGauge implements PlatformMetric {

    /**
     * Constructs a new PlatformLongGauge with the given configuration.
     * @param config the configuration for this long gauge
     */
    public PlatformLongGauge(@NonNull final Config config) {
        super(config);
    }
}
