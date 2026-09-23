// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.test.fixtures.crypto.PreGeneratedX509Certs;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RosterEntryWrapper} class.
 */
class RosterEntryWrapperTest {

    private static RosterEntryWrapper wrap(final Bytes gossipCaCertificate) {
        return new RosterEntryWrapper(RosterEntry.newBuilder()
                .nodeId(7)
                .gossipCaCertificate(gossipCaCertificate)
                .build());
    }

    /**
     * Test that a valid gossip CA certificate is correctly decoded and returned by the wrapper.
     *
     * @throws CertificateEncodingException if there is an error encoding the certificate
     */
    @Test
    void gossipCaCertificateIsDecoded() throws CertificateEncodingException {
        final X509Certificate certificate = PreGeneratedX509Certs.getSigCert(7);

        final RosterEntryWrapper wrapper = wrap(Bytes.wrap(certificate.getEncoded()));

        assertEquals(NodeId.of(7), wrapper.nodeId());
        assertEquals(certificate, wrapper.gossipCaCertificate());
    }

    /**
     * A roster entry without a usable certificate must still be wrappable: some tests wrap rosters they never
     * gossips with, and an unusable certificate is reported as {@code null} rather than thrown.
     */
    @Test
    void unusableGossipCaCertificateIsReportedAsNull() {
        final RosterEntryWrapper wrapper1 = wrap(Bytes.EMPTY);
        final RosterEntryWrapper wrapper2 = wrap(Bytes.wrap(new byte[] {1, 2, 3}));

        assertThat(wrapper1.gossipCaCertificate()).isNull();
        assertThat(wrapper2.gossipCaCertificate()).isNull();
    }
}
