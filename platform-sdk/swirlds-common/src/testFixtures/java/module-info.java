// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.common.test.fixtures {
    exports com.swirlds.common.test.fixtures;
    exports com.swirlds.common.test.fixtures.map;
    exports com.swirlds.common.test.fixtures.set;

    requires transitive com.swirlds.common;
    requires org.hiero.base.utility;
    requires org.junit.jupiter.api;
    requires static transitive com.github.spotbugs.annotations;
}
