// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import org.hiero.consensus.model.state.StateSavingResult;

public interface SavedStateActionTrigger {
    PlatformStatusAction trigger(StateSavingResult stateSavingResult);
}
