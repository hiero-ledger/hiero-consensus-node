// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import com.hedera.hapi.platform.event.EventDescriptor;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;

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

    /**
     * Create a short string representation of this event descriptor.
     * @return a short string
     */
    public @NonNull String shortString() {
        return shortString(new StringBuilder()).toString();
    }

    /**
     * Append a short string representation of this event descriptor to the given {@link StringBuilder}.
     * @param sb the {@link StringBuilder} to append to
     * @return the given {@link StringBuilder}
     */
    public @NonNull StringBuilder shortString(@NonNull final StringBuilder sb) {
        Objects.requireNonNull(sb)
                .append('(')
                .append("CR:")
                .append(creator().id())
                .append(" ")
                .append("H:")
                .append(hash().toHex(6))
                .append(" ")
                .append("G:")
                .append(eventDescriptor().generation())
                .append(" ")
                .append("BR:")
                .append(eventDescriptor().birthRound())
                .append(')');
        return sb;
    }
}
