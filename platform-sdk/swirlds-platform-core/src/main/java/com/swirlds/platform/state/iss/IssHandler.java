// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss;

import com.swirlds.platform.system.state.notifications.IssNotification;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This component is responsible for handling the response to an ISS event.
 */
public interface IssHandler {

    /**
     * Handle an ISS event.
     *
     * @param issNotification the notification of the ISS event
     */
    void issObserved(@NonNull IssNotification issNotification);
}
