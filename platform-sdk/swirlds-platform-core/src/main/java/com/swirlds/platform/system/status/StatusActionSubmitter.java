// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status;

import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A functional interface for submitting status actions
 */
@FunctionalInterface
public interface StatusActionSubmitter {
    /**
     * Submit a status action, which will be processed in the order received
     *
     * @param action the action to submit
     */
    void submitStatusAction(@NonNull final PlatformStatusAction action);
}
