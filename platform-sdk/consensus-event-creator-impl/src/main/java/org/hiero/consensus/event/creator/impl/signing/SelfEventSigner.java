// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.signing;

import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;

/**
 * Signs self events.
 */
public interface SelfEventSigner {

    /**
     * Signs an event and then returns it.
     *
     * @param event the event to sign
     * @return the signed event
     */
    @InputWireLabel("self events")
    @NonNull
    PlatformEvent signEvent(@NonNull UnsignedEvent event);
}
