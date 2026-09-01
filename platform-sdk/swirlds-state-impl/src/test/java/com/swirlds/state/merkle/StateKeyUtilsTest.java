// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class StateKeyUtilsTest {

    @Test
    void writesTypedKeyDirectlyWithGoldenBytesAndRoundTrips() throws ParseException {
        final var key = new StateItem(Bytes.fromHex("aa"), Bytes.fromHex("bb"));

        final var stateKey = StateKeyUtils.kvKey(2, key, StateItem.CODEC);

        assertThat(stateKey).isEqualTo(Bytes.fromHex("12061201aa1a01bb"));
        assertThat(StateKeyUtils.extractStateIdFromStateKeyOneOf(stateKey)).isEqualTo(2);
        assertThat(StateKeyUtils.extractKeyFromStateKeyOneOf(stateKey, StateItem.CODEC))
                .isEqualTo(key);
    }

    @Test
    void writesBytesKeyDirectlyWithGoldenBytes() {
        final var stateKey = StateKeyUtils.kvKey(16, Bytes.fromHex("010203"));

        assertThat(stateKey).isEqualTo(Bytes.fromHex("820103010203"));
        assertThat(StateKeyUtils.extractStateIdFromStateKeyOneOf(stateKey)).isEqualTo(16);
    }

    @Test
    void preservesSignedVarIntEncodingForLargeStateIds() {
        final var stateKey = StateKeyUtils.kvKey(1 << 28, Bytes.EMPTY);

        assertThat(stateKey).isEqualTo(Bytes.fromHex("82808080f8ffffffff0100"));
    }
}
