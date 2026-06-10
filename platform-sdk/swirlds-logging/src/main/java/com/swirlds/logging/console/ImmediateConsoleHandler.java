// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.console;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.handler.AbstractLogHandler;
import com.swirlds.logging.api.internal.format.FormattedLinePrinter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link com.swirlds.logging.api.extensions.handler.LogHandler} that writes each event to
 * {@link System#out} and flushes immediately.
 *
 *
 * <p>Selected via {@code logging.handler.<name>.type=console-immediate} in {@code log.properties}.
 * Registered as a {@link com.swirlds.logging.api.extensions.handler.LogHandlerFactory} via
 * {@link ImmediateConsoleHandlerFactory} (see {@code module-info.java}).
 */
public final class ImmediateConsoleHandler extends AbstractLogHandler {

    /** Type name used in {@code logging.handler.<name>.type}. */
    public static final String TYPE_NAME = "console-immediate";

    private final FormattedLinePrinter printer;
    private final PrintStream out = System.out;

    public ImmediateConsoleHandler(@NonNull final String handlerName, @NonNull final Configuration configuration) {
        super(handlerName, configuration);
        this.printer = FormattedLinePrinter.createForHandler(handlerName, configuration);
    }

    @Override
    public void handle(@NonNull final LogEvent event) {
        final StringBuilder sb = new StringBuilder(256);
        printer.print(sb, event);
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8), 0, sb.length());
        out.flush();
    }

    @Override
    public void stopAndFinalize() {
        out.flush();
    }
}
