// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.signer;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.test.fixtures.Randotron;

public class RandomEventSigner implements EventSigner {
    private final Randotron randotron;

    public RandomEventSigner(final long seed) {
        this.randotron = Randotron.create(seed);
    }

    @Override
    public Bytes signEvent(final UnsignedEvent unsignedEvent) {
        return randotron.nextSignatureBytes();
    }
}
