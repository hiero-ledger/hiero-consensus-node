// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.listeners;

import com.swirlds.common.notification.DispatchMode;
import com.swirlds.common.notification.DispatchModel;
import com.swirlds.common.notification.DispatchOrder;
import com.swirlds.common.notification.Listener;

/**
 * The interface that must be implemented by all notification listeners {@link Listener} listening for
 * notification when state is written to disk.
 */
@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.ORDERED)
public interface StateWriteToDiskCompleteListener extends Listener<StateWriteToDiskCompleteNotification> {}
