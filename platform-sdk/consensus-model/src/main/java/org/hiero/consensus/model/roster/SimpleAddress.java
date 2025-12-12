package org.hiero.consensus.model.roster;

import com.hedera.hapi.node.base.ServiceEndpoint;
import java.util.List;

public record SimpleAddress(long nodeId, long weight, List<ServiceEndpoint> serviceEndpoints, String memo) {
    public SimpleAddress(long nodeId, long weight) {
        this(nodeId, weight, List.of(), "");
    }
}
