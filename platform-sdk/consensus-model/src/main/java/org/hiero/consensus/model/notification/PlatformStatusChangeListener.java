// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.notification;

/**
 * Can be used to listen for changes in the platform status.
 */
@DispatchModel(mode = DispatchMode.SYNC, order = DispatchOrder.ORDERED)
public interface PlatformStatusChangeListener extends Listener<PlatformStatusChangeNotification> {}
