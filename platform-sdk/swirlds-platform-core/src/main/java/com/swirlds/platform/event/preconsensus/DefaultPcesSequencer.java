// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.platform.event.PlatformEvent;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The default implementation of the {@link PcesSequencer}.
 */
public class DefaultPcesSequencer implements PcesSequencer {

    private long nextStreamSequenceNumber = 0;

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PlatformEvent assignStreamSequenceNumber(@NonNull final PlatformEvent event) {
        event.setStreamSequenceNumber(nextStreamSequenceNumber++);

        return event;
    }
}
