// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNonZeroNodePaymentForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONTRACT_CALL_LOCAL_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONTRACT_GET_BYTECODE_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONTRACT_GET_BYTECODE_INCLUDED_PROCESSING_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONTRACT_GET_INFO_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.GAS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.PROCESSING_BYTES_FEE_USD;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
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

    @Contract(contract = "SmartContractsFees")
    static SpecContract contract;

    @Contract(contract = "SmartContractsFeesLarge", creationGas = 8_000_000L)
    static SpecContract largeBytecodeContract;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount civilian;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount relayer;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
        lifecycle.doAdhoc(contract.getInfo(), largeBytecodeContract.getInfo(), civilian.getInfo(), relayer.getInfo());
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
    @DisplayName("Validate getContractBytecode fee follows processing-bytes pricing")
    final Stream<DynamicTest> getContractBytecodeWithProcessingBytesPricing() {
        final var record = "getBytecodeWithProcessingBytesPricing";
        final var bytecodeLen = new AtomicLong(0);
        return hapiTest(
                getContractBytecode(largeBytecodeContract.name())
                        .payingWith(civilian.name())
                        .signedBy(civilian.name())
                        .exposingBytecodeTo(bytes -> bytecodeLen.set(bytes.length))
                        .via(record),
                sourcing(() -> validateChargedUsdForQueries(
                        record,
                        CONTRACT_GET_BYTECODE_BASE_FEE
                                + Math.max(0, bytecodeLen.get() - CONTRACT_GET_BYTECODE_INCLUDED_PROCESSING_BYTES)
                                        * PROCESSING_BYTES_FEE_USD,
                        0.1)));
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
                validateNonZeroNodePaymentForQuery(record));
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
}
