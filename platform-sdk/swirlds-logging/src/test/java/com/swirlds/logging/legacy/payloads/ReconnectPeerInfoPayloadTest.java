// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payloads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.logging.legacy.payload.ReconnectPeerInfoPayload;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReconnectPeerInfoPayloadTest {

    private static Stream<Arguments> peerInfo() {
        return Stream.of(Arguments.of(0L, null), Arguments.of(1L, ""), Arguments.of(2L, "hello swirlds"));
    }

    private ReconnectPeerInfoPayload payload;

    @BeforeEach
    public void setup() {
        payload = new ReconnectPeerInfoPayload();
    }

    @Test
    void testConstructor() {
        assertNotNull(payload.getMessage(), "message should be initialized.");
        assertTrue(payload.getMessage().isEmpty(), "message should be initialized to an empty string.");
        assertNotNull(payload.getPeerInfo(), "peerInfo should be initialized.");
        assertTrue(payload.getPeerInfo().isEmpty(), "peerInfo should be initialized to an empty collection.");
    }

    @ParameterizedTest
    @MethodSource("peerInfo")
    void testAddPeerInfo(final long peerId, final String msg) {
        payload.addPeerInfo(peerId, msg);
        assertFalse(payload.getPeerInfo().isEmpty(), "peerInfo should not be empty after adding a value.");
        assertEquals(
                1,
                payload.getPeerInfo().size(),
                "peerInfo size should be equal to the number of times 'addPeerInfo' is called.");

        ReconnectPeerInfoPayload.PeerInfo info = payload.getPeerInfo().get(0);
        assertEquals(peerId, info.getNode(), "peerInfo left value should match the value provided.");
        assertEquals(msg, info.getMessage(), "peerInfo right value should match the value provided.");
    }
}
