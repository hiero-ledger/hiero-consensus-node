// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.validation.domain;

public class Node {
    private long account;
    private String ipv4Addr;

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

    @Override
    public String toString() {
        return String.format("%s:50211:0.0.%d", ipv4Addr, account);
    }
}
