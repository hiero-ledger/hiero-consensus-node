// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.signer;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.consensus.model.event.UnsignedEvent;

@FunctionalInterface
public interface EventSigner {
    Bytes signEvent(UnsignedEvent unsignedEvent);
}
