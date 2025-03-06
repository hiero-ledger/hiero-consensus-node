// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import com.swirlds.common.notification.DispatchMode;
import com.swirlds.common.notification.DispatchModel;
import com.swirlds.common.notification.DispatchOrder;
import com.swirlds.common.notification.Listener;

/**
 * A method that listens for the signed state created as a result of event recovery.
 */
@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.UNORDERED)
public interface NewRecoveredStateListener extends Listener<NewRecoveredStateNotification> {}
