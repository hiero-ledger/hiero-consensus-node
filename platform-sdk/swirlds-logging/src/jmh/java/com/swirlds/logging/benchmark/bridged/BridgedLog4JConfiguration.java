// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.bridged;

import com.swirlds.logging.benchmark.log4j2.plain.Log4JConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.config.plugins.util.PluginManager;
import org.apache.logging.log4j.spi.LoggerContext;

public class BridgedLog4JConfiguration extends Log4JConfig {
    public static final String BRIDGE_APPENDER_NAME = "SwirldsAppender";

    @NonNull
    public LoggerContext configureBridgedLogging() {
        System.clearProperty("log4j2.contextSelector");
        PluginManager.addPackage("com.swirlds.logging.log4j.appender");
        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.DEBUG);
        builder.setConfigurationName("bridgeLoggingConfig");
        final AppenderComponentBuilder appenderBuilder =
                builder.newAppender(BRIDGE_APPENDER_NAME, "SwirldsLoggingAppender");
        builder.add(appenderBuilder);
        builder.add(builder.newRootLogger(Level.ALL).add(builder.newAppenderRef(BRIDGE_APPENDER_NAME)));
        return create(builder);
    }
}
