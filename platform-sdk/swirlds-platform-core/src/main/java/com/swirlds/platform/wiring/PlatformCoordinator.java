// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static java.util.Objects.requireNonNull;

import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.platform.components.EventWindowManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Responsible for coordinating activities through the component's wire for the platform.
 */
public class PlatformCoordinator {

    private final ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring;
    private final GossipModule gossipModule;

    public PlatformCoordinator(
            @NonNull final ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring,
            @NonNull final GossipModule gossipModule) {
        this.eventWindowManagerWiring = requireNonNull(eventWindowManagerWiring);
        this.gossipModule = requireNonNull(gossipModule);
    }

    /**
     * Inject a new event window into all components that need it.
     *
     * @param eventWindow the new event window
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        // Future work: this method can merge with consensusSnapshotOverride
        eventWindowManagerWiring
                .getInputWire(EventWindowManager::updateEventWindow)
                .inject(eventWindow);

        // Since there is asynchronous access to the shadowgraph, it's important to ensure that
        // it has fully ingested the new event window before continuing.
        gossipModule.flush();
    }
}
