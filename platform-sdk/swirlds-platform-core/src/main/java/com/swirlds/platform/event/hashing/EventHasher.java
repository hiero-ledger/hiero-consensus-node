// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.hashing;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.event.PlatformEvent;
import edu.umd.cs.findbugs.annotations.NonNull;

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
