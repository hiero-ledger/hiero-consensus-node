// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.listeners;

import org.hiero.consensus.model.notification.AbstractNotification;
import org.hiero.consensus.model.notification.Notification;

/**
 * Class that provides {@link Notification} when state is loaded from disk
 */
public class StateLoadedFromDiskNotification extends AbstractNotification {

    public StateLoadedFromDiskNotification() {}
}
