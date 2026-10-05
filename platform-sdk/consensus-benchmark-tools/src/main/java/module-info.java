// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.benchmark.tools {
    exports org.hiero.consensus.benchmark.tools.histogram;

    requires transitive jmh.core;
    requires java.management;
    requires jdk.management;
    requires static transitive com.github.spotbugs.annotations;
}
