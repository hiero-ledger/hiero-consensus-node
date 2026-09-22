// SPDX-License-Identifier: Apache-2.0
open module org.hiero.consensus.utility.test.fixtures {
    exports org.hiero.consensus.test.fixtures;
    exports org.hiero.consensus.test.fixtures.crypto;
    exports org.hiero.consensus.test.fixtures.io;
    exports org.hiero.consensus.test.fixtures.scratchpad;

    requires transitive com.hedera.pbj.runtime;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.utility;
    requires org.hiero.base.utility.test.fixtures;
    requires org.hiero.consensus.model;
    requires static transitive com.github.spotbugs.annotations;
}
