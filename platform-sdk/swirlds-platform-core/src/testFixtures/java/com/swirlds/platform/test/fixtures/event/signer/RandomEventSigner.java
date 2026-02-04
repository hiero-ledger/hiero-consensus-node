// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.signer;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.test.fixtures.Randotron;

/**
 * An {@link EventSigner} that produces random (non-cryptographic) signature bytes. Useful in tests where signature
 * validity is not checked.
 */
public class RandomEventSigner implements EventSigner {
    private final Randotron randotron;

    /**
     * Creates a new {@code RandomEventSigner} with the given seed for deterministic random output.
     *
     * @param seed the random seed
     */
    public RandomEventSigner(final long seed) {
        this.randotron = Randotron.create(seed);
    }

    @Override
    public Bytes signEvent(final UnsignedEvent unsignedEvent) {
        return randotron.nextSignatureBytes();
    }
}
