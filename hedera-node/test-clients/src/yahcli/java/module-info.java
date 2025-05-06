// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.test.clients.yahcli {
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.node.app.service.addressbook;
    requires com.hedera.node.app;
    requires com.hedera.node.hapi;
    requires com.hedera.node.test.clients;
    requires org.hiero.base.utility;
    requires com.github.spotbugs.annotations;
    requires com.google.common;
    requires com.google.protobuf;
    requires info.picocli;
    requires net.i2p.crypto.eddsa;
    requires org.apache.logging.log4j;
    requires org.junit.jupiter.api;
    requires org.yaml.snakeyaml;
}
