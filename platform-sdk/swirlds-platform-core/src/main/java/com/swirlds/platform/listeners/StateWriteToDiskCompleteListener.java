// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.listeners;

import org.hiero.consensus.model.notification.DispatchMode;
import org.hiero.consensus.model.notification.DispatchModel;
import org.hiero.consensus.model.notification.DispatchOrder;
import org.hiero.consensus.model.notification.Listener;

/**
 * The interface that must be implemented by all notification listeners {@link Listener} listening for
 * notification when state is written to disk.
 */
@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.ORDERED)
public interface StateWriteToDiskCompleteListener extends Listener<StateWriteToDiskCompleteNotification> {}
