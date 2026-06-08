// SPDX-License-Identifier: Apache-2.0
module com.swirlds.common {

    /* Exported packages. This list should remain alphabetized. */
    exports com.swirlds.common.context;
    exports com.swirlds.common.io.exceptions;
    exports com.swirlds.common.notification;
    exports com.swirlds.common.platform;
    exports com.swirlds.common.utility;
    exports com.swirlds.common.startup;

    opens com.swirlds.common.utility to
            com.fasterxml.jackson.databind;

    requires transitive com.swirlds.base;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive org.hiero.base.concurrent;
    requires transitive org.hiero.base.utility;
    requires transitive org.hiero.consensus.concurrent;
    requires transitive org.hiero.consensus.model;
    requires transitive org.hiero.consensus.utility;
    requires com.swirlds.logging;
    requires org.hiero.consensus.metrics;
    requires jdk.httpserver;
    requires jdk.management;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;
}
