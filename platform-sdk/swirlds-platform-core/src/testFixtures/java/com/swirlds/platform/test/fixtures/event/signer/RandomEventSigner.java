package com.swirlds.platform.test.fixtures.event.signer;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import org.hiero.consensus.model.event.UnsignedEvent;

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
