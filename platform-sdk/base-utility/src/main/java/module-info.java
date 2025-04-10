// SPDX-License-Identifier: Apache-2.0
module org.hiero.base.utility {
    exports org.hiero.base.utility;
    exports org.hiero.base.utility.exceptions;
    exports org.hiero.base.iterator;

    requires transitive com.swirlds.logging;
    requires com.swirlds.base;
    requires static transitive com.github.spotbugs.annotations;
}
