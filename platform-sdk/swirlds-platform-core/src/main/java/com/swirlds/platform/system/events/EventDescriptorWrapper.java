// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.events;

import com.hedera.hapi.platform.event.EventDescriptor;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.event.AncientMode;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A wrapper class for {@link EventDescriptor} that includes the hash of the event descriptor.
 */
public record EventDescriptorWrapper(
        @NonNull EventDescriptor eventDescriptor, @NonNull Hash hash, @NonNull NodeId creator) {
    public static final long CLASS_ID = 0x825e17f25c6e2566L;

    public EventDescriptorWrapper(@NonNull final EventDescriptor eventDescriptor) {
        this(eventDescriptor, new Hash(eventDescriptor.hash()), NodeId.of(eventDescriptor.creatorNodeId()));
    }

    /**
     * Get the value used to determine if this event is ancient or not. Will be the event's generation prior to
     * migration, and the event's birth round after migration.
     *
     * @return the value used to determine if this event is ancient or not
     */
    public long getAncientIndicator(@NonNull final AncientMode ancientMode) {
        return switch (ancientMode) {
            case GENERATION_THRESHOLD -> eventDescriptor.generation();
            case BIRTH_ROUND_THRESHOLD -> eventDescriptor.birthRound();
        };
    }
}
