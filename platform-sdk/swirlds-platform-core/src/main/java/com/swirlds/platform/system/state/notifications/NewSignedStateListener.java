// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import com.swirlds.common.notification.DispatchMode;
import com.swirlds.common.notification.DispatchModel;
import com.swirlds.common.notification.DispatchOrder;
import com.swirlds.common.notification.Listener;

/**
 * A method that listens for new signed states. This listener provides no ordering guarantees with respect to
 * other notifications (i.e. rounds may be received out of order). May not be invoked for all rounds.
 */
@DispatchModel(mode = DispatchMode.ASYNC, order = DispatchOrder.UNORDERED)
public interface NewSignedStateListener extends Listener<NewSignedStateNotification> {}
