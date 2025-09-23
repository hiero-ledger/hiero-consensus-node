// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
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
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of SmartContractServiceFeesTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
@HapiTestLifecycle
@OrderedInIsolation
@Tag(SMART_CONTRACT)
public class AtomicSmartContractServiceFeesTest {

    private static final String ATOMIC_BATCH = "atomicBatch";
    private static final String BATCH_OPERATOR = "batchOperator";

    @Contract(contract = "SmartContractsFees")
    static SpecContract contract;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount civilian;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount relayer;

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(contract.getInfo(), civilian.getInfo(), relayer.getInfo());
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    @DisplayName("Create a smart contract and assure proper fee charged")
    @Order(0)
    final Stream<DynamicTest> contractCreateBaseUSDFee() {
        final var creation = "creation";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                uploadInitCode("EmptyOne"),
                atomicBatch(contractCreate("EmptyOne")
                                .payingWith(civilian.name())
                                .gas(200_000L)
                                .via(creation)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(creation, ATOMIC_BATCH, 0.73, 5));
    }

    @HapiTest
    @DisplayName("Call a smart contract and assure proper fee charged")
    @Order(1)
    @Tag(MATS)
    final Stream<DynamicTest> contractCallBaseUSDFee() {
        final var contract = "contractCall";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                atomicBatch(contractCall("SmartContractsFees", "contractCall1Byte", new byte[] {0})
                                .gas(100_000L)
                                .via(contract)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(contract, ATOMIC_BATCH, 0.0068, 1));
    }

    @LeakyHapiTest(overrides = "contracts.evm.ethTransaction.zeroHapiFees.enabled")
    @DisplayName("Do an ethereum transaction and assure proper fee charged")
    @Order(2)
    @Tag(MATS)
    final Stream<DynamicTest> ethereumTransactionBaseUSDFee(
            @Account(tinybarBalance = ONE_HUNDRED_HBARS) final SpecAccount receiver) {
        final var ethCall = "ethCall";
        return hapiTest(
                overriding("contracts.evm.ethTransaction.zeroHapiFees.enabled", "false"),
                receiver.getInfo(),
                cryptoCreate(BATCH_OPERATOR),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                atomicBatch(ethereumCall(contract.name(), "contractCall1Byte", new byte[] {0})
                                .type(EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(relayer.name())
                                .nonce(0)
                                .via(ethCall)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                // Estimated base fee for EthereumCall is 0.0001 USD and is paid by the relayer account
                validateInnerTxnChargedUsd(ethCall, ATOMIC_BATCH, 0.0069, 1));
    }
}
