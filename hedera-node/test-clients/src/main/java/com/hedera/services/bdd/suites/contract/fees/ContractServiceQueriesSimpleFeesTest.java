// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.explicitContractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNodePaymentAmountForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNonZeroNodePaymentForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONTRACT_CALL_LOCAL_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONTRACT_GET_BYTECODE_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONTRACT_GET_INFO_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.GAS_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class ContractServiceQueriesSimpleFeesTest {

    private static final long EXPECTED_NODE_PAYMENT_TINYCENTS = 84L;
    private static final String NON_EXISTING_CONTRACT =
            HapiSpecSetup.getDefaultInstance().invalidContractName();

    @Contract(contract = "SmartContractsFees")
    static SpecContract contract;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount civilian;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount relayer;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
        lifecycle.doAdhoc(contract.getInfo(), civilian.getInfo(), relayer.getInfo());
    }

    @HapiTest
    @DisplayName("Call a local smart contract local and assure proper fee charged")
    final Stream<DynamicTest> contractLocalCallBaseUSDFee() {
        final var contractLocalCall = "contractLocalCall";
        final var offeredGas = 21500;
        return hapiTest(
                contractCallLocal(contract.name(), "contractLocalCallGet1Byte")
                        .gas(offeredGas)
                        .payingWith(civilian.name())
                        .signedBy(civilian.name())
                        .via(contractLocalCall),
                validateChargedUsdForQueries(
                        contractLocalCall, CONTRACT_CALL_LOCAL_BASE_FEE + offeredGas * GAS_FEE_USD, 1));
    }

    @HapiTest
    @DisplayName("Validate getContractBytecode base usd fee")
    final Stream<DynamicTest> getContractBytecodeBaseUSDFee() {
        final var record = "getBytecode";
        return hapiTest(
                getContractBytecode(contract.name())
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(civilian.name())
                        .signedBy(civilian.name())
                        .via(record),
                validateChargedUsdForQueries(record, CONTRACT_GET_BYTECODE_BASE_FEE, 0.1),
                validateNonZeroNodePaymentForQuery(record));
    }

    @HapiTest
    @DisplayName("Validate get contract info base usd fee")
    final Stream<DynamicTest> getContractInfoBaseUSDFee() {
        final var record = "getInfo";
        return hapiTest(
                getContractInfo(contract.name())
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(civilian.name())
                        .signedBy(civilian.name())
                        .via(record),
                validateChargedUsdForQueries(record, CONTRACT_GET_INFO_BASE_FEE, 1),
                validateNodePaymentAmountForQuery(record, EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("ContractCallLocal query node payment scales with gas")
    final Stream<DynamicTest> contractCallLocalNodePaymentScalesWithGas() {
        final var lowGasQuery = "contractLocalLowGas";
        final var highGasQuery = "contractLocalHighGas";
        return hapiTest(
                contractCallLocal(contract.name(), "contractLocalCallGet1Byte")
                        .gas(50_000)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(civilian.name())
                        .signedBy(civilian.name())
                        .via(lowGasQuery),
                contractCallLocal(contract.name(), "contractLocalCallGet1Byte")
                        .gas(100_000)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(civilian.name())
                        .signedBy(civilian.name())
                        .via(highGasQuery),
                validateNonZeroNodePaymentForQuery(lowGasQuery),
                validateNonZeroNodePaymentForQuery(highGasQuery),
                withOpContext((spec, opLog) -> {
                    final var lowRecord = getTxnRecord(lowGasQuery);
                    final var highRecord = getTxnRecord(highGasQuery);
                    allRunFor(spec, lowRecord, highRecord);
                    final var nodeId = spec.setup().defaultNode();
                    final var lowNodePayment =
                            lowRecord.getResponseRecord().getTransferList().getAccountAmountsList().stream()
                                    .filter(aa -> aa.getAccountID().equals(nodeId))
                                    .mapToLong(aa -> aa.getAmount())
                                    .sum();
                    final var highNodePayment =
                            highRecord.getResponseRecord().getTransferList().getAccountAmountsList().stream()
                                    .filter(aa -> aa.getAccountID().equals(nodeId))
                                    .mapToLong(aa -> aa.getAmount())
                                    .sum();
                    assertTrue(
                            highNodePayment > lowNodePayment,
                            "Expected higher node payment for higher gas, but got low="
                                    + lowNodePayment
                                    + " and high="
                                    + highNodePayment);
                }));
    }

    @HapiTest
    @DisplayName("contract get info - invalid contract fails - no fee charged")
    final Stream<DynamicTest> contractGetInfoInvalidContractFails() {
        final AtomicLong initialBalance = new AtomicLong();
        final AtomicLong afterBalance = new AtomicLong();

        return hapiTest(
                getAccountBalance(civilian.name()).exposingBalanceTo(initialBalance::set),
                getContractInfo(NON_EXISTING_CONTRACT)
                        .payingWith(civilian.name())
                        .hasCostAnswerPrecheck(INVALID_CONTRACT_ID),
                getAccountBalance(civilian.name()).exposingBalanceTo(afterBalance::set),
                withOpContext((spec, log) -> {
                    assertEquals(initialBalance.get(), afterBalance.get());
                }));
    }

    @HapiTest
    @DisplayName("contract get bytecode - invalid contract fails - no fee charged")
    final Stream<DynamicTest> contractGetBytecodeInvalidContractFails() {
        final AtomicLong initialBalance = new AtomicLong();
        final AtomicLong afterBalance = new AtomicLong();

        return hapiTest(
                getAccountBalance(civilian.name()).exposingBalanceTo(initialBalance::set),
                getContractBytecode(NON_EXISTING_CONTRACT)
                        .payingWith(civilian.name())
                        .hasCostAnswerPrecheck(INVALID_CONTRACT_ID),
                getAccountBalance(civilian.name()).exposingBalanceTo(afterBalance::set),
                withOpContext((spec, log) -> {
                    assertEquals(initialBalance.get(), afterBalance.get());
                }));
    }

    @HapiTest
    @DisplayName("contract call local - invalid contract fails - no fee charged")
    final Stream<DynamicTest> contractCallLocalInvalidContractFails() {
        final AtomicLong initialBalance = new AtomicLong();
        final AtomicLong afterBalance = new AtomicLong();

        return hapiTest(
                getAccountBalance(civilian.name()).exposingBalanceTo(initialBalance::set),
                explicitContractCallLocal(NON_EXISTING_CONTRACT, new byte[0])
                        .gas(21500)
                        .payingWith(civilian.name())
                        .hasCostAnswerPrecheck(INVALID_CONTRACT_ID),
                getAccountBalance(civilian.name()).exposingBalanceTo(afterBalance::set),
                withOpContext((spec, log) -> {
                    assertEquals(initialBalance.get(), afterBalance.get());
                }));
    }
}
