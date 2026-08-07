// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import java.util.function.Supplier;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;
import org.hiero.otter.fixtures.internal.OverrideProperties;
import org.jspecify.annotations.NonNull;

public class FalconNodeConfiguration extends AbstractNodeConfiguration {

    public FalconNodeConfiguration(
            @NonNull final Supplier<LifeCycle> lifecycleSupplier,
            @NonNull final OverrideProperties overrideProperties) {
        super(lifecycleSupplier, overrideProperties);
    }
}
