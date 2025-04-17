// SPDX-License-Identifier: Apache-2.0
open module org.hiero.base.crypto.test.fixtures {
    exports org.hiero.base.crypto.test.fixtures;

    requires transitive com.hedera.pbj.runtime;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires com.swirlds.logging;
    requires org.hiero.base.utility.test.fixtures;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
