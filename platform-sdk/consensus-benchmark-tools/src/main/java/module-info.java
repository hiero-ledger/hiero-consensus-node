// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.benchmark.tools {
    exports org.hiero.consensus.benchmark.tools.event;
    exports org.hiero.consensus.benchmark.tools.histogram;

    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.consensus.model;
    requires transitive jmh.core;
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires org.hiero.consensus.fakes;
    requires org.hiero.consensus.utility;
    requires java.management;
    requires jdk.management;
    requires static transitive com.github.spotbugs.annotations;
}
