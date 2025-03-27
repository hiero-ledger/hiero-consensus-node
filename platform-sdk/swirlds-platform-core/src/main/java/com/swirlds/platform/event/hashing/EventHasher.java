// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.hashing;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Hashes events.
 */
public interface EventHasher {
    /**
     * Hashes the event and builds the event descriptor.
     *
     * @param event the event to hash
     * @return the hashed event
     */
    @InputWireLabel("unhashed event")
    @NonNull
    PlatformEvent hashEvent(@NonNull PlatformEvent event);
}
