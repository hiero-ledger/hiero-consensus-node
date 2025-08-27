// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.yahcli {
    exports com.hedera.services.yahcli;

    opens com.hedera.services.yahcli to
            info.picocli;
    opens com.hedera.services.yahcli.commands.keys to
            info.picocli;
    opens com.hedera.services.yahcli.commands.accounts to
            info.picocli;
    opens com.hedera.services.yahcli.commands.fees to
            info.picocli;
    opens com.hedera.services.yahcli.commands.files to
            info.picocli;
    opens com.hedera.services.yahcli.commands.system to
            info.picocli;
    opens com.hedera.services.yahcli.commands.schedules to
            info.picocli;
    opens com.hedera.services.yahcli.commands.nodes to
            info.picocli;

    exports com.hedera.services.yahcli.config.domain;
    exports com.hedera.services.yahcli.config;
    exports com.hedera.services.yahcli.output;

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
