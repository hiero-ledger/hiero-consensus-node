// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The only thing {@link RecordingEventImpl} calls.
 * <p>
 * Two sinks are envisaged, differing only in retention: {@link ConsensusTraceLog}, which accumulates so a fixture can
 * read the whole run afterwards, and a Falcon-side filter that would evaluate registered properties and discard.
 * Accumulating is right at 10-40 events and wrong at Falcon scale.
 */
@FunctionalInterface
public interface ChangeSink {

    /**
     * Accept a change.
     * <p>
     * The sink assigns the sequence number, so the recorder does not have to know the global order it is being written
     * into - and a sink that retains nothing does not have to maintain a counter at all.
     *
     * @param factory builds the change once its sequence number is known
     */
    void accept(@NonNull ChangeFactory factory);

    /** Builds a {@link Change} once the sink has assigned it a sequence number. */
    @FunctionalInterface
    interface ChangeFactory {
        @NonNull
        Change create(long seq);
    }
}
