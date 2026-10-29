// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterEntryWrapper;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.junit.jupiter.api.Test;

class UtilitiesTest {

    /**
     * A peer whose roster entry has no usable gossip certificate must be left out of the peer list instead of
     * producing a {@link PeerInfo} without a signing certificate.
     */
    @Test
    void peersWithoutUsableCertificateAreExcluded() {
        final Random random = RandomUtils.getRandomPrintSeed();
        final RosterWrapper validRoster = RosterWrapperFactory.randomRoster(random, 4);
        final NodeId selfId = validRoster.nodeIdAtIndex(0);
        final NodeId peerWithoutCertificate = validRoster.nodeIdAtIndex(1);

        final List<RosterEntry> entries = validRoster.rosterEntries().stream()
                .map(RosterEntryWrapper::toPbj)
                .map(entry -> entry.nodeId() == peerWithoutCertificate.id()
                        ? entry.copyBuilder().gossipCaCertificate(Bytes.EMPTY).build()
                        : entry)
                .toList();
        final RosterWrapper roster = RosterWrapperFactory.createRosterWrapper(entries);

        final List<PeerInfo> peers = Utilities.createPeerInfoList(roster, selfId);

        assertThat(peers)
                .extracting(PeerInfo::nodeId)
                .containsExactly(validRoster.nodeIdAtIndex(2), validRoster.nodeIdAtIndex(3));
        assertThat(peers).extracting(PeerInfo::signingCertificate).doesNotContainNull();
    }
}
