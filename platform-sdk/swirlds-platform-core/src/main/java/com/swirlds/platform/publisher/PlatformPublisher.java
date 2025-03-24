// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.publisher;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * This component is responsible for publishing internal platform data to external subscribers. By default this is not
 * enabled, and will only publish data if handler methods are registered with the platform at startup time.
 */
public interface PlatformPublisher {

    /**
     * Publish a preconsensus event.
     *
     * @param event the event to publish
     */
    @InputWireLabel("PlatformEvent")
    void publishPreconsensusEvent(@NonNull final PlatformEvent event);

    /**
     * Publish a consensus snapshot override (i.e. what happens when we start from a node state at restart/reconnect
     * boundaries).
     *
     * @param snapshot the snapshot to publish
     */
    @InputWireLabel("ConsensusSnapshot")
    void publishSnapshotOverride(@NonNull final ConsensusSnapshot snapshot);

    /**
     * Publish a stale event.
     *
     * @param event the event to publish
     */
    void publishStaleEvent(@NonNull final PlatformEvent event);
}
