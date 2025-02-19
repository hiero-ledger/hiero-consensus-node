/*
 * Copyright (C) 2016-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.test.network.communication;

import com.swirlds.platform.network.communication.NegotiationProtocols;
import com.swirlds.platform.network.protocol.PeerProtocol;
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
