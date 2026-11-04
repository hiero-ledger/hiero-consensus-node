// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.function.Supplier;
import org.hiero.consensus.status.monitor.config.PlatformStatusConfig_;
import org.hiero.otter.fixtures.internal.AbstractMiniNode.LifeCycle;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;
import org.hiero.otter.fixtures.internal.OverrideProperties;

/**
 * FalconNodeConfiguration is a specialized configuration class for Falcon nodes in the Otter test framework.
 */
public class FalconNodeConfiguration extends AbstractNodeConfiguration {

    /**
     * Constructor for FalconNodeConfiguration.
     *
     * @param lifecycleSupplier a supplier that provides the current lifecycle state of the node, used to determine if modifying the configuration is allowed
     * @param overrideProperties the properties that override the default configuration
     */
    public FalconNodeConfiguration(
            @NonNull final Supplier<LifeCycle> lifecycleSupplier,
            @NonNull final OverrideProperties overrideProperties) {
        super(lifecycleSupplier, overrideProperties);

        // OBSERVING keeps a node from branching when its PCES misses its latest self events, e.g. because the file is
        // corrupt. Falcon tests do not cover that case. With a zero delay, nodes still pass through OBSERVING but leave
        // it with the next status heartbeat.
        this.defaultProperties.withConfigValue(PlatformStatusConfig_.OBSERVING_STATUS_DELAY, Duration.ZERO);
    }
}
