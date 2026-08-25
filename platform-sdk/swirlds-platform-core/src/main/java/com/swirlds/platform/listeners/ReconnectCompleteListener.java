// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.listeners;

import org.hiero.consensus.model.notification.DispatchMode;
import org.hiero.consensus.model.notification.DispatchModel;
import org.hiero.consensus.model.notification.DispatchOrder;
import org.hiero.consensus.model.notification.Listener;

/**
 * The interface that must be implemented by all reconnect notification listeners {@link Listener}.
 */
@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.ORDERED)
public interface ReconnectCompleteListener extends Listener<ReconnectCompleteNotification> {}
