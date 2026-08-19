// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
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
    }
}
