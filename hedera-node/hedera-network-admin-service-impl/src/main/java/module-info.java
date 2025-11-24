// SPDX-License-Identifier: Apache-2.0
import com.hedera.node.app.service.networkadmin.NetworkService;
import com.hedera.node.app.service.networkadmin.impl.FreezeServiceImpl;

module com.hedera.node.app.service.network.admin.impl {
    requires transitive com.hedera.node.app.hapi.fees;
    requires transitive com.hedera.node.app.service.addressbook;
    requires transitive com.hedera.node.app.service.entityid;
    requires transitive com.hedera.node.app.service.file;
    requires transitive com.hedera.node.app.service.network.admin;
    requires transitive com.hedera.node.app.service.token;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive dagger;
    requires transitive java.compiler; // javax.annotation.processing.Generated
    requires transitive javax.inject;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.node.config;
    requires com.swirlds.common;
    requires org.hiero.base.utility;
    requires com.google.common;
    requires org.apache.commons.io;
    requires org.apache.logging.log4j;
    requires static transitive com.github.spotbugs.annotations;

    provides com.hedera.node.app.service.networkadmin.FreezeService with
            FreezeServiceImpl;
    provides NetworkService with
            com.hedera.node.app.service.networkadmin.impl.NetworkServiceImpl;

    exports com.hedera.node.app.service.networkadmin.impl;
    exports com.hedera.node.app.service.networkadmin.impl.handlers;
    exports com.hedera.node.app.service.networkadmin.impl.schemas;
}
