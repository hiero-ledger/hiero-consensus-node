// SPDX-License-Identifier: Apache-2.0
import com.swirlds.config.api.ConfigurationExtension;
import org.hiero.consensus.concurrent.config.ConcurrentConfigurationExtension;

module org.hiero.consensus.concurrent {
    exports org.hiero.consensus.concurrent.config;
    exports org.hiero.consensus.concurrent.framework;
    exports org.hiero.consensus.concurrent.framework.config;
    exports org.hiero.consensus.concurrent.framework.internal;
    exports org.hiero.consensus.concurrent.manager;
    exports org.hiero.consensus.concurrent.pool;
    exports org.hiero.consensus.concurrent.utility.throttle;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.model;
    requires transitive org.apache.logging.log4j;
    requires com.swirlds.logging;
    requires static transitive com.github.spotbugs.annotations;

    provides ConfigurationExtension with
            ConcurrentConfigurationExtension;
}
