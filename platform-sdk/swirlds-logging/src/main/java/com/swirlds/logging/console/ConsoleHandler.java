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
 * A handler that logs events to the console.
 * <p>
 * This class extends the {@link AbstractLogHandler} and provides a simple way to log {@link LogEvent}s to the
 * console using a {@link FormattedLinePrinter}.
 *
 * @see AbstractLogHandler
 * @see FormattedLinePrinter
 */
public class ConsoleHandler extends AbstractLogHandler {

    private final FormattedLinePrinter printer;
    private final PrintStream out = System.out;

    public ConsoleHandler(@NonNull final String handlerName, @NonNull final Configuration configuration) {
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
