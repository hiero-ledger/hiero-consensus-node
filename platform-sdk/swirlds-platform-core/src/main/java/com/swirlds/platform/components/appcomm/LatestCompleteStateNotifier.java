// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.appcomm;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Responsible for notifying the app of the latest complete state.
 */
public interface LatestCompleteStateNotifier {
    /**
     * Submits a dispatch to the app containing the latest complete state.
     *
     * @param reservedSignedState the reserved signed state that is complete
     * @return a record containing the notification and a cleanup handler
     */
    @InputWireLabel("ReservedSignedState")
    @Nullable
    CompleteStateNotificationWithCleanup latestCompleteStateHandler(
            @NonNull final ReservedSignedState reservedSignedState);
}
