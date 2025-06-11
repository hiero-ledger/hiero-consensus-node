// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.NodeConfiguration;

/**
 * An implementation of {@link NodeConfiguration} for a container environment.
 */
public class ContainerNodeConfiguration implements NodeConfiguration<ContainerNodeConfiguration> {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ContainerNodeConfiguration set(@NonNull final String key, final boolean value) {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ContainerNodeConfiguration set(@NonNull final String key, @NonNull final String value) {
        throw new UnsupportedOperationException("Not implemented yet!");
    }
}
