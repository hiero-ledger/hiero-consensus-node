// SPDX-License-Identifier: Apache-2.0
/**
 * Provides the classes necessary to manage the Hedera CLPR Service.
 */
module com.hedera.node.app.service.clpr {
    exports com.hedera.node.app.service.clpr;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires com.swirlds.state.api;
    requires static transitive com.github.spotbugs.annotations;

    uses com.hedera.node.app.service.clpr.ClprService;
}
