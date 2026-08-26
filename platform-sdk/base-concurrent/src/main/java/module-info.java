// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.base.concurrent.config.ConcurrentConfigurationExtension;

// SPDX-License-Identifier: Apache-2.0
module org.hiero.base.concurrent {
    exports org.hiero.base.concurrent.atomic;
    exports org.hiero.base.concurrent.config;
    exports org.hiero.base.concurrent.framework.config;
    exports org.hiero.base.concurrent.framework.queue;
    exports org.hiero.base.concurrent.framework;
    exports org.hiero.base.concurrent.futures;
    exports org.hiero.base.concurrent.interrupt;
    exports org.hiero.base.concurrent.locks.locked;
    exports org.hiero.base.concurrent.locks;
    exports org.hiero.base.concurrent.manager;
    exports org.hiero.base.concurrent.pool;
    exports org.hiero.base.concurrent.throttle;
    exports org.hiero.base.concurrent;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.utility;
    requires transitive org.apache.logging.log4j;
    requires com.swirlds.logging;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            ConcurrentConfigurationExtension;
}
