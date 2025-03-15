// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.modular;

import com.swirlds.common.threading.framework.StoppableThread;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * Represents a thread created for a specific context
 * @param key opaque context for which this thread is created
 * @param thread thread itself, to be started/stopped/forgotten depending on the key context
 */
public record DedicatedStoppableThread<E>(@NonNull E key, @Nullable StoppableThread thread) {
    /**
     * Utility method to start contained thread; simple shortcut to {@code thread().start()}
     */
    public void start() {
        Objects.requireNonNull(thread);
        thread.start();
    }
}
