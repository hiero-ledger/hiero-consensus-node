// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResults;

/**
 * Default implementation of {@link SingleNodePlatformStatusResults}
 *
 * @param nodeId the node ID
 * @param statusProgression the list of platform status progression
 */
public record SingleNodePlatformStatusResultsImpl(
        @NonNull NodeId nodeId, @NonNull List<PlatformStatus> statusProgression)
        implements SingleNodePlatformStatusResults {}
