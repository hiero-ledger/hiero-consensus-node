// SPDX-License-Identifier: Apache-2.0
module org.hiero.base.utility {
    exports org.hiero.base;
    exports org.hiero.base.constructable;
    exports org.hiero.base.exceptions;
    exports org.hiero.base.io;
    exports org.hiero.base.io.exceptions;
    exports org.hiero.base.io.streams;
    exports org.hiero.base.iterator;
    exports org.hiero.base.utility;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.logging;
    requires io.github.classgraph;
    requires jdk.unsupported;
    requires static transitive com.github.spotbugs.annotations;
}
