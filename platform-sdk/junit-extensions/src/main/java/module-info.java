// SPDX-License-Identifier: Apache-2.0
open module org.hiero.junit.extensions {
    exports org.hiero.junit.extensions;

    requires transitive org.junit.jupiter.api;
    requires com.google.common;
    requires static transitive com.github.spotbugs.annotations;
}
