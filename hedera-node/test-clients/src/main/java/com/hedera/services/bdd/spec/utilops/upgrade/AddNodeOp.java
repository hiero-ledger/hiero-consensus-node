// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;

/**
 * Adds the node with "classic" metadata implied by the given node id and refreshes the {@link SubProcessNetwork} roster.
 */
public class AddNodeOp extends UtilOp {
    private final long nodeId;

    public AddNodeOp(final long nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork)) {
            throw new IllegalStateException("Can only add nodes to a SubProcessNetwork");
        }
        subProcessNetwork.addNode(nodeId);
        return false;
    }
}
