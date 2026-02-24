// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_DESCRIPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Validation utilities for HIP-1137 registered nodes.
 */
public final class RegisteredNodeValidator {
    private static final int MAX_DESCRIPTION_UTF8_BYTES = 100;
    private static final int MAX_SERVICE_ENDPOINTS = 50;
    private static final int MAX_DOMAIN_ASCII_CHARS = 250;

    private RegisteredNodeValidator() {}

    public static void validateDescription(@Nullable final String description) throws PreCheckException {
        if (description == null || description.isEmpty()) {
            return;
        }
        final var raw = description.getBytes(StandardCharsets.UTF_8);
        validateFalsePreCheck(raw.length > MAX_DESCRIPTION_UTF8_BYTES, INVALID_NODE_DESCRIPTION);
        for (final byte b : raw) {
            if (b == 0) {
                throw new PreCheckException(INVALID_NODE_DESCRIPTION);
            }
        }
    }

    public static void validateServiceEndpointsForCreate(@NonNull final List<RegisteredServiceEndpoint> endpoints)
            throws PreCheckException {
        requireNonNull(endpoints);
        validateFalsePreCheck(endpoints.isEmpty(), INVALID_SERVICE_ENDPOINT);
        validateFalsePreCheck(endpoints.size() > MAX_SERVICE_ENDPOINTS, INVALID_SERVICE_ENDPOINT);
        for (final var endpoint : endpoints) {
            validateServiceEndpoint(endpoint);
        }
    }

    public static void validateServiceEndpointsForUpdate(@NonNull final List<RegisteredServiceEndpoint> endpoints)
            throws PreCheckException {
        requireNonNull(endpoints);
        // For updates, an empty list means "not set" (proto3 repeated has no presence), so only validate if non-empty.
        if (endpoints.isEmpty()) {
            return;
        }
        validateFalsePreCheck(endpoints.size() > MAX_SERVICE_ENDPOINTS, INVALID_SERVICE_ENDPOINT);
        for (final var endpoint : endpoints) {
            validateServiceEndpoint(endpoint);
        }
    }

    private static void validateServiceEndpoint(@NonNull final RegisteredServiceEndpoint endpoint)
            throws PreCheckException {
        requireNonNull(endpoint);

        final int port = endpoint.port();
        validateTruePreCheck(port >= 0 && port <= 65535, INVALID_SERVICE_ENDPOINT);

        // oneof address is REQUIRED
        final var addressKind = endpoint.address().kind();
        switch (addressKind) {
            case IP_ADDRESS -> {
                final Bytes ip = endpoint.ipAddressOrThrow();
                final long len = ip.length();
                validateTruePreCheck(len == 4L || len == 16L, INVALID_SERVICE_ENDPOINT);
            }
            case DOMAIN_NAME -> {
                final String domain = endpoint.domainNameOrThrow();
                validateTruePreCheck(isValidAsciiFqdn(domain), INVALID_SERVICE_ENDPOINT);
            }
            default -> throw new PreCheckException(INVALID_SERVICE_ENDPOINT);
        }

        // oneof endpoint_type is REQUIRED
        validateTruePreCheck(
                endpoint.endpointType().kind() != RegisteredServiceEndpoint.EndpointTypeOneOfType.UNSET,
                INVALID_SERVICE_ENDPOINT);
    }

    private static boolean isValidAsciiFqdn(@Nullable final String domain) {
        if (domain == null) {
            return false;
        }
        final var trimmed = domain.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.length() > MAX_DOMAIN_ASCII_CHARS) {
            return false;
        }
        // ASCII only
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) > 0x7F) {
                return false;
            }
        }

        // Basic DNS label rules: labels 1..63, overall <=253, only [A-Za-z0-9-], no leading/trailing '-'
        if (trimmed.length() > 253) {
            return false;
        }
        if (trimmed.endsWith(".")) {
            // Allow a trailing dot by stripping it for label validation
            return isValidAsciiFqdn(trimmed.substring(0, trimmed.length() - 1));
        }
        final var labels = trimmed.split("\\.");
        if (labels.length == 0) {
            return false;
        }
        for (final var label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                return false;
            }
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                final char c = label.charAt(i);
                final boolean ok =
                        (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
                if (!ok) {
                    return false;
                }
            }
        }
        return true;
    }
}
