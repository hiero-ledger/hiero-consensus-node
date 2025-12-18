// SPDX-License-Identifier: Apache-2.0
module org.hiero.consensus.concurrent {
    exports org.hiero.consensus.concurrent.config;
    exports org.hiero.consensus.concurrent.framework;
    exports org.hiero.consensus.concurrent.framework.config;
    exports org.hiero.consensus.concurrent.framework.internal;
    exports org.hiero.consensus.concurrent.manager;
    exports org.hiero.consensus.concurrent.pool;
    exports org.hiero.consensus.concurrent.utility.throttle;

    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires com.swirlds.base;
    requires com.swirlds.logging;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
