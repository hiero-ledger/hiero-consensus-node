// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static com.swirlds.platform.crypto.KeyCertPurpose.AGREEMENT;
import static com.swirlds.platform.crypto.KeyCertPurpose.SIGNING;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.stream.Stream;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.consensus.crypto.CryptoConstants;
import org.hiero.consensus.model.node.NodeId;

/**
 * Public certificates for all the members of the network
 *
 * @param agrTrustStore
 * 		the trust store for all the sig certs (self-signed signing cert)
 * @param sigTrustStore
 * 		the trust store for all the enc certs (encryption cert, signed by signing key)
 */
public record PublicStores(KeyStore sigTrustStore, KeyStore agrTrustStore) {
    public PublicStores() throws KeyStoreException {
        this(CryptoStatic.createEmptyTrustStore(), CryptoStatic.createEmptyTrustStore());
    }

    /**
     * Creates an instance loads all the certificates from the provided key store
     *
     * @param allPublic
     * 		key store with all certificates
     * @param nodeIds the nodeIds of all members
     * @return an instance
     * @throws KeyStoreException
     * 		if there is no provider that supports {@link CryptoConstants#KEYSTORE_TYPE}
     * 		or the key store provided has not been initialized
     * @throws KeyLoadingException
     * 		if any of the certificates cannot be found
     */
    public static PublicStores fromAllPublic(final KeyStore allPublic, final Iterable<NodeId> nodeIds)
            throws KeyStoreException, KeyLoadingException {
        final KeyStore sigTrustStore = CryptoStatic.createEmptyTrustStore();
        final KeyStore agrTrustStore = CryptoStatic.createEmptyTrustStore();

        for (NodeId nodeId : nodeIds) {
            Certificate sigCert = allPublic.getCertificate(SIGNING.storeName(nodeId));
            Certificate agrCert = allPublic.getCertificate(AGREEMENT.storeName(nodeId));

            // the agreement certificate is allowed to be absent. The signing certificate is required.
            if (Stream.of(sigCert).anyMatch(Objects::isNull)) {
                throw new KeyLoadingException("Cannot find certificates for: " + nodeId);
            }

            sigTrustStore.setCertificateEntry(SIGNING.storeName(nodeId), sigCert);
            agrTrustStore.setCertificateEntry(AGREEMENT.storeName(nodeId), agrCert);
        }
        return new PublicStores(sigTrustStore, agrTrustStore);
    }

    /**
     * @param type
     * 		the type of certificate
     * @param certificate
     * 		the certificate
     * @param nodeId The nodeId of the involved node
     * @throws KeyStoreException
     * 		if the given alias already exists and does not identify an entry containing a trusted certificate,
     * 		or this operation fails for some other reason
     */
    public void setCertificate(final KeyCertPurpose type, final X509Certificate certificate, final NodeId nodeId)
            throws KeyStoreException {
        switch (type) {
            case SIGNING -> sigTrustStore.setCertificateEntry(type.storeName(nodeId), certificate);
            case AGREEMENT -> agrTrustStore.setCertificateEntry(type.storeName(nodeId), certificate);
        }
    }

    /**
     * @param type
     * 		the type of certificate requested
     * @param nodeId The id of the involved node
     * @return a certificate stored in one of the stores
     * @throws KeyLoadingException
     * 		if the certificate is missing or is not an instance of X509Certificate
     */
    public X509Certificate getCertificate(final KeyCertPurpose type, final NodeId nodeId) throws KeyLoadingException {
        final Certificate certificate;
        final var name = type.storeName(nodeId);
        try {
            certificate = switch (type) {
                case SIGNING -> sigTrustStore.getCertificate(name);
                case AGREEMENT -> agrTrustStore.getCertificate(name);
            };
        } catch (KeyStoreException e) {
            // cannot be thrown because we ensure the key store is initialized in the constructor
            throw new CryptographyException(e);
        }
        if (certificate == null) {
            throw new KeyLoadingException("Certificate not found", type, nodeId);
        }
        if (certificate instanceof X509Certificate x509) {
            return x509;
        }
        throw new KeyLoadingException("Certificate is not an instance of X509Certificate", type, nodeId);
    }
}
