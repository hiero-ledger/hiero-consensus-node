// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapUtils.CONFIGURATION;

import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility methods for creating {@link VirtualMapState} instances for use in tests.
 */
public final class VirtualMapStateTestUtils {

    /**
     * Creates a virtual map state with the given label.
     * @param virtualMapLabel the label to use for the virtual map.
     * @return the created virtual map state
     */
    public static VirtualMapState createTestStateWithLabel(@NonNull final String virtualMapLabel) {
        final var virtualMap = VirtualMapUtils.createVirtualMap(CONFIGURATION, virtualMapLabel);
        return new VirtualMapState(virtualMap, new NoOpMetrics());
    }

    /**
     * Creates a virtual map state with the given virtual map.
     * @param virtualMap the virtual map to use.
     * @return the created virtual map state.
     */
    public static VirtualMapState createTestStateWithVM(@NonNull final VirtualMap virtualMap) {
        return new VirtualMapState(virtualMap, new NoOpMetrics());
    }

    /**
     * Creates a virtual map state with a default label.
     * @return the created virtual map state.
     */
    public static VirtualMapState createTestState() {
        return new VirtualMapState(CONFIGURATION, new NoOpMetrics());
    }

    private VirtualMapStateTestUtils() {}
}
