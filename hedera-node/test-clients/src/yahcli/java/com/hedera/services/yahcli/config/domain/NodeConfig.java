// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.config.domain;

public class NodeConfig {
    private int id;
    private long account;
    private String ipv4Addr;
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
        return String.format("%s:%d.%d.%d#%d", ipv4Addr, shard, realm, account, id);
    }
}
