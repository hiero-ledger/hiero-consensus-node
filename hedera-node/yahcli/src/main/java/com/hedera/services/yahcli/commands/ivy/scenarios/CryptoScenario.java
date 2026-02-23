// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy.scenarios;

public class CryptoScenario {
    public static String SENDER_NAME = "sender";
    public static String RECEIVER_NAME = "receiver";
    public static String NOVEL_ACCOUNT_NAME = "novelAccount";

    Long sender;
    Long receiver;

    public Long getSender() {
        return sender;
    }

    public void setSender(Long sender) {
        this.sender = sender;
    }

    public Long getReceiver() {
        return receiver;
    }

    public void setReceiver(Long receiver) {
        this.receiver = receiver;
    }
}
