// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A simple version of a wiring model for scenarios where the wiring model is not needed.
 */
public final class TestWiringModelBuilder {

    private TestWiringModelBuilder() {}

    /**
     * Build a wiring model using the default configuration.
     *
     * @return a new wiring model
     */
    @NonNull
    public static WiringModel create() {
        return WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();
    }
}
