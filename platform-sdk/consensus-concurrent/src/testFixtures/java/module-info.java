// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.concurrent.test.fixtures {
    exports org.hiero.consensus.concurrent.test.fixtures.assertions;
    exports org.hiero.consensus.concurrent.test.fixtures.threading;

    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.consensus.concurrent;
    requires org.junit.jupiter.api;
    requires static transitive com.github.spotbugs.annotations;
}
