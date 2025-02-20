// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.serdes;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCall;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoGetAccountBalance;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAssociateToAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TransactionGetReceipt;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.utils.TestUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ThrottleBucket;
import com.hederahashgraph.api.proto.java.ThrottleDefinitions;
import com.hederahashgraph.api.proto.java.ThrottleGroup;
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
                .addThrottleBuckets(aBucket())
                .addThrottleBuckets(bBucket())
                .addThrottleBuckets(cBucket())
                .addThrottleBuckets(dBucket())
                .build();
    }

    private ThrottleBucket aBucket() {
        return ThrottleBucket.newBuilder()
                .setName("A")
                .setBurstPeriodMs(2_000)
                .addThrottleGroups(from(10000, List.of(CryptoTransfer, CryptoCreate)))
                .addThrottleGroups(from(12, List.of(ContractCall)))
                .addThrottleGroups(from(3000, List.of(TokenMint)))
                .build();
    }

    private ThrottleBucket bBucket() {
        return ThrottleBucket.newBuilder()
                .setName("B")
                .setBurstPeriodMs(2_000)
                .addThrottleGroups(from(10, List.of(ContractCall)))
                .build();
    }

    private ThrottleBucket cBucket() {
        return ThrottleBucket.newBuilder()
                .setName("C")
                .setBurstPeriodMs(3_000)
                .addThrottleGroups(from(2, List.of(CryptoCreate)))
                .addThrottleGroups(from(100, List.of(TokenCreate, TokenAssociateToAccount)))
                .build();
    }

    private ThrottleBucket dBucket() {
        return ThrottleBucket.newBuilder()
                .setName("D")
                .setBurstPeriodMs(4_000)
                .addThrottleGroups(from(1_000_000, List.of(CryptoGetAccountBalance, TransactionGetReceipt)))
                .build();
    }

    private ThrottleGroup from(final int opsPerSec, final List<HederaFunctionality> functions) {
        return ThrottleGroup.newBuilder()
                .setMilliOpsPerSec(1_000 * opsPerSec)
                .addAllOperations(functions)
                .build();
    }
}
