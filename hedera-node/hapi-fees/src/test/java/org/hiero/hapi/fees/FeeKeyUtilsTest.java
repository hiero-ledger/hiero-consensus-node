// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class FeeKeyUtilsTest {
    private static final Key ED25519 =
            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
    private static final Key CONTRACT_ID = Key.newBuilder()
            .contractID(ContractID.newBuilder().contractNum(1001L).build())
            .build();
    private static final Key DELEGATABLE_CONTRACT_ID = Key.newBuilder()
            .delegatableContractId(ContractID.newBuilder().contractNum(1002L).build())
            .build();

    @Test
    void contractIdKeysCountLikeAnyOtherKey() {
        assertEquals(1L, FeeKeyUtils.countKeys(ED25519));
        assertEquals(1L, FeeKeyUtils.countKeys(CONTRACT_ID));
        assertEquals(1L, FeeKeyUtils.countKeys(DELEGATABLE_CONTRACT_ID));
    }

    @Test
    void everyContractIdKeyInAKeyListIsCounted() {
        final var keys = LongStream.range(0, 700)
                .mapToObj(i -> Key.newBuilder()
                        .contractID(
                                ContractID.newBuilder().contractNum(5_000L + i).build())
                        .build())
                .toList();
        final var keyList = Key.newBuilder()
                .keyList(KeyList.newBuilder().keys(keys).build())
                .build();

        assertEquals(700L, FeeKeyUtils.countKeys(keyList));
    }

    @Test
    void nestedKeysOfMixedKindsAreAllCounted() {
        final var threshold = Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(1)
                        .keys(KeyList.newBuilder()
                                .keys(List.of(ED25519, CONTRACT_ID))
                                .build())
                        .build())
                .build();
        final var keyList = Key.newBuilder()
                .keyList(KeyList.newBuilder()
                        .keys(List.of(threshold, DELEGATABLE_CONTRACT_ID))
                        .build())
                .build();

        assertEquals(3L, FeeKeyUtils.countKeys(keyList));
    }
}
