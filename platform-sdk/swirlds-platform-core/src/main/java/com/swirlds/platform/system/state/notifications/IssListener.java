// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import com.swirlds.common.notification.DispatchMode;
import com.swirlds.common.notification.DispatchModel;
import com.swirlds.common.notification.DispatchOrder;
import com.swirlds.common.notification.Listener;
import org.hiero.consensus.model.notification.IssNotification;

/**
 * A method that listens for an ISS event. This listener provides no ordering guarantees with respect to
 * other notifications.
 */
@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.ORDERED)
public interface IssListener extends Listener<IssNotification> {}
