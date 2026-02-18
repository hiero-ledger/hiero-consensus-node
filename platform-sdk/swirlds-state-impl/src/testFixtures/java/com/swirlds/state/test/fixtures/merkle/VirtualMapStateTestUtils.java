// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapUtils.CONFIGURATION;

import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.metrics.noop.NoOpMetrics;

/**
 * Utility methods for creating {@link VirtualMapStateImpl} instances for use in tests.
 */
public final class VirtualMapStateTestUtils {

    /**
     * Creates a virtual map state with the given virtual map.
     * @param virtualMap the virtual map to use.
     * @return the created virtual map state.
     */
    public static VirtualMapStateImpl createTestStateWithVM(@NonNull final VirtualMap virtualMap) {
        return new VirtualMapStateImpl(virtualMap, new NoOpMetrics());
    }

    /**
     * Creates a virtual map state with a default label.
     * @return the created virtual map state.
     */
    public static VirtualMapStateImpl createTestState() {
        return new VirtualMapStateImpl(CONFIGURATION, new NoOpMetrics());
    }

    private VirtualMapStateTestUtils() {}
}
