// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication;

import com.swirlds.platform.network.protocol.PeerProtocol;
import com.swirlds.platform.test.fixtures.communication.TestPeerProtocol;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NegotiationProtocolsTest {
    @DisplayName("Text NegotiationProtocols construction exceptions")
    @Test
    void constructionExceptions() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new NegotiationProtocols(null),
                "a null list should throw an exception");
        final List<PeerProtocol> empty = List.of();
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new NegotiationProtocols(empty),
                "a empty list should throw an exception");
        final List<PeerProtocol> lots = Stream.generate(TestPeerProtocol::new)
                .limit(1000)
                .map(p -> (PeerProtocol) p)
                .toList();
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new NegotiationProtocols(lots),
                "a list with more protocols than the limit should throw an exception");
    }
}
