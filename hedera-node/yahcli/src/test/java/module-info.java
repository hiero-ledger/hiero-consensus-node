// SPDX-License-Identifier: Apache-2.0
import com.hedera.services.yahcli.test.YahcliLauncherSessionListener;
import org.junit.platform.launcher.LauncherSessionListener;

module hiero.consensus.node.yahcli.test {
    requires com.hedera.common.nativesupport;
    requires com.hedera.node.app.hapi.utils;
    requires com.hedera.node.hapi;
    requires com.hedera.node.test.clients;
    requires com.hedera.node.yahcli;
    requires com.swirlds.config.api;
    requires info.picocli;
    requires io.github.classgraph;
    requires io.helidon.common.buffers;
    requires io.helidon.common.resumable;
    requires io.helidon.common;
    requires jakarta.inject;
    requires javax.inject;
    requires org.apache.logging.log4j;
    requires org.assertj.core;
    requires org.eclipse.collections.api;
    requires org.jspecify;
    requires org.junit.jupiter.api;
    requires org.junit.jupiter.params;
    requires org.junit.platform.launcher;
    requires org.yaml.snakeyaml;
    requires simpleclient;

    provides LauncherSessionListener with
            YahcliLauncherSessionListener;

    opens com.hedera.services.yahcli.test to
            org.junit.platform.commons;
    opens com.hedera.services.yahcli.test.scenarios to
            org.junit.platform.commons;
}
