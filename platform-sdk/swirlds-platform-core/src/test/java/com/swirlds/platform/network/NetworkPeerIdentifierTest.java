// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static com.swirlds.platform.crypto.CryptoStatic.loadKeys;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.crypto.KeyCertPurpose;
import com.swirlds.platform.crypto.KeyGeneratingException;
import com.swirlds.platform.crypto.KeyLoadingException;
import com.swirlds.platform.crypto.PublicStores;
import java.net.URISyntaxException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hiero.base.crypto.internal.DetRandomProvider;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetworkPeerIdentifierTest {
    private static final Pattern NODE_NAME_PATTERN = Pattern.compile("^node(\\d+)$");

    final PlatformContext platformContext = mock(PlatformContext.class);
    List<PeerInfo> peerInfoList = null;
    PublicStores publicStores = null;

    @BeforeEach
    void setUp() throws URISyntaxException, KeyLoadingException, KeyStoreException {
        when(platformContext.getTime()).thenReturn(Time.getCurrent());
        final KeyStore publicKeys = loadKeys(
                ResourceLoader.getFile("preGeneratedKeysAndCerts/").resolve("publicMainnet.pfx"),
                "password".toCharArray());
        final Set<String> names = new HashSet<>();
        publicKeys.aliases().asIterator().forEachRemaining(s -> {
            // there's a weirdly named 'k-stream' alias inside the pfx, we exclude it
            if (!s.equals("k-stream")) {
                names.add(s.split("-")[1]);
            }
        });

        final var ids = names.stream()
                .map(name -> {
                    final Matcher nameMatcher = NODE_NAME_PATTERN.matcher(name);
                    if (!nameMatcher.matches()) {
                        throw new RuntimeException("Invalid node name " + name);
                    }
                    final int id = Integer.parseInt(nameMatcher.group(1)) - 1;
                    return NodeId.of(id);
                })
                .toList();

        publicStores = PublicStores.fromAllPublic(publicKeys, ids);

        peerInfoList = new ArrayList<>();
        ids.forEach(id -> {
            final PeerInfo peer;
            try {
                peer = new PeerInfo(
                        id,
                        "127.0.0.1",
                        12345,
                        Objects.requireNonNull(publicStores.getCertificate(KeyCertPurpose.SIGNING, id)));
            } catch (final KeyLoadingException e) {
                throw new RuntimeException(e);
            }
            peerInfoList.add(peer);
        });
    }

    /**
     * Tests that given a list of valid Swirlds production certificates (like the type used in mainnet),
     * {@link NetworkPeerIdentifier#identifyTlsPeer(Certificate[])} is able to successfully identify a matching
     * peer.
     */
    @Test
    void testExtractPeerInfoWorksForMainnet() throws KeyStoreException, InvalidAlgorithmParameterException {
        final PKIXParameters params = new PKIXParameters(publicStores.agrTrustStore());
        final Set<TrustAnchor> trustAnchors = params.getTrustAnchors();
        final NetworkPeerIdentifier peerIdentifier = new NetworkPeerIdentifier(platformContext, peerInfoList);
        final Set<PeerInfo> matches = new HashSet<>();

        final Certificate[] certificates =
                trustAnchors.stream().map(TrustAnchor::getTrustedCert).toArray(Certificate[]::new);
        for (final Certificate certificate : certificates) {
            final PeerInfo matchedPeer =
                    peerIdentifier.identifyTlsPeer(List.of(certificate).toArray(Certificate[]::new));
            Assertions.assertNotNull(matchedPeer);
            matches.add(matchedPeer);
        }
        // ensure we matched exactly the set of nodes in the original peer list
        Assertions.assertEquals(matches, new HashSet<>(peerInfoList));
    }

    /**
     * Asserts that identifyTlsPeer returns the peer whose certificate matches the passed in certificate
     */
    @Test
    void testReturnsIntendedPeerForMainnet() throws KeyStoreException {
        final NetworkPeerIdentifier peerIdentifier = new NetworkPeerIdentifier(platformContext, peerInfoList);
        // pick a node's agreement certificate, node20
        final Certificate certUnderTest = publicStores.agrTrustStore().getCertificate("a-node20");

        final PeerInfo matchedPeer =
                peerIdentifier.identifyTlsPeer(List.of(certUnderTest).toArray(Certificate[]::new));

        Assertions.assertNotNull(matchedPeer);
        // assert the peer we got back is node20
        Assertions.assertEquals("node20", matchedPeer.nodeName());
    }

    /**
     * Asserts that when none of the peer's certificate match, identifyTlsPeer returns null
     */
    @Test
    void testIdentifyTlsPeerReturnsNull()
            throws NoSuchAlgorithmException, NoSuchProviderException, KeyGeneratingException {

        final SecureRandom secureRandom = DetRandomProvider.getDetRandom();

        final KeyPairGenerator rsaKeyGen = KeyPairGenerator.getInstance("RSA");
        rsaKeyGen.initialize(3072, secureRandom);
        final KeyPair rsaKeyPair1 = rsaKeyGen.generateKeyPair();

        final String name = "CN=Bob";
        final X509Certificate rsaCert =
                CryptoStatic.generateCertificate(name, rsaKeyPair1, name, rsaKeyPair1, secureRandom);
        final Certificate[] certificates = new Certificate[] {rsaCert};

        final PeerInfo matchedPeer =
                new NetworkPeerIdentifier(platformContext, peerInfoList).identifyTlsPeer(certificates);
        Assertions.assertNull(matchedPeer);
    }
}
