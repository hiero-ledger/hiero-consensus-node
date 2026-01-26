// SPDX-License-Identifier: Apache-2.0
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.impl.GossipModuleImpl;

// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gossip.impl {
    requires transitive com.swirlds.component.framework;
    requires transitive org.hiero.consensus.gossip;
    requires transitive org.hiero.consensus.model;

    provides GossipModule with
            GossipModuleImpl;
}
