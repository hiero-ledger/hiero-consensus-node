// SPDX-License-Identifier: Apache-2.0
module com.swirlds.state.api {
    exports com.swirlds.state;
    exports com.swirlds.state.spi;
    exports com.swirlds.state.lifecycle.info;
    exports com.swirlds.state.spi.metrics;
    exports com.swirlds.state.lifecycle;

    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.crypto;
    requires transitive org.hiero.base.utility;
    requires com.swirlds.logging;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
