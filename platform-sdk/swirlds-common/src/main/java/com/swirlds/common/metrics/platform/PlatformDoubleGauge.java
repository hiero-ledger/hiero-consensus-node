// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import com.swirlds.common.metrics.PlatformMetric;
import com.swirlds.metrics.impl.DefaultDoubleGauge;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A double gauge metric that is associated with the platform.
 */
public class PlatformDoubleGauge extends DefaultDoubleGauge implements PlatformMetric {

    /**
     * Constructs a new PlatformDoubleGauge with the given configuration.
     * @param config the configuration for this double gauge
     */
    public PlatformDoubleGauge(@NonNull final Config config) {
        super(config);
    }
}
