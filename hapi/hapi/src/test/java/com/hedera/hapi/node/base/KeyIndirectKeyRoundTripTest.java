// SPDX-License-Identifier: Apache-2.0
package com.hedera.hapi.node.base;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.pbj.runtime.ParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeyIndirectKeyRoundTripTest {
    @Test
    @DisplayName("Key with IndirectKey(account_id) round-trips via PBJ")
    void keyWithIndirectAccountIdRoundTrips() throws ParseException {
        final var indirect = IndirectKey.newBuilder().accountId(AccountID.DEFAULT).build();
        final var key = Key.newBuilder().indirectKey(indirect).build();
        final var bytes = Key.PROTOBUF.toBytes(key);
        final var parsed = Key.PROTOBUF.parse(bytes.toReadableSequentialData());
        assertThat(parsed).isEqualTo(key);
    }
}

