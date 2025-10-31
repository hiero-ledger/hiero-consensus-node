// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc;

import com.hedera.pbj.runtime.io.buffer.Bytes;

public record ItemData(Type type, Bytes bytes, long location) {

    public enum Type {
        P2KV,
        P2H,
        K2P,
        TERMINATOR
    }

    public static ItemData poisonPill() {
        return new ItemData(Type.TERMINATOR, Bytes.EMPTY, -1L);
    }

    public boolean isPoisonPill() {
        return type == Type.TERMINATOR;
    }
}
