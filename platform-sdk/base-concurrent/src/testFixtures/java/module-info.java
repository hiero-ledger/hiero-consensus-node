// SPDX-License-Identifier: Apache-2.0
open module org.hiero.base.concurrent.test.fixtures {
    exports org.hiero.base.concurrent.test.fixtures;

    requires transitive org.hiero.base.concurrent;
    requires com.swirlds.common.test.fixtures;
    requires static transitive com.github.spotbugs.annotations;
}
