// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.status.triggers.StatusMachineTrigger;

/**
 * A functional interface for submitting status actions
 */
@FunctionalInterface
public interface TriggerSubmitter {
    /**
     * Submit a status action, which will be processed in the order received
     *
     * @param action the action to submit
     */
    void submitStatusAction(@NonNull final StatusMachineTrigger action);
}
