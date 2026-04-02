// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hederahashgraph.api.proto.java.RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi;
import org.junit.jupiter.api.Test;

class HapiPropertySourceBlockNodeEndpointTest {

    @Test
    void throwsWhenBlockNodeApiIsMissing() {
        final var ex = assertThrows(
                IllegalArgumentException.class, () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080"));
        assertTrue(ex.getMessage().contains("too few segments"));
    }

    @Test
    void throwsWhenOnlyTlsIsProvidedWithoutBlockNodeApi() {
        final var ex = assertThrows(
                IllegalArgumentException.class, () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:tls"));
        assertTrue(ex.getMessage().contains("Invalid blockNodeApi"));
    }

    @Test
    void throwsOnGarbageBetweenApiAndTls() {
        final var ex = assertThrows(
                IllegalArgumentException.class,
                () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:STATUS:garbage:tls"));
        assertTrue(ex.getMessage().contains("Unknown trailing segment"));
    }

    @Test
    void throwsOnUnknownSegmentAfterApi() {
        final var ex = assertThrows(
                IllegalArgumentException.class,
                () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:STATUS:notTls"));
        assertTrue(ex.getMessage().contains("Unknown trailing segment"));
    }

    @Test
    void throwsOnMissingPort() {
        final var ex =
                assertThrows(IllegalArgumentException.class, () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1"));
        assertTrue(ex.getMessage().contains("too few segments"));
    }

    @Test
    void parsesSingleApi() {
        final var endpoint = HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:STATUS");
        assertTrue(endpoint.hasBlockNode());
        assertEquals(1, endpoint.getBlockNode().getEndpointApiCount());
        assertEquals(BlockNodeApi.STATUS, endpoint.getBlockNode().getEndpointApi(0));
        assertFalse(endpoint.getRequiresTls());
    }

    @Test
    void parsesCommaSeparatedMultipleApis() {
        final var endpoint = HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:STATUS,PUBLISH");
        assertTrue(endpoint.hasBlockNode());
        assertEquals(2, endpoint.getBlockNode().getEndpointApiCount());
        assertEquals(BlockNodeApi.STATUS, endpoint.getBlockNode().getEndpointApi(0));
        assertEquals(BlockNodeApi.PUBLISH, endpoint.getBlockNode().getEndpointApi(1));
    }

    @Test
    void parsesCommaSeparatedMultipleApisWithTls() {
        final var endpoint = HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:STATUS,PUBLISH:tls");
        assertTrue(endpoint.hasBlockNode());
        assertEquals(2, endpoint.getBlockNode().getEndpointApiCount());
        assertTrue(endpoint.getRequiresTls());
    }

    @Test
    void parsesAllFourApis() {
        final var endpoint =
                HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:STATUS,PUBLISH,SUBSCRIBE_STREAM,STATE_PROOF");
        assertTrue(endpoint.hasBlockNode());
        assertEquals(4, endpoint.getBlockNode().getEndpointApiCount());
    }

    @Test
    void parsesLowercaseApiNames() {
        final var endpoint = HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:status,publish");
        assertTrue(endpoint.hasBlockNode());
        assertEquals(2, endpoint.getBlockNode().getEndpointApiCount());
        assertEquals(BlockNodeApi.STATUS, endpoint.getBlockNode().getEndpointApi(0));
        assertEquals(BlockNodeApi.PUBLISH, endpoint.getBlockNode().getEndpointApi(1));
    }

    @Test
    void throwsOnInvalidApiInCommaSeparatedList() {
        final var ex = assertThrows(
                IllegalArgumentException.class,
                () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:STATUS,INVALID"));
        assertTrue(ex.getMessage().contains("Invalid blockNodeApi"));
    }

    @Test
    void throwsOnEmptyApiSegmentInCommaSeparatedList() {
        final var ex = assertThrows(
                IllegalArgumentException.class,
                () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:STATUS,,PUBLISH"));
        assertTrue(ex.getMessage().contains("Empty API name"));
    }

    @Test
    void throwsOnTrailingCommaInApiList() {
        final var ex = assertThrows(
                IllegalArgumentException.class, () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:STATUS,"));
        assertTrue(ex.getMessage().contains("Empty API name"));
    }
}
