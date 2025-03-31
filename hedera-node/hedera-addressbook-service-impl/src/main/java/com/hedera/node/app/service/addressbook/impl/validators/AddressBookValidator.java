// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FQDN_SIZE_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.GOSSIP_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_IPV4_ADDRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_DESCRIPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.IP_FQDN_CANNOT_BE_SET_FOR_SAME_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_REQUIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SERVICE_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isEmpty;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.readCertificatePemFile;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.writeCertificatePemFile;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class AddressBookValidator {
    /**
     * Default constructor for injection.
     */
    @Inject
    public AddressBookValidator() {
        // Dagger2
    }

    /**
     * Validates the node description.
     *
     * @param description The description to validate
     * @param nodesConfig The nodes configuration
     */
    public void validateDescription(@Nullable final String description, @NonNull final NodesConfig nodesConfig) {
        requireNonNull(nodesConfig);

        if (description == null || description.isEmpty()) {
            return;
        }
        final var raw = description.getBytes(StandardCharsets.UTF_8);
        final var maxUtf8Bytes = nodesConfig.nodeMaxDescriptionUtf8Bytes();
        validateFalse(raw.length > maxUtf8Bytes, INVALID_NODE_DESCRIPTION);
        validateFalse(containsZeroByte(raw), INVALID_NODE_DESCRIPTION);
    }

    private boolean containsZeroByte(@NonNull final byte[] bytes) {
        requireNonNull(bytes);

        boolean ret = false;
        for (final byte b : bytes) {
            if (b == 0) {
                ret = true;
                break;
            }
        }
        return ret;
    }

    /**
     * Validates the gossip endpoint.
     *
     * @param endpointList The list of GossipEndpoint to validate
     * @param nodesConfig The nodes configuration
     */
    public void validateGossipEndpoint(
            @Nullable final List<ServiceEndpoint> endpointList, @NonNull final NodesConfig nodesConfig) {
        requireNonNull(nodesConfig);

        validateFalse(endpointList == null || endpointList.isEmpty(), INVALID_GOSSIP_ENDPOINT);
        validateFalse(endpointList.size() > nodesConfig.maxGossipEndpoint(), GOSSIP_ENDPOINTS_EXCEEDED_LIMIT);
        // for phase 2: The first in the list is used as the Internal IP address in config.txt,
        // the second in the list is used as the External IP address in config.txt
        validateFalse(endpointList.size() < 2, INVALID_GOSSIP_ENDPOINT);

        for (final var endpoint : endpointList) {
            validateFalse(
                    nodesConfig.gossipFqdnRestricted() && !endpoint.domainName().isEmpty(),
                    GOSSIP_ENDPOINT_CANNOT_HAVE_FQDN);
            validateEndpoint(endpoint, nodesConfig);
        }
    }

    /**
     * Validates the service endpoint.
     *
     * @param endpointList The list of ServiceEndpoint to validate
     * @param nodesConfig The nodes configuration
     */
    public void validateServiceEndpoint(
            @Nullable final List<ServiceEndpoint> endpointList, @NonNull final NodesConfig nodesConfig) {
        requireNonNull(nodesConfig);

        validateFalse(endpointList == null || endpointList.isEmpty(), INVALID_SERVICE_ENDPOINT);
        validateFalse(endpointList.size() > nodesConfig.maxServiceEndpoint(), SERVICE_ENDPOINTS_EXCEEDED_LIMIT);
        for (final var endpoint : endpointList) {
            validateEndpoint(endpoint, nodesConfig);
        }
    }

    /**
     * Validates the admin key.
     *
     * @param key The key to validate
     * @throws PreCheckException if the key is invalid
     */
    public void validateAdminKey(@Nullable Key key) throws PreCheckException {
        final var keyEmpty = isEmpty(key);
        validateFalsePreCheck(keyEmpty, KEY_REQUIRED);
        validateTruePreCheck(isValid(key), INVALID_ADMIN_KEY);
    }

    /**
     * Validates the account ID.
     *
     * @param accountId The account ID to validate
     * @throws PreCheckException if the account ID is invalid
     */
    public void validateAccountId(@Nullable AccountID accountId) throws PreCheckException {
        validateAccountID(accountId, INVALID_NODE_ACCOUNT_ID);
        validateFalsePreCheck(
                !requireNonNull(accountId).hasAccountNum() && accountId.hasAlias(), INVALID_NODE_ACCOUNT_ID);
    }

    private void validateEndpoint(@NonNull final ServiceEndpoint endpoint, @NonNull final NodesConfig nodesConfig) {
        requireNonNull(endpoint);
        requireNonNull(nodesConfig);

        validateFalse(endpoint.port() == 0, INVALID_ENDPOINT);
        final var addressLen = endpoint.ipAddressV4().length();
        validateFalse(addressLen == 0 && endpoint.domainName().trim().isEmpty(), INVALID_ENDPOINT);
        validateFalse(
                addressLen != 0 && !endpoint.domainName().trim().isEmpty(), IP_FQDN_CANNOT_BE_SET_FOR_SAME_ENDPOINT);
        validateFalse(endpoint.domainName().trim().length() > nodesConfig.maxFqdnSize(), FQDN_SIZE_TOO_LARGE);
        validateFalse(addressLen != 0 && addressLen != 4, INVALID_IPV4_ADDRESS);
    }

    /**
     * Validates the given bytes encode an X509 certificate can be serialized and deserialized from
     * PEM format to recover a usable certificate.
     * @param x509CertBytes the bytes to validate
     * @throws PreCheckException if the certificate is invalid
     */
    public static void validateX509Certificate(@NonNull final Bytes x509CertBytes) throws PreCheckException {
        try {
            // Serialize the given bytes to a PEM file just as we would on a PREPARE_UPGRADE
            final var baos = new ByteArrayOutputStream();
            writeCertificatePemFile(x509CertBytes.toByteArray(), baos);
            // Deserialize an X509 certificate from the resulting PEM file
            final var bais = new ByteArrayInputStream(baos.toByteArray());
            final var cert = readCertificatePemFile(bais);
            // And check its validity for completeness
            cert.checkValidity();
        } catch (Exception ignore) {
            throw new PreCheckException(INVALID_GOSSIP_CA_CERTIFICATE);
        }
    }
}
