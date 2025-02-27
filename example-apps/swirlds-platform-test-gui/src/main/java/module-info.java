// SPDX-License-Identifier: Apache-2.0
module com.swirlds.platform.test.gui {
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires com.hedera.node.hapi;
    requires com.swirlds.common.test.fixtures;
    requires com.swirlds.config.api;
    requires com.swirlds.platform.core.test.fixtures;
    requires java.desktop;
    requires static transitive com.github.spotbugs.annotations;
}
