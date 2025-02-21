// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.benchmark.log4j2.plain;

import com.swirlds.logging.benchmark.log4j2.Log4JBaseConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

public class Log4JConfig extends Log4JBaseConfig {
    @NonNull
    protected AppenderComponentBuilder createFileAppender(
            final @NonNull ConfigurationBuilder<BuiltConfiguration> builder, final @NonNull String path) {
        final LayoutComponentBuilder layoutBuilder =
                builder.newLayout("PatternLayout").addAttribute("pattern", PATTERN);
        return builder.newAppender(Log4JBaseConfig.FILE_APPENDER_NAME, "File")
                .addAttribute("fileName", path)
                .addAttribute("append", true)
                .add(layoutBuilder);
    }
}
