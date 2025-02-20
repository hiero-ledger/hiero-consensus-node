// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import com.hedera.cryptography.tss.api.TssMessage;

public record FakeTssMessage(byte[] bytes) implements TssMessage {

    @Override
    public byte[] toBytes() {
        return bytes;
    }
}
