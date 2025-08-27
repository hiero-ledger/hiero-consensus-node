// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.remote;

import com.hedera.services.bdd.spec.props.NodeConnectInfo;
import java.util.List;

/**
 * Bean for specifying a remote network.
 */
public class RemoteNetworkSpec {
    private long shard;
    private long realm;
    private List<RemoteNodeSpec> nodes;

    public long getShard() {
        return shard;
    }

    public void setShard(long shard) {
        this.shard = shard;
    }

    public long getRealm() {
        return realm;
    }

    public void setRealm(long realm) {
        this.realm = realm;
    }

    public List<RemoteNodeSpec> getNodes() {
        return nodes;
    }

    public void setNodes(List<RemoteNodeSpec> nodes) {
        this.nodes = nodes;
    }

    /**
     * Returns a list of {@link NodeConnectInfo} objects representing the nodes in this network.
     */
    public List<NodeConnectInfo> connectInfos() {
        return nodes.stream().map(node -> node.asNodeConnectInfo(shard, realm)).toList();
    }

    @Override
    public String toString() {
        return "RemoteNetworkSpec{" + "shard=" + shard + ", realm=" + realm + ", nodes=" + connectInfos() + '}';
    }
}
