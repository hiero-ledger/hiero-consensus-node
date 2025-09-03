// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.cli {
    exports com.swirlds.cli;
    exports com.swirlds.cli.commands;
    exports com.swirlds.cli.utility;
    exports com.swirlds.cli.logging;
    exports com.swirlds.cli.platform;

    requires  com.swirlds.common;
    requires  org.hiero.consensus.model;
    requires info.picocli;
    requires org.apache.logging.log4j;
    requires com.swirlds.logging;
    requires io.github.classgraph;
    requires static com.github.spotbugs.annotations;
    requires com.swirlds.platform.core;
}
