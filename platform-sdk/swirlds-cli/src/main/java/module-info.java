// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.cli {
    exports com.swirlds.cli;
    exports com.swirlds.cli.commands;
    exports com.swirlds.cli.utility;
    exports com.swirlds.cli.logging;
    exports com.swirlds.cli.platform;

    requires com.swirlds.common;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.platform.core;
    requires org.hiero.consensus.model;
    requires info.picocli;
    requires io.github.classgraph;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;
}
