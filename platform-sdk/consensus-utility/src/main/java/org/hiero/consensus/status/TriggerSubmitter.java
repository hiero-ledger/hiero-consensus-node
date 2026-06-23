// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.status.triggers.StatusMachineTrigger;

/**
 * A functional interface for submitting status triggers
 */
@FunctionalInterface
public interface TriggerSubmitter {
    /**
     * Submit a status trigger, which will be processed in the order received
     *
     * @param trigger the trigger to submit
     */
    void submitTrigger(@NonNull final StatusMachineTrigger trigger);
}
