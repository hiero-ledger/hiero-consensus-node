// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Node's ephemeral CLPR mTLS leaf identity: the certificate it presents at the TLS handshake and
 * the matching private key. The two always travel together — a node either has a complete leaf identity
 * (mTLS enabled) or none (plaintext) — so they are modeled as one value rather than a pair of nullable
 * fields that callers must check in tandem.
 *
 * @param certificate the ephemeral Ed25519 leaf certificate to present at the handshake
 * @param privateKey the matching leaf private key
 */
public record ClprLeafCredentials(
        @NonNull X509Certificate certificate, @NonNull PrivateKey privateKey) {
    public ClprLeafCredentials {
        requireNonNull(certificate);
        requireNonNull(privateKey);
    }
}
