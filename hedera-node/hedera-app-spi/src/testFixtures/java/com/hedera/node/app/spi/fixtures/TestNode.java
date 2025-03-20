// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/** Holds information related to a node used in test {@link Scenarios} */
public record TestNode(
        long nodeNumber, @NonNull AccountID nodeAccountID, @NonNull Account account, @NonNull TestKeyInfo keyInfo) {
    /**
     * Returns a {@link NodeInfo} representation of this {@link TestNode}
     */
    public NodeInfo asInfo() {
        return new NodeInfo() {
            @Override
            public long nodeId() {
                return nodeNumber;
            }

            @Override
            public AccountID accountId() {
                return nodeAccountID;
            }

            @Override
            public long weight() {
                return 0;
            }

            @Override
            public Bytes sigCertBytes() {
                return Bytes.EMPTY;
            }

            @Override
            public List<ServiceEndpoint> gossipEndpoints() {
                return List.of();
            }

            @NonNull
            @Override
            public List<ServiceEndpoint> hapiEndpoints() {
                return List.of();
            }
        };
    }
}
