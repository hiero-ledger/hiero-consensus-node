// SPDX-License-Identifier: Apache-2.0
open module com.swirlds.state.api.test.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.api;
    requires transitive org.junit.jupiter.params;
    requires org.mockito;
    requires static transitive com.github.spotbugs.annotations;

    exports com.swirlds.state.test.fixtures;
}
