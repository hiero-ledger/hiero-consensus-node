// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.common.metrics.PlatformMetric;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An integer gauge metric that is associated with the platform.
 */
public class PlatformIntegerGauge extends DefaultIntegerGauge implements PlatformMetric {

    /**
     * Constructs a new PlatformIntegerGauge with the given configuration.
     * @param config the configuration for this integer gauge
     */
    public PlatformIntegerGauge(@NonNull final Config config) {
        super(config);
    }
}
