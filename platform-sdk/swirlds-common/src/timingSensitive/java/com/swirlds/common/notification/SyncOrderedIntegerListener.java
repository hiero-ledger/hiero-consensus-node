// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.ORDERED)
public interface SyncOrderedIntegerListener extends Listener<IntegerNotification> {}
