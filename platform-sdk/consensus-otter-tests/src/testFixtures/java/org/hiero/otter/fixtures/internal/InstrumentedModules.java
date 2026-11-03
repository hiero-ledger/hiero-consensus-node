// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

/**
 * Constants for the instrumented modules used by instrumented nodes.
 *
 * <p>The instrumented modules are only runtime dependencies of the test fixtures, so they are referenced by name.
 */
public final class InstrumentedModules {

    /** The JPMS module name of the instrumented {@code EventCreatorModule}. */
    public static final String INSTRUMENTED_EVENT_CREATOR =
            "org.hiero.consensus.event.creator.instrumented.test.fixtures";

    /** The {@code EventBus} channel on which instrumented modules receive ping messages. */
    public static final String PING_CHANNEL = "ping";

    private InstrumentedModules() {}
}
