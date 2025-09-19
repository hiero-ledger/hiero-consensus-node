// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import com.swirlds.platform.system.status.actions.StateWrittenToDiskAction;
import org.hiero.consensus.model.state.StateSavingResult;

public class DefaultSavedStateActionTrigger implements SavedStateActionTrigger {
    @Override
    public PlatformStatusAction trigger(final StateSavingResult stateSavingResult) {
        return new StateWrittenToDiskAction(stateSavingResult.round(), stateSavingResult.freezeState());
    }
}
