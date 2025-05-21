// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * Default implementation of {@link SingleNodeStatusProgression}
 *
 * @param nodeId the node ID
 * @param statusProgression the list of platform status progression
 */
public record SingleNodeStatusProgressionImpl(@NonNull NodeId nodeId, @NonNull List<PlatformStatus> statusProgression)
        implements SingleNodeStatusProgression {}
