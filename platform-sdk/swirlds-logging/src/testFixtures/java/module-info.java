// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.logging.test.fixtures {
    exports com.swirlds.logging.test.fixtures;

    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.logging;
    requires transitive org.junit.jupiter.api;
    requires com.swirlds.base.test.fixtures;
    requires com.swirlds.config.extensions.test.fixtures;
    requires static transitive com.github.spotbugs.annotations;
}
