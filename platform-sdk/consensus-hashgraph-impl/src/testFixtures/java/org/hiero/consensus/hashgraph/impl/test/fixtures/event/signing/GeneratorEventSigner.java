// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.event.signing;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.consensus.model.event.UnsignedEvent;

/**
 * A functional interface for signing an {@link UnsignedEvent} and producing a signature as {@link Bytes}, used when
 * generating events in tests.
 */
@FunctionalInterface
public interface GeneratorEventSigner {
    /**
     * Signs the given unsigned event and returns the signature bytes.
     *
     * @param unsignedEvent the event to sign
     * @return the signature bytes
     */
    Bytes signEvent(UnsignedEvent unsignedEvent);
}
