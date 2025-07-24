// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.listeners;

import com.swirlds.common.notification.DispatchMode;
import com.swirlds.common.notification.DispatchModel;
import com.swirlds.common.notification.DispatchOrder;
import com.swirlds.common.notification.Listener;

/**
 * Can be used to listen for changes in the platform status.
 */
@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.ORDERED)
public interface PlatformStatusChangeListener extends Listener<PlatformStatusChangeNotification> {}
//TODO delete all usages of this
