// SPDX-License-Identifier: Apache-2.0
open module org.hiero.base.utility.test.fixtures {
    exports org.hiero.base.utility.test.fixtures;
    exports org.hiero.base.utility.test.fixtures.io;
    exports org.hiero.base.utility.test.fixtures.tags;

    requires transitive org.hiero.base.utility;
    requires static transitive com.github.spotbugs.annotations;
}
