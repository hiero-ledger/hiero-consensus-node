// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.pces.impl.test.fixtures {
    exports org.hiero.consensus.pces.impl.test.fixtures;

    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires org.hiero.consensus.pces.impl;
    requires static transitive com.github.spotbugs.annotations;
}
