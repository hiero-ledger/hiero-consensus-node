// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.DynamicTest;

public class GasLimitThrottlingSuite {
    private static final String CONTRACT = "Benchmark";
    public static final String PAYER_ACCOUNT = "payerAccount";

    @LeakyHapiTest(overrides = {"contracts.maxGasPerSec"})
    final Stream<DynamicTest> txOverGasLimitThrottled() {
        final var MAX_GAS_PER_SECOND = 1_000_001L;
        return hapiTest(
                overriding("contracts.maxGasPerSec", String.valueOf(MAX_GAS_PER_SECOND)),
                cryptoCreate(PAYER_ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).gas(1_000_000),
                contractCall(
                                CONTRACT,
                                "twoSSTOREs",
                                Bytes.fromHexString(
                                                "0x0000000000000000000000000000000000000000000000000000000000000005")
                                        .toArray())
                        .gas(MAX_GAS_PER_SECOND + 1L)
                        .payingWith(PAYER_ACCOUNT)
                        .hasPrecheckFrom(MAX_GAS_LIMIT_EXCEEDED, BUSY));
    }
}
