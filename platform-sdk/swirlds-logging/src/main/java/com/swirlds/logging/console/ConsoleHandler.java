// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.console;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.handler.AbstractLogHandler;
import com.swirlds.logging.api.internal.format.FormattedLinePrinter;
import com.swirlds.logging.io.BufferedOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
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

    private static final int BUFFER_CAPACITY = 8192;
    private final FormattedLinePrinter format;
    private final OutputStream outputStream;

    /**
     * Constructs a new ConsoleHandler with the specified configuration.
     *
     * @param handlerName   The unique name of this handler.
     * @param configuration The configuration for this handler.
     */
    public ConsoleHandler(
            @NonNull final String handlerName, @NonNull final Configuration configuration, final boolean buffered) {
        super(handlerName, configuration);
        this.format = FormattedLinePrinter.createForHandler(handlerName, configuration);
        this.outputStream = buffered ? new BufferedOutputStream(System.out, BUFFER_CAPACITY) : System.out;
    }

    /**
     * Handles a log event by printing it to the console using the {@link FormattedLinePrinter}. May be buffered and not
     * immediately flushed.
     *
     * @param event The log event to be printed.
     */
    @Override
    public void handle(@NonNull final LogEvent event) {
        StringBuilder builder = new StringBuilder();
        format.print(builder, event);
        try {
            outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) { // Should not happen
            EMERGENCY_LOGGER.log(Level.ERROR, "Failed to write to console", exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopAndFinalize() {
        try {
            outputStream.flush();
        } catch (IOException exception) { // Should Not happen
            EMERGENCY_LOGGER.log(Level.ERROR, "Failed to close file output stream", exception);
        }
    }
}
