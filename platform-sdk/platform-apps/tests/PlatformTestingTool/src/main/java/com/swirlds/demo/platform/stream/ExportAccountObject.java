// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.stream;

public class ExportAccountObject {
    public ExportAccountObject(long shardNum, long realmNum, long accountNum, long balance) {
        this.shardNum = shardNum;
        this.realmNum = realmNum;
        this.accountNum = accountNum;
        this.balance = balance;
    }

    private long shardNum;
    private long realmNum;
    private long accountNum;
    private long balance;

    public long getShardNum() {
        return shardNum;
    }

    public void setShardNum(long shardNum) {
        this.shardNum = shardNum;
    }

    public long getRealmNum() {
        return realmNum;
    }

    public void setRealmNum(long realmNum) {
        this.realmNum = realmNum;
    }

    public long getAccountNum() {
        return accountNum;
    }

    public void setAccountNum(long accountNum) {
        this.accountNum = accountNum;
    }

    public long getBalance() {
        return balance;
    }

    public void setBalance(long balance) {
        this.balance = balance;
    }
}
