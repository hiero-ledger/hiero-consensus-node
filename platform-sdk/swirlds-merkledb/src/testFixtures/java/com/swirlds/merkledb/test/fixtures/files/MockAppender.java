// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures.files;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.hiero.base.constructable.ConstructableIgnored;

/**
 * This class intended to be used in unit tests to capture log messages. It is thread-safe.
 * To make it work, add the following to your test:
 * <pre>{@code
 *         final MockAppender mockAppender = new MockAppender(<test name>);
 *         Logger logger = (Logger) LogManager.getLogger(DataFileCollection.class);
 *         mockAppender.start();
 *         logger.addAppender(mockAppender);
 *  } </pre>
 *  Then call {@code mockAppender.get(<index>)} to get the log message.
 *  After the test, call {@code mockAppender.stop()} to clean up.
 *
 */
@ConstructableIgnored
public class MockAppender extends AbstractAppender {
    private final List<String> messages = new CopyOnWriteArrayList<>();

    public MockAppender(final String name) {
        super("MockAppender-" + name, null, null, true, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void append(final LogEvent event) {
        if (isStarted()) {
            messages.add(String.format(
                    "%s - %s - %s",
                    event.getMarker().getName(),
                    event.getLevel(),
                    event.getMessage().getFormattedMessage()));
        }
    }

    /**
     * Get the number of captured log messages.
     * @return the number of captured log messages
     */
    public int size() {
        return messages.size();
    }

    /**
     * Clear previously captured the log messages.
     */
    public void clear() {
        messages.clear();
    }

    /**
     * Get the log message at the specified index.
     * @param index the index of the message
     * @return the log message
     */
    public String get(final int index) {
        return messages.get(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        messages.clear();
        super.stop();
    }
}
