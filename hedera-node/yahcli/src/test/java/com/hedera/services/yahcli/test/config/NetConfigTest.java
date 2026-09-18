// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.services.bdd.spec.props.NodeConnectInfo;
import com.hedera.services.yahcli.config.domain.NetConfig;
import com.hedera.services.yahcli.config.domain.NodeConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetConfigTest {
    /**
     * Reproduces the mainnet topology where DAB nodeIds have gaps (retired nodes 2 and 3),
     * so accounts are non-sequential: 0→3, 1→4, 4→7. Without stripping the #id suffix,
     * NodeConnectInfo falls back to sequential stub accounts (3, 4, 5) causing account 7
     * to be misrouted to the wrong gRPC endpoint.
     */
    @Test
    void toSpecPropertiesStripsNodeIdSuffixForCorrectAccountParsing() {
        final var netConfig = new NetConfig();
        netConfig.setNodes(List.of(
                nodeConfig(0, 3, "35.237.208.135"),
                nodeConfig(1, 4, "35.236.222.232"),
                nodeConfig(4, 7, "34.94.94.224")));

        final var props = netConfig.toSpecProperties();
        final var nodesValue = props.get("nodes");

        assertThat(nodesValue).doesNotContain("#");

        // Verify NodeConnectInfo can parse each entry with the correct account
        final var entries = nodesValue.split(",");
        assertThat(entries).hasSize(3);
        assertThat(new NodeConnectInfo(entries[0]).getAccount().getAccountNum()).isEqualTo(3L);
        assertThat(new NodeConnectInfo(entries[1]).getAccount().getAccountNum()).isEqualTo(4L);
        assertThat(new NodeConnectInfo(entries[2]).getAccount().getAccountNum()).isEqualTo(7L);
    }

    @Test
    void toSpecPropertiesFormsCorrectNodeString() {
        final var netConfig = new NetConfig();
        netConfig.setNodes(List.of(nodeConfig(4, 7, "34.94.94.224")));

        final var nodesValue = netConfig.toSpecProperties().get("nodes");

        assertThat(nodesValue).isEqualTo("34.94.94.224:0.0.7");
    }

    private static NodeConfig nodeConfig(int id, long account, String ip) {
        final var n = new NodeConfig();
        n.setId(id);
        n.setAccount(account);
        n.setIpv4Addr(ip);
        return n;
    }
}
