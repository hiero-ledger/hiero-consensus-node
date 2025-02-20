// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.log4j2.rolling;

import com.swirlds.logging.benchmark.log4j2.Log4JBaseConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

/**
 * Convenience methods for configuring log4j logger
 */
public class Log4JRollingConfig extends Log4JBaseConfig {

    @NonNull
    protected AppenderComponentBuilder createFileAppender(
            final @NonNull ConfigurationBuilder<BuiltConfiguration> builder, final @NonNull String path) {
        final LayoutComponentBuilder layoutBuilder =
                builder.newLayout("PatternLayout").addAttribute("pattern", PATTERN);

        return builder.newAppender(FILE_APPENDER_NAME, "RollingFile")
                .addAttribute("fileName", path)
                .addAttribute("filePattern", path.replace(".log", "") + ".%i.log")
                .addAttribute("append", true)
                .add(layoutBuilder)
                .addComponent(builder.newComponent("SizeBasedTriggeringPolicy"))
                .addComponent(builder.newComponent("DefaultRolloverStrategy").addAttribute("max", 1))
                .addAttribute("size", "30MB");
    }
}
