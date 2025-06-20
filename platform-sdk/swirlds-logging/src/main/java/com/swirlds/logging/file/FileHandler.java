// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.file;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.handler.AbstractLogHandler;
import com.swirlds.logging.api.internal.format.FormattedLinePrinter;
import com.swirlds.logging.io.OutputStreamFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * A {@link com.swirlds.logging.api.extensions.handler.LogHandler} that writes log events to a file with optional rolling based on size.
 * <p>
 * The rolling behavior of the underlying file is determined by the provided configuration. When enabled, rolling occurs
 * based on size. To enable it at least {@code file-rolling.maxFileSize} property
 * needs to be informed.
 * <p>
 * Rolling is implemented with best effort strategy. Log events are initially written to the file, and rolling occurs
 * if the file size exceeds the configured limit. This approach maintains data coherence and avoids the performance
 * penalties associated with handling files in a highly specific manner. However, it may result in occasional file sizes
 * exceeding the limit, depending on the volume of data being written.
 * <p>
 * The handler can be optionally buffered for improved performance.
 * <p>
 * The handler can be configured with the following properties:
 * <ul>
 *     <li>{@code file} - The {@link Path} of the log file.</li>
 *     <li>{@code append} - Determines whether to append to an existing file or overwrite it.</li>
 *     <li>{@code formatTimestamp} - If set to true, epoch values are formatted as human-readable strings.</li>
 *     <li>{@code file-rolling.maxFileSize} - Maximum size of the file for size-based rolling.</li>
 *     <li>{@code file-rolling.maxFiles} - Maximum number of files used for rolling.</li>
 * </ul>
 */
public class FileHandler extends AbstractLogHandler {

    private static final int EVENT_LOG_PRINTER_SIZE = 4 * 1024;
    private final OutputStream outputStream;
    private final FormattedLinePrinter format;

    /**
     * Creates a new file handler.
     *
     * @param handlerName   the unique handler name
     * @param configuration the configuration
     * @param buffered      if true a buffer is used in between the file writing
     */
    public FileHandler(
            @NonNull final String handlerName, @NonNull final Configuration configuration, final boolean buffered)
            throws IOException {
        super(handlerName, configuration);

        this.format = FormattedLinePrinter.createForHandler(handlerName, configuration);
        try {
            this.outputStream = buffered
                    ? OutputStreamFactory.getInstance().bufferedOutputStream(configuration, handlerName)
                    : OutputStreamFactory.getInstance().outputStream(configuration, handlerName);
        } catch (IOException e) {
            throw new IOException("Could not create FileHandler", e);
        }
    }

    /**
     * Handles a log event by appending it to the file using the {@link FormattedLinePrinter}.
     *
     * @param event The log event to be printed.
     */
    @Override
    public void handle(@NonNull final LogEvent event) {
        final StringBuilder writer = new StringBuilder(EVENT_LOG_PRINTER_SIZE);
        format.print(writer, event);
        try {
            this.outputStream.write(writer.toString().getBytes(StandardCharsets.UTF_8));
        } catch (final Exception exception) {
            EMERGENCY_LOGGER.log(Level.ERROR, "Failed to write to file output stream", exception);
            // FORWARDING the event to the emergency logger
            EMERGENCY_LOGGER.log(event);
        }
    }

    /**
     * All content for this handler that has been buffered will be written to destination.
     */
    @Override
    public void flush() {
        try {
            this.outputStream.flush();
        } catch (IOException e) {
            EMERGENCY_LOGGER.log(Level.WARN, "Failed to flush to file output stream " + this.getName(), e);
        }
    }

    /**
     * Stops the handler and no further events are processed
     */
    @Override
    public void stopAndFinalize() {
        super.stopAndFinalize();
        try {
            outputStream.close();
        } catch (final Exception exception) {
            EMERGENCY_LOGGER.log(Level.ERROR, "Failed to close file output stream", exception);
        }
    }
}
