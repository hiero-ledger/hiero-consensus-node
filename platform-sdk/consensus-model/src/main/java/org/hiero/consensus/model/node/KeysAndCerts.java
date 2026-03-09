// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.node;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.KeyPair;
import java.security.cert.X509Certificate;

/**
 * An instantiation of this class holds all the keys and CSPRNG state for one Platform object. No other class should
 * store any secret or private key/seed information.
 *
 * @param sigKeyPair       the RSA signing key pair (used for state signing and TLS trust chain)
 * @param agrKeyPair       the EC P-384 agreement key pair (used for TLS)
 * @param sigCert          the X.509 certificate for the RSA signing key
 * @param agrCert          the X.509 certificate for the agreement key (signed by RSA key)
 * @param eventSigKeyPair  the Ed25519 key pair for event signing (null if not available)
 */
public record KeysAndCerts(
        KeyPair sigKeyPair,
        KeyPair agrKeyPair,
        X509Certificate sigCert,
        X509Certificate agrCert,
        @Nullable KeyPair eventSigKeyPair) {

    /**
     * Backward-compatible constructor without Ed25519 event signing key pair.
     */
    public KeysAndCerts(KeyPair sigKeyPair, KeyPair agrKeyPair, X509Certificate sigCert, X509Certificate agrCert) {
        this(sigKeyPair, agrKeyPair, sigCert, agrCert, null);
    }
}
