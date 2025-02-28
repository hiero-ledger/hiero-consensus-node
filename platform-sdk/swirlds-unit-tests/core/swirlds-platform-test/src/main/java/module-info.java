// SPDX-License-Identifier: Apache-2.0
module com.swirlds.platform.test {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.base;
    requires transitive com.swirlds.common.test.fixtures;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.component.framework;
    requires transitive com.swirlds.platform.core.test.fixtures;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions.test.fixtures;
    requires java.desktop;
    requires org.junit.jupiter.api;
    requires static transitive com.github.spotbugs.annotations;
}
