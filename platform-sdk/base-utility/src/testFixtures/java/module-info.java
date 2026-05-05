// SPDX-License-Identifier: Apache-2.0
open module org.hiero.base.utility.test.fixtures {
    exports org.hiero.base.utility.test.fixtures;
    exports org.hiero.base.utility.test.fixtures.file;
    exports org.hiero.base.utility.test.fixtures.io;
    exports org.hiero.base.utility.test.fixtures.tags;

    requires transitive org.hiero.base.utility;
    requires com.swirlds.base;
    requires org.junit.jupiter.api;
    requires static transitive com.github.spotbugs.annotations;
}
