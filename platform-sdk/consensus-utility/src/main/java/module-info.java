// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.utility {
    exports org.hiero.consensus.utility;
    exports org.hiero.consensus.utility.exceptions;

    requires transitive com.swirlds.logging;
    requires com.swirlds.base;
    requires static transitive com.github.spotbugs.annotations;
}
