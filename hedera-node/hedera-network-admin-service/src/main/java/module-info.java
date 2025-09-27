// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.service.network.admin {
    exports com.hedera.node.app.service.networkadmin;

    uses com.hedera.node.app.service.networkadmin.FreezeService;
    uses com.hedera.node.app.service.networkadmin.NetworkService;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires com.hedera.node.app.hapi.utils;
    requires com.swirlds.state.api;
    requires static transitive com.github.spotbugs.annotations;
}
