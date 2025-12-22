// SPDX-License-Identifier: Apache-2.0
module com.swirlds.cli {
    opens com.swirlds.cli to
            info.picocli;
    opens com.swirlds.cli.utility to
            info.picocli;
    opens com.swirlds.cli.commands to
            info.picocli;
    opens com.swirlds.cli.logging to
            info.picocli;

    exports com.swirlds.cli.utility;

    requires com.swirlds.base;
    requires com.swirlds.common;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.platform.core;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl;
    requires org.hiero.consensus.model;
    requires info.picocli;
    requires io.github.classgraph;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;
}
