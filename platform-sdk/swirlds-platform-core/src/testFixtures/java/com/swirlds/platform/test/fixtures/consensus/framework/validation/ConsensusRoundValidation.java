// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Validates rounds produced by a test. The type of validation that is done depends on the implementation.
 */
@FunctionalInterface
public interface ConsensusRoundValidation {

    /**
     * Perform validation on the passed consensus rounds.
     *
     * @param round1 the round from one node
     * @param round2 the round from another node
     */
    void validate(@NonNull final ConsensusRound round1, @NonNull final ConsensusRound round2);
}
