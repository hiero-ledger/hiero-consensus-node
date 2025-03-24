// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Arrays;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

@Tag(UPGRADE)
@Order(Integer.MAX_VALUE - 123)
@HapiTestLifecycle
@OrderedInIsolation
public class JumboTransactionsEnabledTest implements LifecycleTest {

    private static final int SMALL_TXN_SIZE = 6 * 1024;
    private static final int MAX_ALLOWED_SIZE = 128 * 1024;
    private static final int ABOVE_MAX_SIZE = 129 * 1024;
    private static final int OVERSIZED_TXN_SIZE = 130 * 1024;
    private static final String CONTRACT_NAME = "CalldataSize";
    private static final String FUNCTION_NAME = "callme";
    private record TestCombination(int txnSize, EthTxData.EthTransactionType type) {}

    private static HapiEthereumCall jumboEthCall(String contract, String function, byte[] payload, EthTxData.EthTransactionType type) {
        var resolvedType = (type == null) ? EthTxData.EthTransactionType.EIP1559 : type;
        return ethereumCall(contract, function, payload)
                .markAsJumboTxn()
                .type(resolvedType)
                .signingWith(SECP_256K1_SOURCE_KEY)
                .payingWith(RELAYER)
                .gasLimit(1_000_000L);
    }


    @HapiTest
    @Order(1)
    @DisplayName("Enable jumbo transactions")
    public Stream<DynamicTest> jumboTransactionShouldBeEnabled() {
        return hapiTest(
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()));
    }

    @Nested
    @DisplayName("Jumbo Ethereum Transactions Positive Tests")
    class JumboEthereumTransactionsPositiveTests {
//        private final Stream<Integer> positiveBoundariesTestCases = Stream.of(SMALL_TXN_SIZE, MAX_ALLOWED_SIZE);
        private final Stream<TestCombination> positiveBoundariesTestCases = Stream.of(
                new TestCombination(MAX_ALLOWED_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM),
                new TestCombination(MAX_ALLOWED_SIZE, EthTxData.EthTransactionType.EIP2930),
                new TestCombination(MAX_ALLOWED_SIZE, EthTxData.EthTransactionType.EIP1559),
                new TestCombination(SMALL_TXN_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM),
                new TestCombination(SMALL_TXN_SIZE, EthTxData.EthTransactionType.EIP2930),
                new TestCombination(SMALL_TXN_SIZE, EthTxData.EthTransactionType.EIP1559)
        );

        @HapiTest
        @Order(2)
        @DisplayName("Jumbo Ethereum transactions should pass for valid sizes")
        public Stream<DynamicTest> jumboTxnWithEthereumDataLessThanAllowedKbShouldPass() {
            return positiveBoundariesTestCases.flatMap(test -> hapiTest(
                    logIt("Valid Jumbo Txn with size: " + (test.txnSize / 1024) + "KB"),
                    prepareFakeUpgrade(),
                    upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                    uploadInitCode(CONTRACT_NAME),
                    contractCreate(CONTRACT_NAME),
                    jumboEthCall(CONTRACT_NAME, FUNCTION_NAME, new byte[test.txnSize], test.type)
                            .hasPrecheckFrom(OK, TRANSACTION_OVERSIZE)
            ));
        }
    }

    @Nested
    @DisplayName("Jumbo Ethereum Transactions Negative Tests")
    class JumboEthereumTransactionsNegativeTests {
        private final Stream<TestCombination> oversizedCases = Stream.of(
                new TestCombination(ABOVE_MAX_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM),
                new TestCombination(OVERSIZED_TXN_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM),
                new TestCombination(ABOVE_MAX_SIZE, EthTxData.EthTransactionType.EIP2930),
                new TestCombination(OVERSIZED_TXN_SIZE, EthTxData.EthTransactionType.EIP2930),
                new TestCombination(ABOVE_MAX_SIZE, EthTxData.EthTransactionType.EIP1559),
                new TestCombination(OVERSIZED_TXN_SIZE, EthTxData.EthTransactionType.EIP1559)
        );
        private final Stream<Integer> insufficientFeeCases = Stream.of(SMALL_TXN_SIZE, MAX_ALLOWED_SIZE);
        private static byte[] corruptedPayload() {
            return Arrays.copyOf("corruptedPayload".getBytes(StandardCharsets.UTF_8), 128 * 1024);
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail for oversized data")
        @Order(3)
        public Stream<DynamicTest> jumboTxnWithOversizedDataShouldFail() {
            return oversizedCases.flatMap(test -> hapiTest(
                    logIt("Invalid Jumbo Txn with size: " + (test.txnSize / 1024) + "KB and type: " + test.type),
                    prepareFakeUpgrade(),
                    upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    jumboEthCall(CONTRACT_NAME, FUNCTION_NAME, new byte[test.txnSize], test.type)
                            .hasPrecheck(TRANSACTION_OVERSIZE)
            ));
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail with corrupted payload")
        @TestFactory
        @Order(4)
        public Stream<DynamicTest> jumboTxnWithCorruptedPayloadShouldFail() {
            var corruptedTypes = Stream.of(
                    EthTxData.EthTransactionType.LEGACY_ETHEREUM,
                    EthTxData.EthTransactionType.EIP2930,
                    EthTxData.EthTransactionType.EIP1559
            );
            return corruptedTypes.flatMap(type -> hapiTest(
                    logIt("Corrupted Jumbo Txn of size 128KB and type: " + type),
                    prepareFakeUpgrade(),
                    upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    ethereumCall(CONTRACT_NAME, FUNCTION_NAME, corruptedPayload())
                            .markAsJumboTxn()
                            .type(type)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(RELAYER)
                            .gasLimit(1_000_000L)
                            .hasPrecheckFrom(OK, INVALID_TRANSACTION_BODY)
            ));
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail with corrupted payload")
        @TestFactory
        @Order(4)
        public Stream<DynamicTest> jumboTxnWithCorruptedPayloadShouldFail111() {
            var payload = new byte[4 * 1024];
            return hapiTest(
//                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    newKeyNamed("string").shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    uploadInitCode(CONTRACT_NAME),
                    contractCreate(CONTRACT_NAME),
                    ethereumCall(CONTRACT_NAME, FUNCTION_NAME, payload)
                            .markAsJumboTxn()
                            .signingWith("string")
                            .payingWith(RELAYER)
                            .gasLimit(1_000_000L)
                            .hasPrecheckFrom(OK)
            );
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail due to insufficient fees")
        @TestFactory
        @Order(5)
        public Stream<DynamicTest> jumboTxnWithInsufficientFeesShouldFail() {
            return insufficientFeeCases.flatMap(txnSize -> hapiTest(
                    logIt("Invalid Jumbo Txn with insufficient fees and size: " + (txnSize / 1024) + "KB"),
                    prepareFakeUpgrade(),
                    upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(1L),
                    jumboEthCall(CONTRACT_NAME, FUNCTION_NAME, new byte[txnSize], null)
                            .hasPrecheck(INSUFFICIENT_TX_FEE)
            ));
        }
    }
}
