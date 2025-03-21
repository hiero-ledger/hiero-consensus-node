// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
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
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.util.Map;
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

    private static HapiEthereumCall jumboEthCall(String contract, String function, byte[] payload) {
        return ethereumCall(contract, function, payload)
                .markAsJumboTxn()
                .type(EthTxData.EthTransactionType.EIP1559)
                .signingWith(SECP_256K1_SOURCE_KEY)
                .payingWith(RELAYER)
                .gasLimit(1_000_000L);
    }

    @HapiTest
    @Order(1)
    @DisplayName("Enable jumbo transactions")
    public Stream<DynamicTest> jumboTransactionShouldBeEnabled() {
        final var size = 10 * 1024;
        return hapiTest(
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()));
    }

    @Nested
    @DisplayName("Jumbo Ethereum Transactions Positive Tests")
    class JumboEthereumTransactionsPositiveTests {
        private final Stream<Integer> positiveTestCases = Stream.of(SMALL_TXN_SIZE, MAX_ALLOWED_SIZE);


        @HapiTest
        @Order(2)
        @DisplayName("Jumbo Ethereum transactions should pass for valid sizes")
        public Stream<DynamicTest> jumboTxnWithEthereumDataLessThanAllowedKbShouldPass() {
            return positiveTestCases.flatMap(txnSize -> hapiTest(
                    logIt("Valid Jumbo Txn with size: " + (txnSize / 1024) + "KB"),
                    prepareFakeUpgrade(),
                    upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                    uploadInitCode(CONTRACT_NAME),
                    contractCreate(CONTRACT_NAME),
                    jumboEthCall(CONTRACT_NAME, FUNCTION_NAME, new byte[txnSize])
                            .hasPrecheckFrom(OK, TRANSACTION_OVERSIZE)
            ));
        }
    }

    @Nested
    @DisplayName("Jumbo Ethereum Transactions Negative Tests")
    class JumboEthereumTransactionsNegativeTests {
        private final Stream<Integer> oversizedCases = Stream.of(ABOVE_MAX_SIZE, OVERSIZED_TXN_SIZE);
        private final Stream<Integer> insufficientFeeCases = Stream.of(SMALL_TXN_SIZE, MAX_ALLOWED_SIZE);

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail for oversized data")
        @Order(3)
        public Stream<DynamicTest> jumboTxnWithOversizedDataShouldFail() {
            return oversizedCases.flatMap(txnSize -> hapiTest(
                    logIt("Invalid Jumbo Txn with size: " + (txnSize / 1024) + "KB"),
                    prepareFakeUpgrade(),
                    upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    jumboEthCall(CONTRACT_NAME, FUNCTION_NAME, new byte[txnSize])
                            .hasPrecheck(TRANSACTION_OVERSIZE)
            ));
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail due to insufficient fees")
        @TestFactory
        @Order(4)
        public Stream<DynamicTest> jumboTxnWithInsufficientFeesShouldFail() {
            return insufficientFeeCases.flatMap(txnSize -> hapiTest(
                    logIt("Invalid Jumbo Txn with insufficient fees and size: " + (txnSize / 1024) + "KB"),
                    prepareFakeUpgrade(),
                    upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(1L),
                    jumboEthCall(CONTRACT_NAME, FUNCTION_NAME, new byte[txnSize])
                            .hasPrecheck(INSUFFICIENT_TX_FEE)
            ));
        }
    }
}
