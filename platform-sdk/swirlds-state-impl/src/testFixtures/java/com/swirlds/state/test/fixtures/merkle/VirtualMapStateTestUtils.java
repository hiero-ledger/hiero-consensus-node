// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapUtils.CONFIGURATION;

import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;

/**
 * Utility methods for creating {@link VirtualMapState} instances for use in tests.
 */
public final class VirtualMapStateTestUtils {

    public static final Function<VirtualMapState, Long> FAKE_ROUND_SUPPLIER = v -> -1L;

    /**
     * Creates a virtual map state with the given label.
     * @param virtualMapLabel the label to use for the virtual map.
     * @return the created virtual map state
     */
    public static VirtualMapState createStateWithLabel(@NonNull final String virtualMapLabel) {
        final var virtualMap = VirtualMapUtils.createVirtualMap(CONFIGURATION, virtualMapLabel);
        return new VirtualMapState(virtualMap, new NoOpMetrics(), FAKE_ROUND_SUPPLIER);
    }

    /**
     * Creates a virtual map state with the given virtual map.
     * @param virtualMap the virtual map to use.
     * @return the created virtual map state.
     */
    public static VirtualMapState createStateWithVM(@NonNull final VirtualMap virtualMap) {
        return new VirtualMapState(virtualMap, new NoOpMetrics(), FAKE_ROUND_SUPPLIER);
    }

    /**
     * Creates a virtual map state with a default label.
     * @return the created virtual map state.
     */
    public static VirtualMapState createState() {
        return new VirtualMapState(CONFIGURATION, new NoOpMetrics(), FAKE_ROUND_SUPPLIER);
    }

    private VirtualMapStateTestUtils() {}
}
