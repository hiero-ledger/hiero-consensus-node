// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import com.hedera.hapi.node.base.ServiceEndpoint;
import java.util.List;
import org.hiero.consensus.model.node.KeysAndCerts;

public record SimpleAddress(long nodeId, long weight, List<ServiceEndpoint> serviceEndpoints, String memo, KeysAndCerts keysAndCerts) {
    public SimpleAddress(long nodeId, long weight) {
        this(nodeId, weight, List.of(), "", null);
    }

    public SimpleAddress withKeysAndCerts(KeysAndCerts keysAndCerts) {
        return new SimpleAddress(nodeId, weight, serviceEndpoints, memo, keysAndCerts);
    }
}
