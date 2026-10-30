// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.service.addressbook {
    exports com.hedera.node.app.service.addressbook;

    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires com.hedera.node.config;
    requires com.swirlds.config.api;
    requires com.swirlds.state.api;
    requires org.hiero.base.crypto;
    requires static transitive com.github.spotbugs.annotations;

    uses com.hedera.node.app.service.addressbook.AddressBookService;
}
