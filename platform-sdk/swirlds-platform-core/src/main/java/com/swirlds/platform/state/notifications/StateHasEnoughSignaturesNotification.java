// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.notifications;

import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.state.notifications.NewSignedStateNotification;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.notification.AbstractNotification;
import org.hiero.consensus.model.notification.Notification;

/**
 * A {@link Notification Notification} that a signed state has collected all necessary
 * signatures. Unlike
 * {@link NewSignedStateNotification NewSignedStateNotification}, this is
 * called on every single state that becomes signed, even if signing happens out of order. NOT called when a state is
 * read from disk or received from reconnect. State is guaranteed to hold a reservation until callback is finished.
 */
public class StateHasEnoughSignaturesNotification extends AbstractNotification {

    private final SignedState signedState;

    // FUTURE WORK:
    //  this field can be removed once PlatformContext maintains a single notification engine per platform instance
    private final NodeId selfId;

    /**
     * Create a notification for a newly signed state.
     *
     * @param signedState the state that just became signed
     * @param selfId      the ID of this node
     */
    public StateHasEnoughSignaturesNotification(final SignedState signedState, final NodeId selfId) {
        this.signedState = signedState;
        this.selfId = selfId;
    }

    /**
     * Get the signed state that has collected sufficient signatures.
     */
    public SignedState getSignedState() {
        return signedState;
    }

    /**
     * The ID of this node.
     */
    public NodeId getSelfId() {
        return selfId;
    }
}
