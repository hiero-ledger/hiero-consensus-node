// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import com.swirlds.common.notification.DispatchMode;
import com.swirlds.common.notification.DispatchModel;
import com.swirlds.common.notification.DispatchOrder;
import com.swirlds.common.notification.Listener;
import org.hiero.consensus.model.notification.IssNotification;

/**
 * Listener for fatal ISS events (i.e. of type SELF or CATASTROPHIC). This listener is ordered and asynchronous.
 * If you require ordered and synchronous dispatch that includes all ISS events, then use {@link IssListener}.
 */
@DispatchModel(mode = DispatchMode.ASYNC, order = DispatchOrder.ORDERED)
public interface AsyncFatalIssListener extends Listener<IssNotification> {}
