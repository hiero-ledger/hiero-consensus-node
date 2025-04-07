// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.remote;

import com.hedera.services.bdd.spec.props.NodeConnectInfo;

/**
 * Bean for specifying a remote node.
 */
public class RemoteNodeSpec {
    private long id;
    private long accountNum;
    private int port;
    private String host;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getAccountNum() {
        return accountNum;
    }

    public void setAccountNum(long accountNum) {
        this.accountNum = accountNum;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    /**
     * Creates a {@link NodeConnectInfo} representing this node in the given shard and realm.
     * @param shard the shard number
     * @param realm the realm number
     * @return a {@link NodeConnectInfo} object representing this node
     */
    public NodeConnectInfo asNodeConnectInfo(final long shard, final long realm) {
        return new NodeConnectInfo(String.format("%s:%d:%d.%d.%d", host, port, shard, realm, accountNum));
    }
}
