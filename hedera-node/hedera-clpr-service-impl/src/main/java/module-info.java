// SPDX-License-Identifier: Apache-2.0
import com.hedera.node.app.service.clpr.impl.ClprServiceImpl;

/**
 * Module that provides the implementation of the Hedera CLPR Service.
 */
module com.hedera.node.app.service.clpr.impl {
    exports com.hedera.node.app.service.clpr.impl.calculator;
    exports com.hedera.node.app.service.clpr.impl.handlers;
    exports com.hedera.node.app.service.clpr.impl.roster;
    exports com.hedera.node.app.service.clpr.impl.schemas;
    exports com.hedera.node.app.service.clpr.impl.verifier.ethereum;
    exports com.hedera.node.app.service.clpr.impl.verifier.sei;
    exports com.hedera.node.app.service.clpr.impl.verifier;
    exports com.hedera.node.app.service.clpr.impl;

    requires transitive com.hedera.node.app.hapi.fees;
    requires transitive com.hedera.node.app.hapi.utils;
    requires transitive com.hedera.node.app.service.addressbook;
    requires transitive com.hedera.node.app.service.clpr;
    requires transitive com.hedera.node.app.service.entityid;
    requires transitive com.hedera.node.app.service.token;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.state.api;
    requires transitive com.esaulpaugh.headlong;
    requires transitive dagger;
    requires transitive java.compiler; // javax.annotation.processing.Generated
    requires transitive javax.inject;
    requires com.hedera.node.app.service.contract;
    requires org.hiero.base.crypto;
    requires org.hiero.consensus.roster;
    requires com.sun.jna;
    requires org.apache.logging.log4j;
    requires org.bouncycastle.provider;
    requires org.hyperledger.besu.nativelib.secp256k1;
    requires org.slf4j;
    requires static transitive com.github.spotbugs.annotations;

    provides com.hedera.node.app.service.clpr.ClprService with
            ClprServiceImpl;
}
