// SPDX-License-Identifier: Apache-2.0
module com.swirlds.metrics.impl {
    exports com.swirlds.metrics.impl;

    requires transitive com.swirlds.metrics.api;
    requires com.swirlds.base;
    requires static transitive com.github.spotbugs.annotations;
}
