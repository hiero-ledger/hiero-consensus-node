// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the two {@link ClprMtlsTrust} trust managers, driving the trust-manager methods
 * directly (no TLS handshake).
 */
class ClprMtlsTrustTest {

    private static final String AUTH_TYPE = "EC";

    private static ClprTestCa ca;
    private static ClprTestCa otherCa;
    private static X509Certificate leaf; // signed by ca
    private static X509Certificate foreignLeaf; // signed by otherCa

    @BeforeAll
    static void certs() throws Exception {
        ca = new ClprTestCa("clpr-ca");
        otherCa = new ClprTestCa("other-clpr-ca");
        leaf = ca.signLeaf("leaf").cert();
        foreignLeaf = otherCa.signLeaf("foreign-leaf").cert();
    }

    private static X509Certificate[] chain(final X509Certificate... certs) {
        return certs;
    }

    @Nested
    @DisplayName("pinnedServerTrustManager (outbound)")
    class PinnedServer {
        private final X509ExtendedTrustManager tm = ClprMtlsTrust.pinnedServerTrustManager(ca.caCert());

        @Test
        @DisplayName("accepts a server leaf signed by the pinned CA")
        void acceptsLeafSignedByPinnedCa() {
            assertThatCode(() -> tm.checkServerTrusted(chain(leaf), AUTH_TYPE)).doesNotThrowAnyException();
            assertThatCode(() -> tm.checkServerTrusted(chain(leaf), AUTH_TYPE, (Socket) null))
                    .doesNotThrowAnyException();
            assertThatCode(() -> tm.checkServerTrusted(chain(leaf), AUTH_TYPE, (SSLEngine) null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a server leaf signed by a different CA")
        void rejectsLeafSignedByOtherCa() {
            assertThatThrownBy(() -> tm.checkServerTrusted(chain(foreignLeaf), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName("rejects a CA cert presented as a leaf")
        void rejectsCaCertAsLeaf() {
            assertThatThrownBy(() -> tm.checkServerTrusted(chain(ca.caCert()), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName("rejects an empty or null chain")
        void rejectsEmptyOrNullChain() {
            assertThatThrownBy(() -> tm.checkServerTrusted(chain(), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
            assertThatThrownBy(() -> tm.checkServerTrusted(null, AUTH_TYPE)).isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName("never trusts a client — the outbound manager rejects on every checkClientTrusted overload")
        void neverTrustsAClient() {
            assertThatThrownBy(() -> tm.checkClientTrusted(chain(leaf), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
            assertThatThrownBy(() -> tm.checkClientTrusted(chain(leaf), AUTH_TYPE, (Socket) null))
                    .isInstanceOf(CertificateException.class);
            assertThatThrownBy(() -> tm.checkClientTrusted(chain(leaf), AUTH_TYPE, (SSLEngine) null))
                    .isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName("accepted issuers is exactly the pinned CA")
        void acceptedIssuersIsThePinnedCa() {
            assertThat(tm.getAcceptedIssuers()).containsExactly(ca.caCert());
        }
    }

    @Nested
    @DisplayName("peerSetClientTrustManager (inbound)")
    class PeerSet {
        private final Map<X500Principal, List<X509Certificate>> peerCasByIssuer = new HashMap<>();
        private final X509ExtendedTrustManager tm = ClprMtlsTrust.peerSetClientTrustManager(() -> peerCasByIssuer);

        /** Adds a CA to the issuer-keyed index under its subject DN, matching production's cache shape. */
        private void trust(final X509Certificate caCert) {
            peerCasByIssuer
                    .computeIfAbsent(caCert.getSubjectX500Principal(), k -> new ArrayList<>())
                    .add(caCert);
        }

        @BeforeEach
        void beforeEach() {
            trust(ca.caCert());
        }

        @Test
        @DisplayName("accepts a client leaf signed by a CA in the set (every checkClientTrusted overload)")
        void acceptsLeafSignedByKnownCa() {
            assertThatCode(() -> tm.checkClientTrusted(chain(leaf), AUTH_TYPE)).doesNotThrowAnyException();
            assertThatCode(() -> tm.checkClientTrusted(chain(leaf), AUTH_TYPE, (Socket) null))
                    .doesNotThrowAnyException();
            assertThatCode(() -> tm.checkClientTrusted(chain(leaf), AUTH_TYPE, (SSLEngine) null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a client leaf whose CA is not in the set")
        void rejectsLeafSignedByUnknownCa() {
            assertThatThrownBy(() -> tm.checkClientTrusted(chain(foreignLeaf), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName("rejects a CA cert presented as a leaf, even when that CA is trusted")
        void rejectsCaCertAsLeaf() {
            assertThatThrownBy(() -> tm.checkClientTrusted(chain(ca.caCert()), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName("rejects an empty or null chain")
        void rejectsEmptyOrNullChain() {
            assertThatThrownBy(() -> tm.checkClientTrusted(chain(), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
            assertThatThrownBy(() -> tm.checkClientTrusted(null, AUTH_TYPE)).isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName("never trusts a server — the inbound manager rejects on every checkServerTrusted overload")
        void neverTrustsAServer() {
            assertThatThrownBy(() -> tm.checkServerTrusted(chain(leaf), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
            assertThatThrownBy(() -> tm.checkServerTrusted(chain(leaf), AUTH_TYPE, (Socket) null))
                    .isInstanceOf(CertificateException.class);
            assertThatThrownBy(() -> tm.checkServerTrusted(chain(leaf), AUTH_TYPE, (SSLEngine) null))
                    .isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName("accepted issuers reflects the current index")
        void acceptedIssuersReflectsTheSet() {
            trust(otherCa.caCert());
            assertThat(tm.getAcceptedIssuers()).containsExactlyInAnyOrder(ca.caCert(), otherCa.caCert());
        }

        @Test
        @DisplayName("the supplier is re-read on every check, so a newly-added CA takes effect immediately")
        void supplierIsReadLive() {
            // The foreign leaf's CA is not yet known, so it is rejected.
            assertThatThrownBy(() -> tm.checkClientTrusted(chain(foreignLeaf), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
            // Learn the CA — the same manager instance now accepts the same leaf.
            trust(otherCa.caCert());
            assertThatCode(() -> tm.checkClientTrusted(chain(foreignLeaf), AUTH_TYPE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null peer-CA index rejects everything and yields no accepted issuers")
        void nullPeerCaSet() {
            final X509ExtendedTrustManager nullTm = ClprMtlsTrust.peerSetClientTrustManager(() -> null);
            assertThat(nullTm.getAcceptedIssuers()).isEmpty();
            assertThatThrownBy(() -> nullTm.checkClientTrusted(chain(leaf), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName("rejects a client leaf whose issuer DN is absent from the index, even if other CAs are known")
        void rejectsLeafWhoseIssuerHasNoBucket() {
            // Index holds only `ca` (from beforeEach). The foreign leaf's issuer DN keys nothing, so the
            // O(1) lookup misses and the leaf is rejected without scanning unrelated CAs.
            assertThatThrownBy(() -> tm.checkClientTrusted(chain(foreignLeaf), AUTH_TYPE))
                    .isInstanceOf(CertificateException.class);
        }

        @Test
        @DisplayName(
                "when two CAs share a subject DN, a leaf signed by either is accepted (bucket is verified in full)")
        void acceptsLeafSignedByAnyCaInSharedDnBucket() throws Exception {
            // A second CA with the SAME subject DN as `ca` but a different key pair — they collide on the
            // issuer-lookup key, so both live in one bucket and each candidate must be tried.
            final var sameDnCa = new ClprTestCa("clpr-ca");
            final var sameDnLeaf = sameDnCa.signLeaf("leaf").cert();
            trust(sameDnCa.caCert());
            assertThatCode(() -> tm.checkClientTrusted(chain(sameDnLeaf), AUTH_TYPE))
                    .doesNotThrowAnyException();
            // The original leaf (signed by `ca`, also in the same bucket) still verifies too.
            assertThatCode(() -> tm.checkClientTrusted(chain(leaf), AUTH_TYPE)).doesNotThrowAnyException();
        }
    }
}
