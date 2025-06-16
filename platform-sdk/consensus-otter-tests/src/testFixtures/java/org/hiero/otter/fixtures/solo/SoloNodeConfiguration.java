// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.solo;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.NodeConfiguration;

/**
 * An implementation of {@link NodeConfiguration} for a Solo environment.
 */
public class SoloNodeConfiguration implements NodeConfiguration<SoloNodeConfiguration> {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SoloNodeConfiguration set(@NonNull final String key, final boolean value) {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SoloNodeConfiguration set(@NonNull final String key, @NonNull final String value) {
        throw new UnsupportedOperationException("Not implemented yet!");
    }
}
