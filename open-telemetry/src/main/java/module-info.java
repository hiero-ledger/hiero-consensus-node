// SPDX-License-Identifier: Apache-2.0
module org.hiero.telemetryconverter {
    exports org.hiero.telemetryconverter.config to
            com.swirlds.config.impl,
            com.swirlds.config.extensions;

    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires org.eclipse.collections.api;
    requires org.eclipse.collections.impl;
    requires jdk.jfr;
    requires com.hedera.pbj.grpc.client.helidon;
    requires io.helidon.common.tls;
    requires io.helidon.webclient.api;
    requires java.logging;
    requires com.swirlds.config.api;
    requires com.swirlds.config.extensions;

    provides com.swirlds.config.api.ConfigurationExtension with
            org.hiero.telemetryconverter.config.TracingConfigurationExtension;
}
