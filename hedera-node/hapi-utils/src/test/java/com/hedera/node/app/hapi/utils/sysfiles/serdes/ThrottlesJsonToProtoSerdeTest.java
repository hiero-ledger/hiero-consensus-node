// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.serdes;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TRANSACTION_GET_RECEIPT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.ThrottleBucket;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.ThrottleGroup;
import com.hedera.node.app.hapi.utils.TestUtils;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThrottlesJsonToProtoSerdeTest {
    @Test
    void loadsExpectedDefs() throws IOException {
        final var actual = TestUtils.protoDefs("bootstrap/throttles.json");

        assertEquals(expected(), actual);
    }

    private ThrottleDefinitions expected() {
        return ThrottleDefinitions.newBuilder()
                .throttleBuckets(aBucket(), bBucket(), cBucket(), dBucket())
                .build();
    }

    private ThrottleBucket aBucket() {
        return ThrottleBucket.newBuilder()
                .name("A")
                .burstPeriodMs(2_000)
                .throttleGroups(
                        from(10000, List.of(CRYPTO_TRANSFER, CRYPTO_CREATE)),
                        from(12, List.of(CONTRACT_CALL)),
                        from(3000, List.of(TOKEN_MINT)))
                .build();
    }

    private ThrottleBucket bBucket() {
        return ThrottleBucket.newBuilder()
                .name("B")
                .burstPeriodMs(2_000)
                .throttleGroups(from(10, List.of(CONTRACT_CALL)))
                .build();
    }

    private ThrottleBucket cBucket() {
        return ThrottleBucket.newBuilder()
                .name("C")
                .burstPeriodMs(3_000)
                .throttleGroups(
                        from(2, List.of(CRYPTO_CREATE)), from(100, List.of(TOKEN_CREATE, TOKEN_ASSOCIATE_TO_ACCOUNT)))
                .build();
    }

    private ThrottleBucket dBucket() {
        return ThrottleBucket.newBuilder()
                .name("D")
                .burstPeriodMs(4_000)
                .throttleGroups(from(1_000_000, List.of(CRYPTO_GET_ACCOUNT_BALANCE, TRANSACTION_GET_RECEIPT)))
                .build();
    }

    private ThrottleGroup from(final int opsPerSec, final List<HederaFunctionality> functions) {
        return ThrottleGroup.newBuilder()
                .milliOpsPerSec(1_000 * opsPerSec)
                .operations(functions)
                .build();
    }
}
