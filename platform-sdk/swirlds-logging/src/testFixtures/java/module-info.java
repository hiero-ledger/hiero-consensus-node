// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.logging.test.fixtures {
    exports com.swirlds.logging.test.fixtures;
    exports com.swirlds.logging.test.fixtures.util;
    exports com.swirlds.logging.test.fixtures.internal to
            com.swirlds.logging; // for testing the fixture itself

    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.config.extensions.test.fixtures;
    requires transitive com.swirlds.logging;
    requires transitive org.junit.jupiter.api;
    requires com.swirlds.base.test.fixtures;
    requires static transitive com.github.spotbugs.annotations;
}
