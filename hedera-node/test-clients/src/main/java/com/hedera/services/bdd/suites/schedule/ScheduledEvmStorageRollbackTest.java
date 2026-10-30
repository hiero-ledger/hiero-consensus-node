// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.utils.contracts.SimpleBytesResult.bigIntResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** Checks that an enclosing revert undoes storage bookkeeping performed by a successful scheduled execution. */
@Tag(SMART_CONTRACT)
public class ScheduledEvmStorageRollbackTest {
    private static final String CONTRACT = "ScheduledEvmInterleaving";
    private static final String PAYER = "rollbackSchedulePayer";
    private static final String SCHEDULE = "rollbackSchedule";

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> outerRevertRestoresScheduledDeletionAndAllowsRetry() {
        return rollbackThenRetry(10, 11, 0);
    }

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> outerRevertRestoresScheduledInsertionAndAllowsRetry() {
        return rollbackThenRetry(0, 0, 22);
    }

    private Stream<DynamicTest> rollbackThenRetry(
            final int initialValue, final int beforeValue, final int scheduledValue) {
        return hapiTest(
                overriding("scheduling.whitelist", "ContractCall"),
                overriding("contracts.sidecars", "CONTRACT_STATE_CHANGE,CONTRACT_BYTECODE"),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT, BigInteger.valueOf(initialValue)).gas(2_000_000),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).keyShape(KeyShape.CONTRACT.signedWith(CONTRACT)),
                scheduleCreate(
                                SCHEDULE,
                                contractCall(CONTRACT, "set", BigInteger.valueOf(scheduledValue))
                                        .gas(1_000_000))
                        .designatingPayer(PAYER)
                        .signedBy(DEFAULT_PAYER),
                getScheduleInfo(SCHEDULE).isNotExecuted(),
                withOpContext((spec, log) -> {
                    final var schedules = new Address[] {
                        mirrorAddrWith(
                                spec, spec.registry().getScheduleId(SCHEDULE).getScheduleNum())
                    };
                    // run() checks the value only after the schedule authorization returns successfully.
                    // A deliberately wrong expectation reverts X after Y has performed its storage commit.
                    allRunFor(
                            spec,
                            contractCall(
                                            CONTRACT,
                                            "run",
                                            schedules,
                                            BigInteger.valueOf(beforeValue),
                                            new BigInteger[] {BigInteger.valueOf(scheduledValue + 1)},
                                            false,
                                            BigInteger.ZERO)
                                    .gas(3_000_000)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            getScheduleInfo(SCHEDULE).isNotExecuted(),
                            getContractInfo(CONTRACT).has(contractWith().numKvPairs(initialValue == 0 ? 0 : 1)),
                            contractCallLocal(CONTRACT, "value")
                                    .has(resultWith().contractCallResult(bigIntResult(initialValue))));

                    // The same authorization must still work after rollback, with exactly one application of
                    // Y's insertion/removal to the account's count and the persistent storage linked list.
                    allRunFor(
                            spec,
                            contractCall(
                                            CONTRACT,
                                            "run",
                                            schedules,
                                            BigInteger.valueOf(beforeValue),
                                            new BigInteger[] {BigInteger.valueOf(scheduledValue)},
                                            false,
                                            BigInteger.ZERO)
                                    .gas(3_000_000)
                                    .hasKnownStatus(SUCCESS),
                            getScheduleInfo(SCHEDULE).isExecuted(),
                            getContractInfo(CONTRACT).has(contractWith().numKvPairs(scheduledValue == 0 ? 0 : 1)),
                            contractCallLocal(CONTRACT, "value")
                                    .has(resultWith().contractCallResult(bigIntResult(scheduledValue))));
                }));
    }
}
