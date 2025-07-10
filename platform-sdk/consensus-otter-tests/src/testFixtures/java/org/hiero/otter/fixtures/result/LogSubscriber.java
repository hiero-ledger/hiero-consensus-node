// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.logging.StructuredLog;

/**
 * Defines a subscriber that will receive {@link StructuredLog} entries.
 */
@FunctionalInterface
public interface LogSubscriber {

    /**
     * Called when new {@link StructuredLog}s are available.
     *
     * @param logEntry the new {@link StructuredLog} entry
     * @return {@link SubscriberAction#UNSUBSCRIBE} to unsubscribe, {@link SubscriberAction#CONTINUE} to continue
     */
    SubscriberAction onLogEntry(@NonNull StructuredLog logEntry);
}
