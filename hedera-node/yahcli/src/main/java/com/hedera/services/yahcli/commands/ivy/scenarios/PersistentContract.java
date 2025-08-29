// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.scenarios;

public class PersistentContract {
    Long num;
    Long bytecode;
    String source;
    Integer luckyNo;

    public Long getNum() {
        return num;
    }

    public void setNum(Long num) {
        this.num = num;
    }

    public Integer getLuckyNo() {
        return luckyNo;
    }

    public void setLuckyNo(Integer luckyNo) {
        this.luckyNo = luckyNo;
    }

    public Long getBytecode() {
        return bytecode;
    }

    public void setBytecode(Long bytecode) {
        this.bytecode = bytecode;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
