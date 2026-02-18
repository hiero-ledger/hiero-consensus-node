// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class ContractServiceQueriesSimpleFeesTest {
    private static final double CONTRACT_CALL_LOCAL_BASE_FEE = 0.001;
    private static final double CONTRACT_GET_BYTECODE_BASE_FEE = 0.05;
    private static final double CONTRACT_GET_INFO_BASE_FEE = 0.0001;

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
        return hapiTest(
                contractCallLocal(contract.name(), "contractLocalCallGet1Byte")
                        .gas(21500)
                        .payingWith(civilian.name())
                        .signedBy(civilian.name())
                        .via(contractLocalCall),
                validateChargedUsdForQueries(contractLocalCall, CONTRACT_CALL_LOCAL_BASE_FEE, 1));
    }

    @HapiTest
    @DisplayName("Validate getContractBytecode base usd fee")
    final Stream<DynamicTest> getContractBytecodeBaseUSDFee() {
        final var record = "getBytecode";
        return hapiTest(
                getContractBytecode(contract.name())
                        .payingWith(civilian.name())
                        .signedBy(civilian.name())
                        .via(record),
                validateChargedUsdForQueries(record, CONTRACT_GET_BYTECODE_BASE_FEE, 0.1));
    }

    @HapiTest
    @DisplayName("Validate get contract info base usd fee")
    final Stream<DynamicTest> getContractInfoBaseUSDFee() {
        final var record = "getInfo";
        return hapiTest(
                getContractInfo(contract.name())
                        .payingWith(civilian.name())
                        .signedBy(civilian.name())
                        .via(record),
                validateChargedUsdForQueries(record, CONTRACT_GET_INFO_BASE_FEE, 1));
    }
}
