// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Implementation of {@link SingleNodeLogResult} that stores the log results for a single node.
 *
 * @param nodeId the ID of the node
 * @param logs the list of log entries for the node
 */
public record SingleNodeLogResultImpl(NodeId nodeId, List<StructuredLog> logs) implements SingleNodeLogResult {}
