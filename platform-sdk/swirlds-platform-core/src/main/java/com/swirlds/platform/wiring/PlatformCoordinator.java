// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.swirlds.platform.components.EventWindowManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * Responsible for coordinating activities through the component's wire for the platform.
 *
 * @param components
 */
public record PlatformCoordinator(@NonNull PlatformComponents components) {

    /**
     * Constructor
     */
    public PlatformCoordinator {
        Objects.requireNonNull(components);
    }

    /**
     * Flushes the primary consensus-layer pipeline component-by-component, in upstream-to-downstream order: intake →
     * pces → gossip → hashgraph → transaction handling (pre-handler then handler) → event creation → state management.
     *
     * <p>This only flushes the pipeline; it does not decide whether in-flight work is delivered or discarded. That
     * depends on the state of the components when it is called — live components deliver (the PCES-replay caller),
     * squelched components discard (the reconnect {@code clear()} caller).
     *
     * <p>A single ordered pass leaves no work behind only while (1) no new events enter the intake module during the
     * flush and (2) the orphan buffer releases nothing in response to an event-window update. Both callers satisfy
     * these; see {@code rules/RUL-002} in the consensus-layer knowledge base for why one pass suffices and what would
     * break it.
     *
     * <p>Do not change the order of the calls below without consulting the wiring diagram.
     */
    public void flushPrimaryPipeline() {
        // Important: the order of the lines within this function matters. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        components.eventIntakeModule().flush();
        components.pcesModule().flush();
        components.gossipModule().flush();
        components.hashgraphModule().flush();
        components.transactionHandlingModule().flush();
        components.eventCreatorModule().flush();
        components.stateModule().flush();
    }

    /**
     * Inject a new event window into all components that need it.
     *
     * @param eventWindow the new event window
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        // Future work: this method can merge with consensusSnapshotOverride
        components
                .eventWindowManagerWiring()
                .getInputWire(EventWindowManager::updateEventWindow)
                .inject(eventWindow);

        // Since there is asynchronous access to the shadowgraph, it's important to ensure that
        // it has fully ingested the new event window before continuing.
        components.gossipModule().flush();
    }

    /**
     * @see EventCreatorModule#quiescenceCommandInputWire()
     */
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {
        components.statusMonitorModule().submitQuiescenceCommand(quiescenceCommand);
        components.eventCreatorModule().quiescenceCommandInputWire().inject(quiescenceCommand);
    }
}
