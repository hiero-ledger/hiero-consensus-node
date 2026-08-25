// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import org.hiero.consensus.model.notification.DispatchMode;
import org.hiero.consensus.model.notification.DispatchModel;
import org.hiero.consensus.model.notification.DispatchOrder;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.Listener;

/**
 * Listener for fatal ISS events (i.e. of type SELF or CATASTROPHIC). This listener is ordered and asynchronous.
 * If you require ordered and synchronous dispatch that includes all ISS events, then use {@link IssListener}.
 */
@DispatchModel(mode = DispatchMode.ASYNC, order = DispatchOrder.ORDERED)
public interface AsyncFatalIssListener extends Listener<IssNotification> {}
