// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.gossip {
    exports org.hiero.consensus.gossip;

    requires transitive org.hiero.consensus.model;
    requires static transitive com.github.spotbugs.annotations;
}
