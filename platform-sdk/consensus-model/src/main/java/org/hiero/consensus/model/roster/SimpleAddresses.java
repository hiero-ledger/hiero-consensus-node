// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.hiero.consensus.model.node.NodeId;

public record SimpleAddresses(List<SimpleAddress> addresses) {
    public Set<NodeId> getNodeIds() {
        return addresses.stream().map(a -> NodeId.of(a.nodeId())).collect(Collectors.toSet());
    }

    @Nullable
    public SimpleAddress get(final long nodeId) {
        return addresses.stream().filter(a -> a.nodeId() == nodeId).findFirst().orElse(null);
    }
}
