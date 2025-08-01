// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.yahcli.test {
    requires com.hedera.node.yahcli;
    requires org.junit.jupiter.params;
    requires static com.hedera.node.hapi;
    requires static com.hedera.node.test.clients;
    requires static com.google.protobuf;
    requires static info.picocli;
    requires static org.assertj.core;

    // For test visibility
    opens com.hedera.services.yahcli.test to
            org.junit.platform.commons,
            org.junit.jupiter.api,
            org.assertj.core;
    opens com.hedera.services.yahcli.test.config to
            org.junit.platform.commons;
}
