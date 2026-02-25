// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Default implementation of the {@link EventHasher}.
 */
public class DefaultEventHasher implements EventHasher {

    private static final ThreadLocal<PbjStreamHasher> HASHER = ThreadLocal.withInitial(PbjStreamHasher::new);

    @Override
    @NonNull
    public PlatformEvent hashEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(event);
        HASHER.get().hashEvent(event);
        return event;
    }
}
