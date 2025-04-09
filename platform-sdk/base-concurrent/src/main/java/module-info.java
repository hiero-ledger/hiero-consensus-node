// SPDX-License-Identifier: Apache-2.0
module org.hiero.base.concurrent {
    exports org.hiero.base.concurrent;
    exports org.hiero.base.concurrent.atomic;
    exports org.hiero.base.concurrent.futures;
    exports org.hiero.base.concurrent.interrupt;
    exports org.hiero.base.concurrent.locks;
    exports org.hiero.base.concurrent.locks.locked;

    requires transitive com.swirlds.base;
    requires com.swirlds.logging;
    requires org.hiero.base.utility;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
