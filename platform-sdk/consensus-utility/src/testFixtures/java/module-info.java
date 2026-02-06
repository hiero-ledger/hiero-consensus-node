// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.utility.test.fixtures {
    exports org.hiero.consensus.test.fixtures;
    exports org.hiero.consensus.test.fixtures.crypto;

    requires transitive com.hedera.pbj.runtime;
    requires transitive org.hiero.base.crypto;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.utility;
    requires static transitive com.github.spotbugs.annotations;
}
