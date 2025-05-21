// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hederahashgraph.api.proto.java.ScheduleID;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class UnknownFunctionSelectorTest {

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount account;

    @Contract(contract = "UnknownFunctionSelectorContract", creationGas = 1_500_000)
    static SpecContract contract;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(contract.getInfo(), account.getInfo());
    }

    @HapiTest
    final Stream<DynamicTest> callScheduleServiceWithUnknownSelector(@Account final SpecAccount receiver) {

        final AtomicReference<ScheduleID> scheduleID = new AtomicReference<>();
        final String schedule = "testSchedule";
        return hapiTest(
                receiver.getInfo(),
                scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(account.name(), receiver.name(), 1)))
                        .exposingCreatedIdTo(scheduleID::set),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        contract.name(),
                                        "callScheduleServiceWithFakeSelector",
                                        mirrorAddrWith(spec, scheduleID.get().getScheduleNum()))
                                .payingWith(account.name())
                                .hasKnownStatus(SUCCESS)
                                .gas(1_000_000)
                                .via("txn"))),
                withOpContext((spec, opLog) -> {
                    final var txn = getTxnRecord("txn");
                    allRunFor(spec, txn);

                    final var res = Bytes32.wrap(Arrays.copyOfRange(
                            txn.getResponseRecord()
                                    .getContractCallResult()
                                    .getContractCallResult()
                                    .toByteArray(),
                            32,
                            64));
                    assertEquals(Bytes32.ZERO, res);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> callTokenServiceWithUnknownSelector() {
        return hapiTest(
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        contract.name(),
                                        "callTokenServiceWithFakeSelector",
                                        contract.addressOn(spec.targetNetworkOrThrow()))
                                .payingWith(account.name())
                                .hasKnownStatus(SUCCESS)
                                .gas(1_000_000)
                                .via("txn"))),
                withOpContext((spec, opLog) -> {
                    final var txn = getTxnRecord("txn");
                    allRunFor(spec, txn);

                    final var res = Bytes32.wrap(Arrays.copyOfRange(
                            txn.getResponseRecord()
                                    .getContractCallResult()
                                    .getContractCallResult()
                                    .toByteArray(),
                            32,
                            64));
                    assertEquals(Bytes32.ZERO, res);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> callAccountServiceWithUnknownSelector() {
        return hapiTest(
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        contract.name(),
                                        "callAccountServiceWithFakeSelector",
                                        contract.addressOn(spec.targetNetworkOrThrow()))
                                .payingWith(account.name())
                                .hasKnownStatus(SUCCESS)
                                .gas(1_000_000)
                                .via("txn"))),
                withOpContext((spec, opLog) -> {
                    final var txn = getTxnRecord("txn");
                    allRunFor(spec, txn);

                    final var res = Bytes32.wrap(Arrays.copyOfRange(
                            txn.getResponseRecord()
                                    .getContractCallResult()
                                    .getContractCallResult()
                                    .toByteArray(),
                            32,
                            64));
                    assertEquals(Bytes32.ZERO, res);
                }));
    }
}
