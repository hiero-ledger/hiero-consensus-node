// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.iss.detection.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.notification.IssNotification;

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
