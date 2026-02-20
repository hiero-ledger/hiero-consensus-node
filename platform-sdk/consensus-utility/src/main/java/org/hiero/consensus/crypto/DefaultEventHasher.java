// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Default implementation of the {@link EventHasher}. Pools {@link PbjStreamHasher} instances to avoid repeated
 * allocation of {@link java.security.MessageDigest} objects and the associated JCA provider lookup overhead.
 */
public class DefaultEventHasher implements EventHasher {

    private final Queue<PbjStreamHasher> pool = new ConcurrentLinkedQueue<>();

    @Override
    @NonNull
    public PlatformEvent hashEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(event);
        PbjStreamHasher hasher = pool.poll();
        if (hasher == null) {
            hasher = new PbjStreamHasher();
        }
        hasher.hashEvent(event);
        pool.offer(hasher);
        return event;
    }
}
