// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForGasOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithoutGas;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFeeFromBytesFor;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class SimpleSmartContractServiceFeesTest {
    static final double CONTRACT_CREATE_BASE_FEE = 1.0;
    static final double CONTRACT_DELETE_BASE_FEE = 0.007;
    static final double CONTRACT_CALL_BASE_FEE = 0;
    static final double CONTRACT_UPDATE_BASE_FEE = 0.026;
    static final double ETHEREUM_CALL_BASE_FEE = 0.0001;
    static final double HOOK_STORE_BASE_FEE = 0.005;
    // EXTRAS
    static final double EXTRA_HOOK_SLOT_UPDATE_FEE = 0.0050;
    static final double EXPECTED_GAS_USED = 0.00184;

    @Contract(contract = "SmartContractsFees")
    static SpecContract contract;

    @Contract(contract = "StorageAccessHook", creationGas = 5_000_000)
    static SpecContract storageGetSlotHook;

    @Contract(contract = "CalldataSize")
    static SpecContract calldataContract;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount civilian;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount relayer;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                contract.getInfo(),
                storageGetSlotHook.getInfo(),
                calldataContract.getInfo(),
                civilian.getInfo(),
                relayer.getInfo());
    }

    @HapiTest
    @DisplayName("Create, update and delete a smart contract and assure proper fee charged")
    final Stream<DynamicTest> contractCreateUpdateDeleteBaseUSDFee() {
        return hapiTest(
                uploadInitCode("EmptyOne"),
                contractCreate("EmptyOne")
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(civilian.name())
                        .gas(200_000L)
                        .via("createTxn"),
                contractUpdate("EmptyOne")
                        .payingWith(civilian.name())
                        .signedBy(civilian.name(), "EmptyOne")
                        .via("updateTxn"),
                // we need the contractId signature as well as the payer
                contractDelete("EmptyOne")
                        .payingWith(civilian.name())
                        .signedBy(civilian.name(), "EmptyOne")
                        .via("deleteTxn"),
                validateChargedUsd("createTxn", CONTRACT_CREATE_BASE_FEE),
                validateChargedUsd("updateTxn", CONTRACT_UPDATE_BASE_FEE + SIGNATURE_FEE_AFTER_MULTIPLIER),
                validateChargedUsd("deleteTxn", CONTRACT_DELETE_BASE_FEE + SIGNATURE_FEE_AFTER_MULTIPLIER));
    }

    @HapiTest
    @DisplayName("Call a smart contract and assure proper fee charged")
    final Stream<DynamicTest> contractCallBaseUSDFee() {
        final var contractCall = "contractCall";
        return hapiTest(
                contract.call("contractCall1Byte", (Object) new byte[] {0})
                        .payingWith(civilian)
                        .gas(100_000L)
                        .via(contractCall),
                // ContractCall's fee is paid with gas only. Estimated price is based on call data and gas used
                validateChargedUsdForGasOnly(contractCall, EXPECTED_GAS_USED, 1),
                validateChargedUsdWithoutGas(contractCall, CONTRACT_CALL_BASE_FEE, 0));
    }

    @LeakyHapiTest(overrides = "contracts.evm.ethTransaction.zeroHapiFees.enabled")
    @DisplayName("Do an ethereum transaction and assure proper fee charged")
    final Stream<DynamicTest> ethereumTransactionBaseUSDFee() {
        return hapiTest(
                overriding("contracts.evm.ethTransaction.zeroHapiFees.enabled", "false"),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                ethereumCall(contract.name(), "contractCall1Byte", (Object) new byte[] {0})
                        .fee(ONE_HBAR)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signedBy(SECP_256K1_SOURCE_KEY)
                        .payingWith(relayer.name())
                        .nonce(0)
                        .via("ethCall"),
                // Estimated base fee for EthereumCall is 0.0001 USD and is paid by the relayer account
                validateChargedUsdWithin("ethCall", EXPECTED_GAS_USED + ETHEREUM_CALL_BASE_FEE, 1),
                validateChargedUsdForGasOnly("ethCall", EXPECTED_GAS_USED, 1),
                validateChargedUsdWithoutGas("ethCall", ETHEREUM_CALL_BASE_FEE, 1));
    }

    @LeakyHapiTest(overrides = "contracts.evm.ethTransaction.zeroHapiFees.enabled")
    @DisplayName("Do an JUMBO ethereum transaction and assure proper fee charged")
    final Stream<DynamicTest> jumboEthTransactionBaseUSDFee() {
        final var payloadSize = 10 * 1024;
        final var jumboPayload = new byte[payloadSize];
        final var jumboGasUsed = 0.0054;
        return hapiTest(
                overriding("contracts.evm.ethTransaction.zeroHapiFees.enabled", "false"),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                ethereumCall(calldataContract.name(), "callme", jumboPayload)
                        .fee(ONE_HUNDRED_HBARS)
                        .memo("TESTT")
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signedBy(SECP_256K1_SOURCE_KEY)
                        .payingWith(relayer.name())
                        .markAsJumboTxn()
                        .nonce(0)
                        .via("ethCall"),
                // Estimated base fee for EthereumCall is 0.0001 USD and is paid by the relayer account
                withOpContext((spec, log) -> {
                    final var expectedFeeFromBytes = expectedFeeFromBytesFor(spec, log, "ethCall");

                    final var totalExpected = jumboGasUsed + ETHEREUM_CALL_BASE_FEE + expectedFeeFromBytes;
                    final var withoutGasExpected = ETHEREUM_CALL_BASE_FEE + expectedFeeFromBytes;

                    allRunFor(
                            spec,
                            validateChargedUsdWithin("ethCall", totalExpected, 1),
                            validateChargedUsdForGasOnly("ethCall", jumboGasUsed, 1),
                            validateChargedUsdWithoutGas("ethCall", withoutGasExpected, 1));
                }));
    }

    @LeakyHapiTest(overrides = "hooks.hooksEnabled")
    final Stream<DynamicTest> multipleHookStoreCreateBaseFee() {
        final Bytes slot1 = Bytes.wrap("slot1");
        final Bytes slot2 = Bytes.wrap("slot2");
        final Bytes slot3 = Bytes.wrap("slot3");
        final Bytes value = Bytes.wrap("value");
        return hapiTest(
                overriding("hooks.hooksEnabled", "true"),
                cryptoCreate("ownerAccount").withHooks(accountAllowanceHook(237L, storageGetSlotHook.name())),
                accountEvmHookStore("ownerAccount", 237L)
                        .putSlot(slot1, value)
                        .putSlot(slot2, value)
                        .putSlot(slot3, value)
                        .payingWith("ownerAccount")
                        .signedBy("ownerAccount")
                        .via("hookStoreTxn"),
                validateChargedUsd("hookStoreTxn", HOOK_STORE_BASE_FEE + 2 * EXTRA_HOOK_SLOT_UPDATE_FEE));
    }

    @LeakyHapiTest(overrides = "hooks.hooksEnabled")
    final Stream<DynamicTest> singleHookStoreCreateBaseFee() {
        final Bytes slot1 = Bytes.wrap("slot1");
        final Bytes value = Bytes.wrap("value");
        return hapiTest(
                overriding("hooks.hooksEnabled", "true"),
                cryptoCreate("ownerAccount").withHooks(accountAllowanceHook(237L, storageGetSlotHook.name())),
                accountEvmHookStore("ownerAccount", 237L)
                        .putSlot(slot1, value)
                        .payingWith("ownerAccount")
                        .signedBy("ownerAccount")
                        .via("hookStoreTxn"),
                validateChargedUsd("hookStoreTxn", HOOK_STORE_BASE_FEE));
    }
}
