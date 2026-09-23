// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.wiring.framework;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;

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
