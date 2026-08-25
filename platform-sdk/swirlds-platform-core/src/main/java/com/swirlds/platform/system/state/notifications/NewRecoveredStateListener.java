// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import org.hiero.consensus.model.notification.DispatchMode;
import org.hiero.consensus.model.notification.DispatchModel;
import org.hiero.consensus.model.notification.DispatchOrder;
import org.hiero.consensus.model.notification.Listener;

/**
 * A method that listens for the signed state created as a result of event recovery.
 */
@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.UNORDERED)
public interface NewRecoveredStateListener extends Listener<NewRecoveredStateNotification> {}
