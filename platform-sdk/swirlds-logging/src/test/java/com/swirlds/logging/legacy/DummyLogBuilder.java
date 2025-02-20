// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

import com.swirlds.logging.legacy.json.JsonLogEntry;
import java.io.FileNotFoundException;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import org.apache.logging.log4j.Marker;

/**
 * Utility for simulating a SwirldsLogReader.
 */
public class DummyLogBuilder {

    private final List<JsonLogEntry> entries;

    private Instant previousTimestamp;
    private Instant timestamp;

    private String thread;
    private String loggerName;

    public DummyLogBuilder() {
        entries = new LinkedList<>();
        timestamp = Instant.now();
        thread = "unspecified";
        loggerName = "unspecified";
        previousTimestamp = timestamp.minusNanos(1);
    }

    /**
     * Get the name of simulated thread that is adding to the dummy log.
     */
    public String getThread() {
        return thread;
    }

    /**
     * Set the name of simulated thread that is adding to the dummy log.
     */
    public DummyLogBuilder setThread(String thread) {
        this.thread = thread;
        return this;
    }

    /**
     * Get the name of simulated logger that is adding to the dummy log.
     */
    public String getLoggerName() {
        return loggerName;
    }

    /**
     * Set the name of simulated logger that is adding to the dummy log.
     */
    public void setLoggerName(String loggerName) {
        this.loggerName = loggerName;
    }

    public DummyLogBuilder debug(final Marker marker, final String message, final Object... data) {
        log(marker, "DEBUG", message, data);
        return this;
    }

    public DummyLogBuilder info(final Marker marker, final String message, final Object... data) {
        log(marker, "INFO", message, data);
        return this;
    }

    public DummyLogBuilder warn(final Marker marker, final String message, final Object... data) {
        log(marker, "WARN", message, data);
        return this;
    }

    public DummyLogBuilder error(final Marker marker, final String message, final Object... data) {
        log(marker, "ERROR", message, data);
        return this;
    }

    private void log(final Marker marker, final String level, final String message, final Object... data) {

        if (timestamp.equals(previousTimestamp)) {
            timestamp = timestamp.plusNanos(1);
        }
        previousTimestamp = timestamp;

        String payload = message;
        if (data.length > 0 && !(data.length == 1 && data[0] instanceof Throwable)) {
            payload = String.format(message.replaceAll("\\{}", "%s"), data);
        }

        String exceptionType = null;
        String exceptionMessage = null;
        if (data.length > 0 && data[data.length - 1] instanceof Throwable) {
            Throwable t = (Throwable) data[data.length - 1];
            exceptionType = t.getClass().getName();
            exceptionMessage = t.getMessage();
        }

        JsonLogEntry entry = new JsonLogEntry(
                previousTimestamp,
                thread,
                level,
                loggerName,
                marker.getName(),
                exceptionType,
                exceptionMessage,
                payload);

        entries.add(entry);
    }

    /**
     * Simulate a wait between the previous log message and the next log message.
     */
    public void simulateWait(double seconds) {
        long secs = (long) seconds;
        long nsecs = (long) ((seconds - secs) * 1_000_000_000);
        timestamp = timestamp.plusSeconds(secs);
        timestamp = timestamp.plusNanos(nsecs);
    }

    /**
     * Build the dummy log from the registered messages.
     */
    public DummyLogReader build() {
        try {
            return new DummyLogReader(entries);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
