/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.system.events;

import com.hedera.hapi.platform.event.EventDescriptor;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.event.AncientMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

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
        Objects.requireNonNull(sb).append('(')
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
