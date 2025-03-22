// SPDX-License-Identifier: Apache-2.0
module com.swirlds.cli {
    exports com.swirlds.cli;
    exports com.swirlds.cli.commands;
    exports com.swirlds.cli.utility;
    exports com.swirlds.cli.logging;

    opens com.swirlds.cli to
            info.picocli;
    opens com.swirlds.cli.utility to
            info.picocli;
    opens com.swirlds.cli.commands to
            info.picocli;
    opens com.swirlds.cli.logging to
            info.picocli;

    requires transitive com.swirlds.common;
    requires transitive org.hiero.consensus.model;
    requires transitive info.picocli;
    requires transitive org.apache.logging.log4j;
    requires com.swirlds.logging;
    requires io.github.classgraph;
    requires static transitive com.github.spotbugs.annotations;
}
