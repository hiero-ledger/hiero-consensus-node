// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gossip.impl {
    requires transitive org.hiero.consensus.gossip;

    provides org.hiero.consensus.gossip.Gossip with
            org.hiero.consensus.gossip.impl.GossipImpl;
}
