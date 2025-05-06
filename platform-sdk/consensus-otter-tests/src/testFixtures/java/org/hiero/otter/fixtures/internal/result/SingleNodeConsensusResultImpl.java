// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Default implementation of {@link SingleNodeConsensusResult}
 */
public record SingleNodeConsensusResultImpl(@NonNull NodeId nodeId, @NonNull List<ConsensusRound> consensusRounds)
        implements SingleNodeConsensusResult {}
