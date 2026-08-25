// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import org.hiero.consensus.model.notification.DispatchMode;
import org.hiero.consensus.model.notification.DispatchModel;
import org.hiero.consensus.model.notification.DispatchOrder;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.Listener;

/**
 * A method that listens for an ISS event. This listener provides no ordering guarantees with respect to
 * other notifications.
 */
@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.ORDERED)
public interface IssListener extends Listener<IssNotification> {}
