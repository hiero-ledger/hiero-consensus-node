// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

@DispatchModel(mode = DispatchMode.ASYNC, order = DispatchOrder.ORDERED)
public interface AsyncOrderedIntegerListener extends Listener<IntegerNotification> {}
