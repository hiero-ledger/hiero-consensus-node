// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for verifying that the network roster correctly incorporates node parameters into the network roster.
 */
public class NetworkRosterTests {
    /**
     * Tests that when nodes are created with overridden keys and certificates, the network roster correctly reflects
     * those certificates.
     */
    @ParameterizedTest
    @EnumSource(SigningSchema.class)
    void testCertificates(@NonNull final SigningSchema schema)
            throws NoSuchAlgorithmException, KeyGeneratingException, NoSuchProviderException,
                    CertificateEncodingException {
        final TurtleTestEnvironment env = new TurtleTestEnvironment();
        try {
            // Create a network with 2 nodes
            final Network network = env.network();
            final List<Node> nodes = network.addNodes(2);
            final Node node0 = nodes.get(0);
            final Node node1 = nodes.get(1);

            // Override the keys and certs for each node
            final SecureRandom secureRandom = SecureRandom.getInstanceStrong();
            final KeysAndCerts kac0 =
                    KeysAndCertsGenerator.generate(node0.selfId(), schema, secureRandom, secureRandom);
            final KeysAndCerts kac1 =
                    KeysAndCertsGenerator.generate(node0.selfId(), schema, secureRandom, secureRandom);
            node0.keysAndCerts(kac0);
            node1.keysAndCerts(kac1);

            // Start the network so that the roster is created
            network.start();

            // Verify that the roster uses the overridden certificates
            final Roster roster = network.roster();
            assertThat(roster.rosterEntries().size()).isEqualTo(2);
            assertThat(roster.rosterEntries().get(0).gossipCaCertificate())
                    .isEqualTo(Bytes.wrap(kac0.sigCert().getEncoded()));
            assertThat(roster.rosterEntries().get(1).gossipCaCertificate())
                    .isEqualTo(Bytes.wrap(kac1.sigCert().getEncoded()));
        } finally {
            env.destroy();
        }
    }
}
