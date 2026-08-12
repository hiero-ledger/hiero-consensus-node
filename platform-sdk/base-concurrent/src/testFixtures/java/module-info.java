// SPDX-License-Identifier: Apache-2.0
open module org.hiero.base.concurrent.test.fixtures {
    exports org.hiero.base.concurrent.test.fixtures;
    exports org.hiero.base.concurrent.test.fixtures.assertions;
    exports org.hiero.base.concurrent.test.fixtures.threading;

    requires transitive org.hiero.base.concurrent;
    requires org.hiero.base.utility.test.fixtures;
    requires static transitive com.github.spotbugs.annotations;
    requires org.junit.jupiter.api;
}
