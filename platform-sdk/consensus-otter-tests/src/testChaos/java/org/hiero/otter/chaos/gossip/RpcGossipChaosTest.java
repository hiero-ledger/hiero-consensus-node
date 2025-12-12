// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.chaos.gossip;

import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import com.swirlds.common.merkle.synchronization.config.ReconnectConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.chaosbot.ChaosBot;
import org.hiero.otter.fixtures.chaosbot.ChaosBotConfiguration;

public class RpcGossipChaosTest {

    @OtterTest(requires = Capability.RECONNECT)
    void chaosTest(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        network.addNodes(7);
        network.withConfigValue(ReconnectConfig_.MAXIMUM_RECONNECT_FAILURES_BEFORE_SHUTDOWN, Integer.MAX_VALUE);
        // we probably don't really want to test old sync code, given it is slated for removal very soon
        // network.withConfigValue(ProtocolConfig_.RPC_GOSSIP, "false");
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        network.start();

        final ChaosBot bot = env.createChaosBot(new ChaosBotConfiguration(
                Duration.ofSeconds(130), Duration.ofSeconds(150), null, List.of(new GossipChaosExperiment())));
        bot.runChaos(Duration.ofMinutes(10L));
    }
}
