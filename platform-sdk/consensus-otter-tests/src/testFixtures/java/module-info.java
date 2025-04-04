// SPDX-License-Identifier: Apache-2.0
module org.hiero.otter.fixtures {
    requires transitive com.swirlds.logging;
    requires com.hedera.pbj.runtime;
    requires org.hiero.consensus.model;
    requires static transitive com.github.spotbugs.annotations;

    exports org.hiero.otter.fixtures;
}
