// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.spec.HapiPropertySource;
import org.junit.jupiter.api.Test;

class HapiPropertySourceGeneralServiceEndpointTest {

    @Test
    void minimalEndpointWithNoDescriptionNoTls() {
        final var endpoint = HapiPropertySource.asGeneralServiceEndpoint("127.0.0.1:8080");
        assertEquals(8080, endpoint.getPort());
        assertFalse(endpoint.getRequiresTls());
        assertTrue(endpoint.hasGeneralService());
        assertEquals("", endpoint.getGeneralService().getDescription());
    }

    @Test
    void tlsOnlyWithNoDescription() {
        final var endpoint = HapiPropertySource.asGeneralServiceEndpoint("127.0.0.1:8080:tls");
        assertEquals(8080, endpoint.getPort());
        assertTrue(endpoint.getRequiresTls());
        assertTrue(endpoint.hasGeneralService());
        assertEquals("", endpoint.getGeneralService().getDescription());
    }

    @Test
    void withDescription() {
        final var endpoint = HapiPropertySource.asGeneralServiceEndpoint("service.example.com:9090:Custom indexer");
        assertEquals(9090, endpoint.getPort());
        assertFalse(endpoint.getRequiresTls());
        assertEquals("Custom indexer", endpoint.getGeneralService().getDescription());
        assertTrue(endpoint.hasDomainName());
    }

    @Test
    void withDescriptionAndTls() {
        final var endpoint = HapiPropertySource.asGeneralServiceEndpoint("service.example.com:9090:Custom indexer:tls");
        assertEquals(9090, endpoint.getPort());
        assertTrue(endpoint.getRequiresTls());
        assertEquals("Custom indexer", endpoint.getGeneralService().getDescription());
    }

    @Test
    void colonsInDescription() {
        final var endpoint = HapiPropertySource.asGeneralServiceEndpoint("10.0.0.1:8080:Service:with:colons:tls");
        assertEquals(8080, endpoint.getPort());
        assertTrue(endpoint.getRequiresTls());
        assertEquals("Service:with:colons", endpoint.getGeneralService().getDescription());
    }

    @Test
    void domainNameAddress() {
        final var endpoint = HapiPropertySource.asGeneralServiceEndpoint("custom.example.com:443:My service");
        assertEquals(443, endpoint.getPort());
        assertFalse(endpoint.getRequiresTls());
        assertEquals("My service", endpoint.getGeneralService().getDescription());
        assertTrue(endpoint.hasDomainName());
        assertEquals("custom.example.com", endpoint.getDomainName());
    }

    @Test
    void throwsOnMissingPort() {
        final var ex = assertThrows(
                IllegalArgumentException.class, () -> HapiPropertySource.asGeneralServiceEndpoint("127.0.0.1"));
        assertTrue(ex.getMessage().contains("too few segments"));
    }
}
