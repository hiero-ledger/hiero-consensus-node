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
     * Changes arrive synchronously from inside {@code ConsensusImpl#addEvent}, so the sink receives them in exactly the
     * order the algorithm made them.
     *
     * @param change the change
     */
    void accept(@NonNull Change change);
}
