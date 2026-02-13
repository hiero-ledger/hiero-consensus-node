// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CANDIDATE_ROSTER_JSON;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.internal.network.Network;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.OnlyRoster;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class SubProcessNetworkPortLayoutTest {
    @Test
    void addNodeUsesPerNetworkPortBase() throws Exception {
        final var netA = SubProcessNetwork.newIsolatedNetwork("NET_A", 4, 0, 0, 27400);
        final var netB = SubProcessNetwork.newIsolatedNetwork("NET_B", 4, 0, 0, 28400);
        try {
            netA.nodes().forEach(node -> node.initWorkingDir(netA.genesisNetwork()));
            final var node0 = netA.getRequiredNode(byNodeId(0));
            final var base = node0.metadata();
            final var node0Dir = base.workingDirOrThrow();
            Files.createDirectories(node0Dir);
            final var candidateRoster = WorkingDirUtils.networkFrom(netA.genesisNetwork(), OnlyRoster.YES);
            Files.writeString(node0Dir.resolve(CANDIDATE_ROSTER_JSON), Network.JSON.toJSON(candidateRoster));

            netA.addNode(4);

            final var node4 = netA.getRequiredNode(byNodeId(4));
            final var meta4 = node4.metadata();

            assertEquals(base.grpcPort() + 4 * 2, meta4.grpcPort());
            assertEquals(base.grpcNodeOperatorPort() + 4, meta4.grpcNodeOperatorPort());
            assertEquals(base.internalGossipPort() + 4 * 2, meta4.internalGossipPort());
            assertEquals(base.externalGossipPort() + 4 * 2, meta4.externalGossipPort());
            assertEquals(base.prometheusPort() + 4, meta4.prometheusPort());
            assertEquals(base.debugPort() + 4, meta4.debugPort());
        } finally {
            netA.terminate();
            netB.terminate();
        }
    }
}
