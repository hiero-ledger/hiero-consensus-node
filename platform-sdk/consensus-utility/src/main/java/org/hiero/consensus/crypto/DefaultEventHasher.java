// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Default implementation of the {@link EventHasher}.
 */
public class DefaultEventHasher implements EventHasher {

    // A thread local in a fixed size thread pool results in better performance than a concurrent list for the same use
    // case
    // we should re-evaluate once we decide to use an unbounded threadpool
    private static final ThreadLocal<PbjStreamHasher> HASHER = ThreadLocal.withInitial(PbjStreamHasher::new);

    @Override
    @NonNull
    public PlatformEvent hashEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(event);
        HASHER.get().hashEvent(event);
        return event;
    }
}
