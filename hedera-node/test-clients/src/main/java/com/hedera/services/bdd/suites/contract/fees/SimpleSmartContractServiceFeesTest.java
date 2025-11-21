package com.hedera.services.bdd.suites.contract.fees;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForGasOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithoutGas;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
@OrderedInIsolation
public class SimpleSmartContractServiceFeesTest {
    final static double CONTRACT_CREATE_BASE_FEE = 1.0;
    final static double CONTRACT_CALL_BASE_FEE = 0.05;

    @Contract(contract = "SmartContractsFees")
    static SpecContract contract;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount civilian;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount relayer;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(contract.getInfo(), civilian.getInfo(), relayer.getInfo());
    }

    @HapiTest
    @DisplayName("Create a smart contract and assure proper fee charged")
    @Order(0)
    final Stream<DynamicTest> contractCreateBaseUSDFee() {
        final var creation = "creation";
        return hapiTest(
                uploadInitCode("EmptyOne"),
                contractCreate("EmptyOne")
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(civilian.name())
                        .gas(200_000L)
                        .via(creation),
                validateChargedUsdWithoutGas(creation, CONTRACT_CREATE_BASE_FEE,1));
    }

    @HapiTest
    @DisplayName("Call a smart contract and assure proper fee charged")
    @Order(1)
    final Stream<DynamicTest> contractCallBaseUSDFee() {
        final var contractCall = "contractCall";
        return hapiTest(
                contract.call("contractCall1Byte", (Object) new byte[] {0}).gas(100_000L).via(contractCall),
                // ContractCall's fee is paid with gas only. Estimated price is based on call data and gas used
                validateChargedUsdForGasOnly(contractCall, 0.00184, 1),
                validateChargedUsdWithoutGas(contractCall, CONTRACT_CALL_BASE_FEE,1));
    }
}
