// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

/**
 * Default implementation of {@link MultipleNodeReconnectResults}
 *
 * @param reconnectResults the list of {@link SingleNodeReconnectResult}
 */
public record MultipleNodeReconnectResultsImpl(@NonNull List<SingleNodeReconnectResult> reconnectResults)
        implements MultipleNodeReconnectResults {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeReconnectResults suppressingNode(@NonNull final NodeId nodeId) {
        final List<SingleNodeReconnectResult> filtered = reconnectResults.stream()
                .filter(it -> Objects.equals(it.nodeId(), nodeId))
                .toList();
        return new MultipleNodeReconnectResultsImpl(filtered);
    }
}
