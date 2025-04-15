// SPDX-License-Identifier: Apache-2.0
open module org.hiero.base.crypto.test.fixtures {
    exports org.hiero.base.crypto.test.fixtures;

    requires transitive org.hiero.base.crypto;
    requires org.hiero.base.utility.test.fixtures;
    requires static transitive com.github.spotbugs.annotations;
}
