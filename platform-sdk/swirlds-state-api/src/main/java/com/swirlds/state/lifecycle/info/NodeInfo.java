// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle.info;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import org.hiero.consensus.model.utility.CommonUtils;

/**
 * Summarizes useful information about the nodes in the AddressBook from the Platform. In
 * the future, there may be events that require re-reading the book; but at present nodes may treat
 * the initializing book as static.
 */
public interface NodeInfo {

    /**
     * Convenience method to check if this node has zero weight.
     *
     * @return whether this node has zero weight
     */
    default boolean zeroWeight() {
        return weight() == 0;
    }

    /**
     * Gets the node ID. This is a separate identifier from the node's account. This IS NOT IN ANY WAY related to the
     * node AccountID.
     *
     * <p>FUTURE: Should we expose NodeId from the platform? It would make this really hard to misuse as the node
     * accountID, whereas as a long, it could be.
     *
     * @return The node ID.
     */
    long nodeId();

    /**
     * Returns the account ID corresponding with this node.
     *
     * @return the account ID of the node.
     * @throws IllegalStateException if the book did not contain the id, or was missing an account for the id
     */
    AccountID accountId();

    /**
     * The stake weight of this node.
     * @return the stake weight
     */
    long weight();

    /**
     * The signing x509 certificate bytes of the member
     * @return the signing x509 certificate bytes
     */
    Bytes sigCertBytes();

    /**
     * The list of gossip endpoints of this node, as known by the internal and external worlds.
     * This has an IP address and port.
     *
     * @return The host name (IP Address) of this node
     */
    List<ServiceEndpoint> gossipEndpoints();

    /**
     * The list of HAPI endpoints of this node.
     */
    @NonNull
    List<ServiceEndpoint> hapiEndpoints();

    /**
     * The gossip X.509 certificate of this node.
     * @return the gossip X.509 certificate
     * @throws IllegalStateException if the certificate could not be extracted
     */
    default X509Certificate sigCert() {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) certificateFactory.generateCertificate(
                    new ByteArrayInputStream(sigCertBytes().toByteArray()));
        } catch (CertificateException e) {
            throw new IllegalStateException("Error extracting public key from certificate", e);
        }
    }

    /**
     * The public key of this node, as a hex-encoded string. It is extracted from the certificate bytes.
     *
     * @return the public key
     */
    default String hexEncodedPublicKey() {
        return CommonUtils.hex(sigCert().getPublicKey().getEncoded());
    }
}
