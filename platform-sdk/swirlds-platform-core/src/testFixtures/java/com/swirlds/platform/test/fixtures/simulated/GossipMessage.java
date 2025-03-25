// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.simulated;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.node.NodeId;

/**
 * A test message that is used for simulated gossip.
 *
 * @param message     the message to gossip
 * @param senderId    the sender of the message
 * @param recipientId the node to send the message to, or {@code null} if broadcast to all
 */
public record GossipMessage(@NonNull SelfSerializable message, @NonNull NodeId senderId, @Nullable NodeId recipientId) {

    /**
     * Create a gossip message with all nodes as recipients.
     *
     * @param message  the message to gossip
     * @param senderId the id of the sender
     * @return the gossip message
     */
    public static @NonNull GossipMessage toAll(
            @NonNull final SelfSerializable message, @NonNull final NodeId senderId) {
        Objects.requireNonNull(message);
        Objects.requireNonNull(senderId);
        return new GossipMessage(message, senderId, null);
    }

    /**
     * Create a gossip message with a single node recipient.
     *
     * @param message     the message to gossip
     * @param senderId    the id of the sender
     * @param recipientId the recipient of the message
     * @return the gossip message
     */
    public static @NonNull GossipMessage toPeer(
            @NonNull final SelfSerializable message, @NonNull final NodeId senderId, final NodeId recipientId) {
        Objects.requireNonNull(message);
        Objects.requireNonNull(senderId);
        return new GossipMessage(message, senderId, recipientId);
    }

    @Override
    public String toString() {
        return message + " from " + senderId;
    }
}
