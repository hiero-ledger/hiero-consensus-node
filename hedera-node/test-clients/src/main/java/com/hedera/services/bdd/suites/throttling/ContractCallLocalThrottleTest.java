// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractCallLocalThrottleTest {

    private static final String CONTRACT = "SmartContractsFees";
    private static final String VIEW_FN = "contractLocalCallGet1Byte";
    private static final String CIVILIAN = "civilian";

    private static final String TINY_THROTTLES = "testSystemFiles/tiny-contract-call-local-throttle.json";

    @LeakyHapiTest(
            requirement = {THROTTLE_OVERRIDES},
            throttles = TINY_THROTTLES)
    final Stream<DynamicTest> contractCallLocalNotBypassesTinyThrottleBucket() {
        return hapiTest(withOpContext((spec, opLog) -> {
            final var nodesCount = spec.getNetworkNodes().size();
            opLog.info("{} running with {} nodes", this.getClass().getSimpleName(), nodesCount);
            final List<SpecOperation> ops = new ArrayList<>();
            ops.add(uploadInitCode(CONTRACT));
            ops.add(contractCreate(CONTRACT));
            ops.add(cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS));
            // tiny-contract-call-local-throttle.json -> "name": "PoCContractQueryLimits" -> "milliOpsPerSec": 12000
            // 12000 / 1000 / nodesCount (4 nodes local, 3 nodes on CI) = OpsPerSec per one node
            // each call = 1 cost-answer + 1 answer-only = 2 OpsPerSec = 2000 milliOpsPerSec
            final AtomicInteger callCapacity = new AtomicInteger(12000 / 1000 / nodesCount + 1);
            while (callCapacity.get() >= 0) {
                ops.add(contractCallLocal(CONTRACT, VIEW_FN)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .hasCostAnswerPrecheck(callCapacity.decrementAndGet() > 0 ? OK : BUSY)
                        .hasAnswerOnlyPrecheck(callCapacity.decrementAndGet() > 0 ? OK : BUSY));
            }
            // last getContractInfo is also failing because 'capacity' is exhausted
            ops.add(getContractInfo(CONTRACT)
                    .payingWith(CIVILIAN)
                    .signedBy(CIVILIAN)
                    .hasCostAnswerPrecheck(callCapacity.decrementAndGet() > 0 ? OK : BUSY)
                    .hasAnswerOnlyPrecheck(callCapacity.decrementAndGet() > 0 ? OK : BUSY));
            allRunFor(spec, ops);
        }));
    }
}
