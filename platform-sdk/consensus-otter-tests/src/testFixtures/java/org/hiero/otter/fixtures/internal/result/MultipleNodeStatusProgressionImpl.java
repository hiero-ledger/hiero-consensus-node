// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.result.MultipleNodeStatusProgression;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;

/**
 * Default implementation of {@link MultipleNodeStatusProgression}
 *
 * @param statusProgressions the list of {@link SingleNodeStatusProgression}
 */
public record MultipleNodeStatusProgressionImpl(@NonNull List<SingleNodeStatusProgression> statusProgressions)
        implements MultipleNodeStatusProgression {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public MultipleNodeStatusProgression ignoring(@NonNull final Node node) {
        final List<SingleNodeStatusProgression> filtered = statusProgressions.stream()
                .filter(it -> Objects.equals(it.nodeId(), node.getSelfId()))
                .toList();
        return new MultipleNodeStatusProgressionImpl(filtered);
    }
}
