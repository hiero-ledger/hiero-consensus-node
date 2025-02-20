// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

@DispatchModel(mode = DispatchMode.ASYNC, order = DispatchOrder.UNORDERED)
public interface AsyncUnorderedIntegerListener extends Listener<IntegerNotification> {}
