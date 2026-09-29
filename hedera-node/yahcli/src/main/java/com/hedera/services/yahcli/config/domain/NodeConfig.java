// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.config.domain;

public class NodeConfig {
    private int id;
    private long account;
    private String ipv4Addr;
    private Integer port;
    private long shard;
    private long realm;

    public long getAccount() {
        return account;
    }

    public void setAccount(long account) {
        this.account = account;
    }

    public String getIpv4Addr() {
        return ipv4Addr;
    }

    public void setIpv4Addr(String ipv4Addr) {
        this.ipv4Addr = ipv4Addr;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public void setShard(long shard) {
        this.shard = shard;
    }

    public void setRealm(long realm) {
        this.realm = realm;
    }

    @Override
    public String toString() {
        if (port != null) {
            return String.format("%s:%d:%d.%d.%d#%d", ipv4Addr, port, shard, realm, account, id);
        }
        return String.format("%s:%d.%d.%d#%d", ipv4Addr, shard, realm, account, id);
    }
}
