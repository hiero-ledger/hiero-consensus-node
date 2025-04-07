// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.service.addressbook.impl {
    requires transitive com.hedera.node.app.service.addressbook;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.state.api;
    requires transitive dagger;
    requires transitive javax.inject;
    requires transitive org.apache.logging.log4j;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.node.app.service.token;
    requires com.swirlds.config.api;
    requires org.hiero.consensus.model;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires static transitive com.github.spotbugs.annotations;
    requires static transitive java.compiler;

    provides com.hedera.node.app.service.addressbook.AddressBookService with
            com.hedera.node.app.service.addressbook.impl.AddressBookServiceImpl;

    exports com.hedera.node.app.service.addressbook.impl;
    exports com.hedera.node.app.service.addressbook.impl.handlers;
    exports com.hedera.node.app.service.addressbook.impl.records;
    exports com.hedera.node.app.service.addressbook.impl.validators;
    exports com.hedera.node.app.service.addressbook.impl.schemas;
}
