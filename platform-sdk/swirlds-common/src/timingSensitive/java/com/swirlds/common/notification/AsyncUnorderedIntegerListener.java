// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

import org.hiero.consensus.model.notification.DispatchMode;
import org.hiero.consensus.model.notification.DispatchModel;
import org.hiero.consensus.model.notification.DispatchOrder;
import org.hiero.consensus.model.notification.Listener;

@DispatchModel(mode = DispatchMode.ASYNC, order = DispatchOrder.UNORDERED)
public interface AsyncUnorderedIntegerListener extends Listener<IntegerNotification> {}
