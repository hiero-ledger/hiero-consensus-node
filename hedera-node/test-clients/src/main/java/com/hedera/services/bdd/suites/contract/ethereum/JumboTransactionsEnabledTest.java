// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.PropertySource.asAccountString;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.queries.meta.AccountCreationDetails;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(UPGRADE)
@Order(Integer.MAX_VALUE - 123)
@HapiTestLifecycle
@OrderedInIsolation
public class JumboTransactionsEnabledTest implements LifecycleTest {

    private static final String CONTRACT_CALLDATA_SIZE = "CalldataSize";
    private static final String FUNCTION = "callme";
    private static final int SMALL_TXN_SIZE = 6 * 1024;
    private static final int MAX_ALLOWED_SIZE = 127 * 1024;
    private static final int ABOVE_MAX_SIZE = 129 * 1024;
    private static final int OVERSIZED_TXN_SIZE = 130 * 1024;

    private record TestCombination(int txnSize, EthTxData.EthTransactionType type) {}

    private static HapiEthereumCall jumboEthCall(String contract, String function, byte[] payload) {
        return jumboEthCall(contract, function, payload, EthTxData.EthTransactionType.EIP1559);
    }

    private static HapiEthereumCall jumboEthCall(
            String contract, String function, byte[] payload, EthTxData.EthTransactionType type) {
        return ethereumCall(contract, function, payload)
                .markAsJumboTxn()
                .type(type)
                .signingWith(SECP_256K1_SOURCE_KEY)
                .payingWith(RELAYER)
                .gasLimit(1_000_000L);
    }

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                uploadInitCode(CONTRACT_CALLDATA_SIZE),
                contractCreate(CONTRACT_CALLDATA_SIZE));
    }

    @HapiTest
    @Order(1)
    @DisplayName("Jumbo transaction should fail if feature flag is disabled")
    public Stream<DynamicTest> jumboTransactionDisabled() {

        final var jumboPayload = new byte[10 * 1024];
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, jumboPayload)
                        .payingWith(RELAYER)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L)
                        // gRPC request terminated immediately
                        .orUnavailableStatus());
    }

    @HapiTest
    @Order(2)
    @DisplayName("Enable jumbo transactions")
    public Stream<DynamicTest> jumboTransactionShouldBeEnabled() {
        return hapiTest(
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()));
    }

    @HapiTest
    @Order(3)
    @DisplayName("Jumbo transaction should pass")
    public Stream<DynamicTest> jumboTransactionShouldPass() {
        final var jumboPayload = new byte[10 * 1024];
        final var tooBigPayload = new byte[130 * 1024 + 1];
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),

                // The feature flag is only used once at startup (when building gRPC ServiceDefinitions),
                // so we can't toggle it via overriding(). Instead, we need to upgrade to the config version.
                //                prepareFakeUpgrade(),
                //                upgradeToNextConfigVersion(Map.of("jumboTransactions.isEnabled", "true"), noOp()),

                // send jumbo payload to non jumbo endpoint
                contractCall(CONTRACT_CALLDATA_SIZE, FUNCTION, jumboPayload)
                        .gas(1_000_000L)
                        // gRPC request terminated immediately
                        .orUnavailableStatus(),

                // send too big payload to jumbo endpoint
                ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, tooBigPayload)
                        .payingWith(RELAYER)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L)
                        // gRPC request terminated immediately
                        .orUnavailableStatus(),

                // send jumbo payload to jumbo endpoint
                ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, jumboPayload)
                        .payingWith(RELAYER)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .markAsJumboTxn()
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(1_000_000L));
    }

    @Nested
    @OrderedInIsolation
    @DisplayName("Jumbo Ethereum Transactions Positive Tests")
    class JumboEthereumTransactionsPositiveTests {
        private final Stream<TestCombination> positiveBoundariesTestCases = Stream.of(
                new TestCombination(MAX_ALLOWED_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM),
                new TestCombination(MAX_ALLOWED_SIZE, EthTxData.EthTransactionType.EIP2930),
                new TestCombination(MAX_ALLOWED_SIZE, EthTxData.EthTransactionType.EIP1559),
                new TestCombination(SMALL_TXN_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM),
                new TestCombination(SMALL_TXN_SIZE, EthTxData.EthTransactionType.EIP2930),
                new TestCombination(SMALL_TXN_SIZE, EthTxData.EthTransactionType.EIP1559));

        @HapiTest
        @Order(4)
        @DisplayName("Jumbo Ethereum transactions should pass for valid sizes")
        // JUMBO_P_01, JUMBO_P_02, JUMBO_P_03, JUMBO_P_04
        public Stream<DynamicTest> jumboTxnWithEthereumDataLessThanAllowedKbShouldPass() {
            return positiveBoundariesTestCases.flatMap(test -> hapiTest(
                    logIt("Valid Jumbo Txn with size: " + (test.txnSize / 1024) + "KB" + " and type: " + test.type),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                    jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, new byte[test.txnSize], test.type)));
        }
    }

    @HapiTest
    @Order(12)
    @DisplayName(
            "Jumbo Ethereum txn works when alias account is updated to threshold key (following token creation pattern)")
    public Stream<DynamicTest> jumboTxnAliasWithThresholdKeyPattern() {
        final var cryptoKey = "cryptoKey";
        final var thresholdKey = "thresholdKey";
        final var aliasCreationTxn = "aliasCreation";
        final var ethereumCallTxn = "jumboTxnFromThresholdKeyAccount";
        final var contract = CONTRACT_CALLDATA_SIZE;
        final var function = FUNCTION;
        final var payload = new byte[127 * 1024];

        final AtomicReference<byte[]> rawPublicKey = new AtomicReference<>();
        final AtomicReference<AccountCreationDetails> creationDetails = new AtomicReference<>();

        return hapiTest(

                // Create SECP key and extract raw bytes
                newKeyNamed(cryptoKey)
                        .shape(SECP256K1_ON)
                        .exposingKeyTo(
                                k -> rawPublicKey.set(k.getECDSASecp256K1().toByteArray())),

                // Create alias account via cryptoTransfer
                cryptoTransfer(tinyBarsFromToWithAlias(GENESIS, cryptoKey, 2 * ONE_HUNDRED_HBARS))
                        .via(aliasCreationTxn),

                // Extract AccountCreationDetails for EVM address and account ID
                getTxnRecord(aliasCreationTxn)
                        .exposingCreationDetailsTo(details -> creationDetails.set(details.getFirst())),

                // Create threshold key using SECP key and contract
                newKeyNamed(thresholdKey)
                        .shape(threshOf(1, PREDEFINED_SHAPE, CONTRACT).signedWith(sigs(cryptoKey, contract))),

                // Update alias account to use threshold key
                sourcing(
                        () -> cryptoUpdate(asAccountString(creationDetails.get().createdId()))
                                .key(thresholdKey)
                                .signedBy(GENESIS, cryptoKey)),

                // Submit jumbo Ethereum txn, signed with SECP key
                sourcing(() -> ethereumCall(contract, function, payload)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .markAsJumboTxn()
                        .nonce(0)
                        .signingWith(cryptoKey)
                        .payingWith(RELAYER)
                        .gasLimit(1_000_000L)
                        .via(ethereumCallTxn)),
                getTxnRecord(ethereumCallTxn).logged());
    }

    @Nested
    @OrderedInIsolation
    @DisplayName("Jumbo Ethereum Transactions Negative Tests")
    class JumboEthereumTransactionsNegativeTests {
        private final Stream<TestCombination> aboveMaxCases = Stream.of(
                new TestCombination(ABOVE_MAX_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM),
                new TestCombination(ABOVE_MAX_SIZE, EthTxData.EthTransactionType.EIP2930),
                new TestCombination(ABOVE_MAX_SIZE, EthTxData.EthTransactionType.EIP1559));
        private final Stream<TestCombination> oversizedCases = Stream.of(
                new TestCombination(OVERSIZED_TXN_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM),
                new TestCombination(OVERSIZED_TXN_SIZE, EthTxData.EthTransactionType.EIP2930),
                new TestCombination(OVERSIZED_TXN_SIZE, EthTxData.EthTransactionType.EIP1559));

        private final Stream<Integer> insufficientFeeCases = Stream.of(SMALL_TXN_SIZE, MAX_ALLOWED_SIZE);

        private static byte[] corruptedPayload() {
            return Arrays.copyOf("corruptedPayload".getBytes(StandardCharsets.UTF_8), 128 * 1024);
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail for above max size data with TRANSACTION_OVERSIZE")
        @Order(5)
        // JUMBO_N_01
        public Stream<DynamicTest> jumboTxnWithAboveMaxDataShouldFail() {
            return aboveMaxCases.flatMap(test -> hapiTest(
                    logIt("Invalid Jumbo Txn with size: " + (test.txnSize / 1024) + "KB and type: " + test.type),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, new byte[test.txnSize], test.type)
                            .noLogging()
                            .hasPrecheck(TRANSACTION_OVERSIZE)));
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail for oversized data with grpc unavailable status")
        @Order(6)
        // JUMBO_N_02
        public Stream<DynamicTest> jumboTxnWithOversizedDataShouldFail() {
            return oversizedCases.flatMap(test -> hapiTest(
                    logIt("Invalid Jumbo Txn with size: " + (test.txnSize / 1024) + "KB and type: " + test.type),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, new byte[test.txnSize], test.type)
                            .noLogging()
                            .orUnavailableStatus()));
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail with corrupted payload")
        @Order(6)
        // JUMBO_N_17
        public Stream<DynamicTest> jumboTxnWithCorruptedPayloadShouldFail() {
            var corruptedTypes = Stream.of(
                    EthTxData.EthTransactionType.LEGACY_ETHEREUM,
                    EthTxData.EthTransactionType.EIP2930,
                    EthTxData.EthTransactionType.EIP1559);
            return corruptedTypes.flatMap(type -> hapiTest(
                    logIt("Corrupted Jumbo Txn of size 128KB and type: " + type),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, corruptedPayload())
                            .markAsJumboTxn()
                            .type(type)
                            .payingWith(RELAYER)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .gasLimit(1_000_000L)
                            .hasPrecheck(TRANSACTION_OVERSIZE)));
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail with wrong signature")
        @Order(7)
        // JUMBO_N_16
        public Stream<DynamicTest> jumboTxnWithWrongSignatureShouldFail() {
            var payload = new byte[10 * 1024];
            return hapiTest(
                    newKeyNamed("string").shape(ED25519),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .markAsJumboTxn()
                            .signingWith("string")
                            .payingWith(RELAYER)
                            .gasLimit(1_000_000L)
                            .hasPrecheck(INVALID_SIGNATURE));
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail due to insufficient payer balance")
        @Order(8)
        // JUMBO_N_14
        public Stream<DynamicTest> jumboTxnWithInsufficientPayerBalanceShouldFail() {
            return insufficientFeeCases.flatMap(txnSize -> hapiTest(
                    logIt("Invalid Jumbo Txn with insufficient balance and size: " + (txnSize / 1024) + "KB"),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(1L),
                    jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, new byte[txnSize])
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE)));
        }
    }
}
