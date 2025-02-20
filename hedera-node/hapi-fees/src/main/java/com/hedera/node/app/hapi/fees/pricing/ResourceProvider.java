// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

/**
 * Represents the three resources providers a transaction may consume resources from, and hence owe
 * fees to.
 *
 * <p>The {@link ResourceProvider#relativeWeight()} method returns the size of the network, since
 * the network and service providers are essentially consuming resources from every node. (Unlike
 * the node- specific work done in answering a query.)
 */
public enum ResourceProvider {
    /** A single node in the network. */
    NODE {
        @Override
        public String jsonKey() {
            return "nodedata";
        }

        @Override
        public int relativeWeight() {
            return 1;
        }
    },
    /** The gossip and consensus provisions of the entire network. */
    NETWORK {
        @Override
        public String jsonKey() {
            return "networkdata";
        }

        @Override
        public int relativeWeight() {
            return NETWORK_SIZE;
        }
    },
    /** The provisions of the entire network for a specific service such as HTS. */
    SERVICE {
        @Override
        public String jsonKey() {
            return "servicedata";
        }

        @Override
        public int relativeWeight() {
            return NETWORK_SIZE;
        }
    };

    private static final int RELEASE_0160_NETWORK_SIZE = 20;
    private static final int NETWORK_SIZE = RELEASE_0160_NETWORK_SIZE;

    public abstract int relativeWeight();

    public abstract String jsonKey();
}
