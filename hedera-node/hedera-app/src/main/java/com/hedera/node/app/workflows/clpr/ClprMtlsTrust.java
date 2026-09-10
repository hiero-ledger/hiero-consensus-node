// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * Factory class for trust managers implementing the CLPR mTLS two-tier model. A peer's
 * ECDSA P-384 CA cert is the trust anchor, obtained from {@code ClprEndpoint.tls_certificate} and
 * pinned by direct comparison; the ephemeral Ed25519 leaf presented at the handshake is accepted
 * only if it was signed by a pinned CA and is not itself a CA cert. Hostname verification is skipped
 * — CLPR endpoints are identified by their pinned CA, not by a DNS name.
 *
 * <p>Both directions share the same leaf-validation logic:
 * <ul>
 *   <li>{@link #pinnedServerTrustManager(X509Certificate)} — outbound (client) validation of the
 *       peer <i>server</i>'s leaf against the single CA pinned for that peer.</li>
 *   <li>{@link #peerSetClientTrustManager(Supplier)} — inbound (server) validation of a connecting
 *       <i>client</i>'s leaf against the single known peer CA matching the leaf's issuer DN.</li>
 * </ul>
 */
public final class ClprMtlsTrust {
    private ClprMtlsTrust() {}

    /**
     * Outbound trust manager: accepts the peer server's leaf only if it was signed by {@code pinnedCa}
     * and is not itself a CA cert. Used by the outbound CLPR sync/discovery client.
     */
    @NonNull
    public static X509ExtendedTrustManager pinnedServerTrustManager(@NonNull final X509Certificate pinnedCa) {
        requireNonNull(pinnedCa);
        return new X509ExtendedTrustManager() {
            @Override
            public void checkServerTrusted(final X509Certificate[] chain, final String authType)
                    throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
                    throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
                    throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkClientTrusted(final X509Certificate[] chain, final String authType)
                    throws CertificateException {
                throw new CertificateException("Client trust is not supported by the outbound CLPR client");
            }

            @Override
            public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
                    throws CertificateException {
                throw new CertificateException("Client trust is not supported by the outbound CLPR client");
            }

            @Override
            public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
                    throws CertificateException {
                throw new CertificateException("Client trust is not supported by the outbound CLPR client");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[] {pinnedCa};
            }

            private void verify(final X509Certificate[] chain) throws CertificateException {
                validateLeaf(chain);
                if (!signedByAuthority(chain[0], pinnedCa)) {
                    throw new CertificateException("CLPR peer leaf cert not signed by pinned CA");
                }
            }
        };
    }

    /**
     * Inbound trust manager for the dedicated mTLS sync listener: accepts a connecting client's leaf
     * only if it was signed by the known peer CA whose subject DN matches the leaf's issuer DN, and is
     * not itself a CA cert. The supplier returns the CA index keyed by subject DN and is read on every
     * handshake so newly-learned peer CAs take effect without rebuilding the server. A peer whose CA this
     * node has not yet learned (via ledger config, channel completion, or disk rehydration) is rejected
     * at the handshake and retries. Keying by issuer DN replaces an O(n) scan of all CAs with a direct
     * lookup, then a single signature check against the matching CA(s).
     */
    @NonNull
    public static X509ExtendedTrustManager peerSetClientTrustManager(
            @NonNull final Supplier<Map<X500Principal, List<X509Certificate>>> peerCasByIssuer) {
        requireNonNull(peerCasByIssuer);
        return new X509ExtendedTrustManager() {

            @Override
            public void checkClientTrusted(final X509Certificate[] chain, final String authType)
                    throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
                    throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
                    throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] chain, final String authType)
                    throws CertificateException {
                throw new CertificateException("Server trust is not supported by the inbound CLPR sync listener");
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
                    throws CertificateException {
                throw new CertificateException("Server trust is not supported by the inbound CLPR sync listener");
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
                    throws CertificateException {
                throw new CertificateException("Server trust is not supported by the inbound CLPR sync listener");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                final var casByIssuer = peerCasByIssuer.get();
                if (casByIssuer == null) {
                    return new X509Certificate[0];
                }
                return casByIssuer.values().stream().flatMap(List::stream).toArray(X509Certificate[]::new);
            }

            private void verify(final X509Certificate[] chain) throws CertificateException {
                // By the X509TrustManager contract, the chain argument is the peer's certificate path with a fixed
                // ordering: chain[0] is the end-entity (leaf) certificate — the identity being authenticated — and
                // chain[1..] are the intermediate CA certs the peer chose to send toward a root.
                // The party on the other end of the socket is always chain[0]. So the only certificate whose identity
                // we care about is chain[0].
                validateLeaf(chain);
                final var leafCertificate = chain[0];
                final var casByIssuer = peerCasByIssuer.get();
                final var issuer = leafCertificate.getIssuerX500Principal();
                final var candidates = casByIssuer == null ? null : casByIssuer.get(issuer);
                if (candidates == null || candidates.stream().noneMatch(ca -> signedByAuthority(leafCertificate, ca))) {
                    throw new CertificateException(
                            "CLPR client leaf cert not signed by a known peer CA for issuer " + issuer);
                }
            }
        };
    }

    /** Rejects an empty chain or a CA cert presented as a leaf (basicConstraints &gt;= 0 means it is a CA). */
    private static void validateLeaf(final X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Empty certificate chain from CLPR peer");
        }
        if (chain[0].getBasicConstraints() >= 0) {
            throw new CertificateException("CLPR peer presented a CA cert as its leaf certificate — rejected");
        }
    }

    /**
     * Returns {@code true} iff {@code leaf} was signed by {@code ca}'s public key. Validation is
     * intentionally signature-only: no validity-period or revocation check. Leaves are per-process,
     * held only in memory, never persisted, and carry a fixed long-lived validity window, so expiry
     * is a non-issue, and pinning the signing CA is the whole of the trust decision.
     */
    private static boolean signedByAuthority(final X509Certificate leaf, final X509Certificate ca) {
        try {
            leaf.verify(ca.getPublicKey());
            return true;
        } catch (final Exception e) {
            return false;
        }
    }
}
