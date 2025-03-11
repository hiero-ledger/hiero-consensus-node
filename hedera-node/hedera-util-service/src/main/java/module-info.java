// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.service.util {
    exports com.hedera.node.app.service.util;

    uses com.hedera.node.app.service.util.UtilService;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.api;
    requires com.hedera.node.hapi;
    requires static transitive com.github.spotbugs.annotations;
}
