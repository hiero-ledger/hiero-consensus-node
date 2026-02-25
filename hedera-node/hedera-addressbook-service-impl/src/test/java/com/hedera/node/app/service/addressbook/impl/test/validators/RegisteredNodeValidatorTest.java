// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_DESCRIPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hedera.node.app.service.addressbook.impl.validators.RegisteredNodeValidator.validateDescription;
import static com.hedera.node.app.service.addressbook.impl.validators.RegisteredNodeValidator.validateServiceEndpointsForCreate;
import static com.hedera.node.app.service.addressbook.impl.validators.RegisteredNodeValidator.validateServiceEndpointsForUpdate;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegisteredNodeValidatorTest {

    // --- Description validation ---

    @Test
    void nullDescriptionIsValid() {
        assertDoesNotThrow(() -> validateDescription(null));
    }

    @Test
    void emptyDescriptionIsValid() {
        assertDoesNotThrow(() -> validateDescription(""));
    }

    @Test
    void shortDescriptionIsValid() {
        assertDoesNotThrow(() -> validateDescription("Block node operated by Alice"));
    }

    @Test
    void descriptionAtMaxBytesIsValid() {
        // 100 ASCII chars = 100 UTF-8 bytes
        final var desc = "a".repeat(100);
        assertDoesNotThrow(() -> validateDescription(desc));
    }

    @Test
    void descriptionExceedingMaxBytesIsInvalid() {
        final var desc = "a".repeat(101);
        final var e = assertThrows(PreCheckException.class, () -> validateDescription(desc));
        assertEquals(INVALID_NODE_DESCRIPTION, e.responseCode());
    }

    @Test
    void multiByteDescriptionExceedingMaxBytesIsInvalid() {
        // Each emoji is 4 UTF-8 bytes; 26 emojis = 104 bytes > 100
        final var desc = "\uD83D\uDE00".repeat(26);
        final var e = assertThrows(PreCheckException.class, () -> validateDescription(desc));
        assertEquals(INVALID_NODE_DESCRIPTION, e.responseCode());
    }

    @Test
    void descriptionWithNullByteIsInvalid() {
        final var e = assertThrows(PreCheckException.class, () -> validateDescription("hello\0world"));
        assertEquals(INVALID_NODE_DESCRIPTION, e.responseCode());
    }

    // --- Service endpoint validation for create ---

    @Test
    void createWithEmptyEndpointsIsInvalid() {
        final var e = assertThrows(PreCheckException.class, () -> validateServiceEndpointsForCreate(List.of()));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void createWithValidIpv4EndpointSucceeds() {
        assertDoesNotThrow(() -> validateServiceEndpointsForCreate(List.of(validIpv4Endpoint())));
    }

    @Test
    void createWithValidIpv6EndpointSucceeds() {
        assertDoesNotThrow(() -> validateServiceEndpointsForCreate(List.of(validIpv6Endpoint())));
    }

    @Test
    void createWithValidDomainEndpointSucceeds() {
        assertDoesNotThrow(() -> validateServiceEndpointsForCreate(List.of(validDomainEndpoint())));
    }

    @Test
    void createExceedingMaxEndpointsIsInvalid() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 51; i++) {
            endpoints.add(validIpv4Endpoint());
        }
        final var e = assertThrows(PreCheckException.class, () -> validateServiceEndpointsForCreate(endpoints));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void createAt50EndpointsSucceeds() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 50; i++) {
            endpoints.add(validIpv4Endpoint());
        }
        assertDoesNotThrow(() -> validateServiceEndpointsForCreate(endpoints));
    }

    // --- Service endpoint validation for update ---

    @Test
    void updateWithEmptyEndpointsIsValid() {
        assertDoesNotThrow(() -> validateServiceEndpointsForUpdate(List.of()));
    }

    @Test
    void updateWithValidEndpointSucceeds() {
        assertDoesNotThrow(() -> validateServiceEndpointsForUpdate(List.of(validIpv4Endpoint())));
    }

    @Test
    void updateExceedingMaxEndpointsIsInvalid() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 51; i++) {
            endpoints.add(validIpv4Endpoint());
        }
        final var e = assertThrows(PreCheckException.class, () -> validateServiceEndpointsForUpdate(endpoints));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    // --- Individual endpoint validation ---

    @Test
    void endpointWithNoAddressIsInvalid() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .port(8080)
                .blockNode(blockNodeEndpoint())
                .build();
        final var e = assertThrows(PreCheckException.class, () -> validateServiceEndpointsForCreate(List.of(endpoint)));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void endpointWithNoEndpointTypeIsInvalid() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                .port(8080)
                .build();
        final var e = assertThrows(PreCheckException.class, () -> validateServiceEndpointsForCreate(List.of(endpoint)));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void endpointWithInvalidIpLengthIsInvalid() {
        // 5 bytes is neither IPv4 (4) nor IPv6 (16)
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0, 1, 1}))
                .port(8080)
                .blockNode(blockNodeEndpoint())
                .build();
        final var e = assertThrows(PreCheckException.class, () -> validateServiceEndpointsForCreate(List.of(endpoint)));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void endpointWithMirrorNodeTypeSucceeds() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                .port(5600)
                .mirrorNode(RegisteredServiceEndpoint.MirrorNodeEndpoint.DEFAULT)
                .build();
        assertDoesNotThrow(() -> validateServiceEndpointsForCreate(List.of(endpoint)));
    }

    @Test
    void endpointWithRpcRelayTypeSucceeds() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                .port(7546)
                .rpcRelay(RegisteredServiceEndpoint.RpcRelayEndpoint.DEFAULT)
                .build();
        assertDoesNotThrow(() -> validateServiceEndpointsForCreate(List.of(endpoint)));
    }

    // --- FQDN validation ---

    @Test
    void validSimpleDomainSucceeds() {
        assertDoesNotThrow(() -> validateServiceEndpointsForCreate(List.of(domainEndpoint("example.com"))));
    }

    @Test
    void validSubdomainSucceeds() {
        assertDoesNotThrow(() -> validateServiceEndpointsForCreate(List.of(domainEndpoint("block.node.example.com"))));
    }

    @Test
    void domainWithTrailingDotSucceeds() {
        assertDoesNotThrow(() -> validateServiceEndpointsForCreate(List.of(domainEndpoint("example.com."))));
    }

    @Test
    void domainWithLeadingHyphenInLabelIsInvalid() {
        final var e = assertThrows(
                PreCheckException.class,
                () -> validateServiceEndpointsForCreate(List.of(domainEndpoint("-example.com"))));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void domainWithTrailingHyphenInLabelIsInvalid() {
        final var e = assertThrows(
                PreCheckException.class,
                () -> validateServiceEndpointsForCreate(List.of(domainEndpoint("example-.com"))));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void domainWithNonAsciiIsInvalid() {
        final var e = assertThrows(
                PreCheckException.class,
                () -> validateServiceEndpointsForCreate(List.of(domainEndpoint("ex\u00E4mple.com"))));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void domainExceeding253CharsIsInvalid() {
        final var longDomain = ("a".repeat(63) + ".").repeat(4) + "com";
        final var e = assertThrows(
                PreCheckException.class, () -> validateServiceEndpointsForCreate(List.of(domainEndpoint(longDomain))));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void labelExceeding63CharsIsInvalid() {
        final var longLabel = "a".repeat(64) + ".com";
        final var e = assertThrows(
                PreCheckException.class, () -> validateServiceEndpointsForCreate(List.of(domainEndpoint(longLabel))));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void emptyDomainIsInvalid() {
        final var e = assertThrows(
                PreCheckException.class, () -> validateServiceEndpointsForCreate(List.of(domainEndpoint(""))));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    // --- Helpers ---

    private static RegisteredServiceEndpoint validIpv4Endpoint() {
        return RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                .port(8080)
                .blockNode(blockNodeEndpoint())
                .build();
    }

    private static RegisteredServiceEndpoint validIpv6Endpoint() {
        return RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[16]))
                .port(8080)
                .blockNode(blockNodeEndpoint())
                .build();
    }

    private static RegisteredServiceEndpoint validDomainEndpoint() {
        return domainEndpoint("block.example.com");
    }

    private static RegisteredServiceEndpoint domainEndpoint(final String domain) {
        return RegisteredServiceEndpoint.newBuilder()
                .domainName(domain)
                .port(443)
                .requiresTls(true)
                .blockNode(blockNodeEndpoint())
                .build();
    }

    private static RegisteredServiceEndpoint.BlockNodeEndpoint blockNodeEndpoint() {
        return RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS)
                .build();
    }
}
