// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_MICROSECONDS;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.platform.metrics.StateMetrics;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * A utility class with useful methods for implementations of {@link SwirldStateManager}.
 */
public final class SwirldStateManagerUtils {

    // prevent instantiation of a static utility class
    private SwirldStateManagerUtils() {}

    /**
     * Performs a fast copy on a {@link State}. The {@code state} must not be modified during execution of this method.
     *
     * @param state           the state object to fast copy
     * @param stats           object to record stats in
     * @param softwareVersion the current software version
     * @return the newly created state copy
     */
    public static MerkleNodeState fastCopy(
            @NonNull final MerkleNodeState state,
            @NonNull final StateMetrics stats,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final PlatformStateFacade platformStateFacade) {

        Objects.requireNonNull(softwareVersion);

        final long copyStart = System.nanoTime();

        // Create a fast copy
        final MerkleNodeState copy = state.copy();
        platformStateFacade.setCreationSoftwareVersionTo(copy, softwareVersion);

        // Increment the reference count because this reference becomes the new value
        copy.getRoot().reserve();

        final long copyEnd = System.nanoTime();

        stats.stateCopyMicros((copyEnd - copyStart) * NANOSECONDS_TO_MICROSECONDS);

        return copy;
    }
}
