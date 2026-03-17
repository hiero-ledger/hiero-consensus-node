// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.spec.HapiPropertySource;
import org.junit.jupiter.api.Test;

class HapiPropertySourceSimpleEndpointTest {

    @Test
    void parseMirrorNodeAddrPort() {
        final var endpoint = HapiPropertySource.asMirrorNodeEndpoint("127.0.0.1:8080");
        assertEquals(8080, endpoint.getPort());
        assertFalse(endpoint.getRequiresTls());
        assertTrue(endpoint.hasMirrorNode());
    }

    @Test
    void parseMirrorNodeAddrPortTls() {
        final var endpoint = HapiPropertySource.asMirrorNodeEndpoint("mirror.example.com:443:tls");
        assertEquals(443, endpoint.getPort());
        assertTrue(endpoint.getRequiresTls());
        assertTrue(endpoint.hasMirrorNode());
    }

    @Test
    void parseRpcRelayAddrPort() {
        final var endpoint = HapiPropertySource.asRpcRelayEndpoint("127.0.0.1:8545");
        assertEquals(8545, endpoint.getPort());
        assertFalse(endpoint.getRequiresTls());
        assertTrue(endpoint.hasRpcRelay());
    }

    @Test
    void throwsOnUnknownTrailingSegment() {
        final var ex = assertThrows(
                IllegalArgumentException.class, () -> HapiPropertySource.asMirrorNodeEndpoint("127.0.0.1:8080:typo"));
        assertTrue(ex.getMessage().contains("Unknown trailing segment"));
    }

    @Test
    void throwsOnExtraSegmentAfterTls() {
        final var ex = assertThrows(
                IllegalArgumentException.class,
                () -> HapiPropertySource.asMirrorNodeEndpoint("127.0.0.1:8080:tls:extra"));
        assertTrue(ex.getMessage().contains("Unknown trailing segment"));
    }

    @Test
    void throwsOnMissingPort() {
        final var ex = assertThrows(
                IllegalArgumentException.class, () -> HapiPropertySource.asMirrorNodeEndpoint("127.0.0.1"));
        assertTrue(ex.getMessage().contains("too few segments"));
    }
}
