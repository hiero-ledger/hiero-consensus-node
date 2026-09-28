// SPDX-License-Identifier: Apache-2.0
/**
 * Provides the classes necessary to manage Hedera Smart Contract Service.
 */
module com.hedera.node.app.service.contract {
    exports com.hedera.node.app.service.contract.api;
    exports com.hedera.node.app.service.contract;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires static transitive com.github.spotbugs.annotations;

    uses com.hedera.node.app.service.contract.ContractService;
}
