// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;

@FunctionalInterface
public interface ReconnectNotificationSubscriber {

    SubscriberAction onNotification(@NonNull ReconnectNotification<?> notification);
}
