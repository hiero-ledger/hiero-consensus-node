// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

public interface SelfEventConsensusMonitor {
    @InputWireLabel("monitor consensus round")
    PlatformStatusAction selfEventInConsensusRound(@NonNull ConsensusRound round);
}
