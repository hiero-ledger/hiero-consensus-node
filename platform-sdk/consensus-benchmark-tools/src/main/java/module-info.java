// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.benchmark.tools {
    exports org.hiero.consensus.benchmark.tools.event;
    exports org.hiero.consensus.benchmark.tools.histogram;

    requires transitive com.hedera.pbj.runtime;
    requires transitive org.hiero.consensus.model;
    requires transitive jmh.core;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.utility;
    requires java.management;
    requires jdk.management;
    requires static transitive com.github.spotbugs.annotations;
}
