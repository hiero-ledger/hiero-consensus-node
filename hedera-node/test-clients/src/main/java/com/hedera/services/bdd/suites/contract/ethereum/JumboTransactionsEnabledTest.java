// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableReason;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.queries.meta.AccountCreationDetails;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class JumboTransactionsEnabledTest implements LifecycleTest {

    private static final String CONTRACT_CALLDATA_SIZE = "CalldataSize";
    private static final String FUNCTION = "callme";
    private static final int SIX_KB_SIZE = 6 * 1024;
    private static final int MAX_ALLOWED_SIZE = 127 * 1024;
    private static final int ABOVE_MAX_SIZE = 129 * 1024;
    private static final int OVERSIZED_TXN_SIZE = 130 * 1024;

    private record TestCombination(int txnSize, EthTxData.EthTransactionType type) {}

    private record TestCombinationWithGas(
            int txnSize, EthTxData.EthTransactionType type, int gasLimit, int expectedGas) {}

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
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                uploadInitCode(CONTRACT_CALLDATA_SIZE),
                contractCreate(CONTRACT_CALLDATA_SIZE));

        testLifecycle.overrideInClass(Map.of(
                "jumboTransactions.maxBytesPerSec",
                "99999999999", // to avoid throttling
                "contracts.throttle.throttleByGas",
                "false", // to avoid gas throttling
                "hedera.transaction.maxMemoUtf8Bytes",
                "10000" // to avoid memo size limit
                ));
    }

    @HapiTest
    @DisplayName("Jumbo transaction should pass")
    @Tag(MATS)
    public Stream<DynamicTest> jumboTransactionShouldPass() {
        final var jumboPayload = new byte[10 * 1024];
        final var halfJumboPayload = new byte[5 * 1024];
        final var thirdJumboPayload = new byte[3 * 1024];
        final var tooBigPayload = new byte[130 * 1024 + 1];

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),

                // send jumbo payload to non jumbo endpoint
                contractCall(CONTRACT_CALLDATA_SIZE, FUNCTION, jumboPayload)
                        .gas(1_000_000L)
                        .hasPrecheck(TRANSACTION_OVERSIZE)
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

                // send jumbo payload to jumbo endpoint and assert the used gas
                jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, jumboPayload)
                        .gasLimit(800000)
                        .exposingGasTo((s, gasUsed) -> assertEquals(640000, gasUsed)),
                jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, halfJumboPayload)
                        .gasLimit(500000)
                        .exposingGasTo((s, gasUsed) -> assertEquals(400000, gasUsed)),
                jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, thirdJumboPayload)
                        .gasLimit(300000)
                        .exposingGasTo((s, gasUsed) -> assertEquals(240000, gasUsed)));
    }

    @Nested
    @DisplayName("Jumbo Ethereum Transactions Positive Tests")
    class JumboEthereumTransactionsPositiveTests {

        private final Stream<TestCombinationWithGas> positiveBoundariesTestCases = Stream.of(
                new TestCombinationWithGas(SIX_KB_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM, 400_000, 320_000),
                new TestCombinationWithGas(SIX_KB_SIZE, EthTxData.EthTransactionType.EIP2930, 400_000, 320_000),
                new TestCombinationWithGas(SIX_KB_SIZE, EthTxData.EthTransactionType.EIP1559, 400_000, 320_000),
                new TestCombinationWithGas(
                        MAX_ALLOWED_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM, 9_000_000, 7_200_000),
                new TestCombinationWithGas(
                        MAX_ALLOWED_SIZE, EthTxData.EthTransactionType.EIP2930, 9_000_000, 7_200_000),
                new TestCombinationWithGas(
                        MAX_ALLOWED_SIZE, EthTxData.EthTransactionType.EIP1559, 9_000_000, 7_200_000));

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Jumbo Ethereum transactions should pass for valid sizes and expected gas used")
        public Stream<DynamicTest> jumboTxnWithEthereumDataLessThanAllowedKbShouldPass() {
            return positiveBoundariesTestCases.flatMap(test -> {
                var payload = new byte[test.txnSize];
                return hapiTest(
                        logIt("Valid Jumbo Txn | Size: " + (test.txnSize / 1024) + "KB | Type: " + test.type
                                + " | GasLimit: " + test.gasLimit + " | ExpectedGas: " + test.expectedGas),
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoTransfer(
                                tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                        jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload, test.type)
                                .gasLimit(test.gasLimit)
                                .exposingGasTo((s, gasUsed) -> assertEquals(
                                        test.expectedGas,
                                        gasUsed,
                                        "Unexpected gas used for size: " + test.txnSize + ", type: " + test.type)));
            });
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum txn works when alias account is updated to threshold key")
        // JUMBO_P_13
        @Tag(MATS)
        public Stream<DynamicTest> jumboTxnAliasWithThresholdKeyPattern() {
            final var cryptoKey = "cryptoKey";
            final var thresholdKey = "thresholdKey";
            final var aliasCreationTxn = "aliasCreation";
            final var ethereumCallTxn = "jumboTxnFromThresholdKeyAccount";
            final var contract = CONTRACT_CALLDATA_SIZE;
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
                    sourcing(() -> cryptoUpdate(
                                    asAccountString(creationDetails.get().createdId()))
                            .key(thresholdKey)
                            .signedBy(GENESIS, cryptoKey)),

                    // Submit jumbo Ethereum txn, signed with SECP key
                    sourcing(() -> ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .markAsJumboTxn()
                            .nonce(0)
                            .signingWith(cryptoKey)
                            .payingWith(RELAYER)
                            .gasLimit(1_000_000L)
                            .via(ethereumCallTxn)),
                    getTxnRecord(ethereumCallTxn).logged());
        }

        @HapiTest
        @DisplayName("Jumbo transaction with multiple signatures should pass")
        // JUMBO_P_13
        public Stream<DynamicTest> jumboTransactionWithMultipleSignaturesShouldPass() {
            final var cryptoKey = "cryptoKey";
            final var payload = new byte[10 * 1024];
            return hapiTest(
                    newKeyNamed(cryptoKey).shape(SECP_256K1_SHAPE),
                    newKeyNamed("test").shape(SECP_256K1_SHAPE),
                    newKeyNamed("test2").shape(SECP_256K1_SHAPE),
                    newKeyNamed("test3").shape(SECP_256K1_SHAPE),
                    newKeyNamed("test4").shape(SECP_256K1_SHAPE),
                    newKeyNamed("test5").shape(SECP_256K1_SHAPE),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, cryptoKey, ONE_HUNDRED_HBARS)),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, "test", ONE_HUNDRED_HBARS)),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, "test2", ONE_HUNDRED_HBARS)),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, "test3", ONE_HUNDRED_HBARS)),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, "test4", ONE_HUNDRED_HBARS)),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, "test5", ONE_HUNDRED_HBARS)),
                    sourcing(() -> ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .markAsJumboTxn()
                            .signingWith(cryptoKey)
                            .signedBy("test", "test2", "test3", "test4", "test5")
                            .payingWith(RELAYER)
                            .gasLimit(1_000_000L)));
        }
    }

    @Nested
    @DisplayName("Jumbo Ethereum Transactions Negative Tests")
    class JumboEthereumTransactionsNegativeTests {

        private final Stream<TestCombination> oversizedCases = Stream.of(
                new TestCombination(OVERSIZED_TXN_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM),
                new TestCombination(OVERSIZED_TXN_SIZE, EthTxData.EthTransactionType.EIP2930),
                new TestCombination(OVERSIZED_TXN_SIZE, EthTxData.EthTransactionType.EIP1559));

        private final Stream<TestCombinationWithGas> aboveMaxCases = Stream.of(
                new TestCombinationWithGas(ABOVE_MAX_SIZE, EthTxData.EthTransactionType.LEGACY_ETHEREUM, 8_500_000, 0),
                new TestCombinationWithGas(ABOVE_MAX_SIZE, EthTxData.EthTransactionType.EIP2930, 8_500_000, 0),
                new TestCombinationWithGas(ABOVE_MAX_SIZE, EthTxData.EthTransactionType.EIP1559, 8_500_000, 0));

        private final Stream<Integer> insufficientFeeCases = Stream.of(SIX_KB_SIZE, MAX_ALLOWED_SIZE);

        private static byte[] corruptedPayload() {
            return Arrays.copyOf("corruptedPayload".getBytes(StandardCharsets.UTF_8), 128 * 1024);
        }

        @HapiTest
        @DisplayName("Jumbo transaction send to the wrong gRPC endpoint")
        // JUMBO_N_03, JUMBO_N_04, JUMBO_N_05, JUMBO_N_06
        public Stream<DynamicTest> sendToTheWrongGRPCEndpoint() {
            final var sixKbPayload = new byte[6 * 1024];
            final var moreThenSixKbPayload = new byte[6 * 1024 + 1];
            final var limitPayload = new byte[127 * 1024];
            final var tooBigPayload = new byte[130 * 1024];

            return hapiTest(
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, sixKbPayload)
                            .markAsJumboTxn()
                            // Override the hedera functionality to make the framework send the request to the wrong
                            // endpoint
                            .withOverriddenHederaFunctionality(HederaFunctionality.TokenAirdrop)
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .gasLimit(1_000_000L)
                            .orUnavailableStatus(),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, moreThenSixKbPayload)
                            .markAsJumboTxn()
                            // Override the hedera functionality to make the framework send the request to the wrong
                            // endpoint
                            .withOverriddenHederaFunctionality(HederaFunctionality.TokenAirdrop)
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .gasLimit(1_000_000L)
                            .orUnavailableStatus(),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, limitPayload)
                            .markAsJumboTxn()
                            // Override the hedera functionality to make the framework send the request to the wrong
                            // endpoint
                            .withOverriddenHederaFunctionality(HederaFunctionality.TokenAirdrop)
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .gasLimit(1_000_000L)
                            .orUnavailableStatus(),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, tooBigPayload)
                            .markAsJumboTxn()
                            // Override the hedera functionality to make the framework send the request to the wrong
                            // endpoint
                            .withOverriddenHederaFunctionality(HederaFunctionality.TokenAirdrop)
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .gasLimit(1_000_000L)
                            .orUnavailableStatus());
        }

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Jumbo Ethereum transactions should fail for above max size data with TRANSACTION_OVERSIZE")
        // JUMBO_N_01
        public Stream<DynamicTest> jumboTxnWithAboveMaxDataShouldFail() {
            return aboveMaxCases.flatMap(test -> {
                var payload = new byte[test.txnSize];
                return hapiTest(
                        logIt("Invalid Jumbo Txn | Size: " + (test.txnSize / 1024) + "KB | Type: " + test.type
                                + " | GasLimit: " + test.gasLimit + " | ExpectedGas: " + test.expectedGas),
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                        jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload, test.type)
                                .gasLimit(test.gasLimit)
                                .noLogging()
                                .exposingGasTo((s, gasUsed) -> assertEquals(
                                        test.expectedGas,
                                        gasUsed,
                                        "Unexpected gas used for txn size " + test.txnSize + " and type " + test.type))
                                .hasPrecheck(TRANSACTION_OVERSIZE));
            });
        }

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Jumbo Ethereum transactions should fail for oversized data with grpc unavailable status")
        // JUMBO_N_02
        public Stream<DynamicTest> jumboTxnWithOversizedDataShouldFail() {
            return oversizedCases.flatMap(test -> hapiTest(
                    logIt("Invalid Jumbo Txn with size: " + (test.txnSize / 1024) + "KB and type: " + test.type),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, new byte[test.txnSize], test.type)
                            .noLogging()
                            .hasPrecheck(TRANSACTION_OVERSIZE)
                            .orUnavailableStatus()));
        }

        @HapiTest
        @DisplayName("Allows Ethereum jumbo contract create jumbo above max transaction size of 6kb")
        public Stream<DynamicTest> ethereumContractCreateJumboTxnMoreThen6Kb() {
            final var contract = "TokenCreateContract";
            return hapiTest(
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                            .via("autoAccount"),
                    uploadInitCode(contract),
                    ethereumContractCreate(contract)
                            .markAsJumboTxn()
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(RELAYER)
                            .nonce(0)
                            .maxGasAllowance(ONE_HUNDRED_HBARS)
                            .gasLimit(6_000_000L)
                            .via("payTxn"));
        }

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Jumbo Ethereum transactions should fail with corrupted payload")
        // JUMBO_N_17
        public Stream<DynamicTest> jumboTxnWithCorruptedPayloadShouldFail() {
            var balance = new AtomicLong(0L);
            var corruptedTypes = Stream.of(
                    EthTxData.EthTransactionType.LEGACY_ETHEREUM,
                    EthTxData.EthTransactionType.EIP2930,
                    EthTxData.EthTransactionType.EIP1559);
            return corruptedTypes.flatMap(type -> hapiTest(
                    logIt("Corrupted Jumbo Txn of size 128KB and type: " + type),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                    getAccountBalance(RELAYER).exposingBalanceTo(balance::set),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, corruptedPayload())
                            .markAsJumboTxn()
                            .type(type)
                            .payingWith(RELAYER)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .gasLimit(1_000_000L)
                            .hasPrecheck(TRANSACTION_OVERSIZE),
                    getAccountBalance(RELAYER)
                            .exposingBalanceTo(newBalance -> assertTrue(
                                    balance.get() > newBalance,
                                    "Balance should decrease after failed jumbo transaction"))));
        }

        @HapiTest
        @DisplayName("Non-jumbo transaction bigger then 6kb should fail")
        // JUMBO_N_07
        public Stream<DynamicTest> nonJumboTransactionBiggerThen6kb() {
            return hapiTest(
                    cryptoCreate("receiver"),
                    cryptoTransfer(tinyBarsFromTo(GENESIS, "receiver", ONE_HUNDRED_HBARS))
                            .memo(StringUtils.repeat("a", 6145))
                            .hasKnownStatus(TRANSACTION_OVERSIZE)
                            .orUnavailableStatus());
        }

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Three jumbo transactions one after the other")
        // JUMBO_N_08
        public Stream<DynamicTest> treeJumboTransactionOneAfterTheOther() {
            final var payload = new byte[128 * 1024 - 100];
            return hapiTest(
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                            .via("autoAccount"),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .markAsJumboTxn()
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .gasLimit(1_000_000L),
                    sleepFor(1000),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .markAsJumboTxn()
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .gasLimit(1_000_000L),
                    sleepFor(1000),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .markAsJumboTxn()
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .gasLimit(1_000_000L));
        }

        @HapiTest
        @DisplayName("Jumbo Ethereum transactions should fail with wrong signature")
        // JUMBO_N_16
        public Stream<DynamicTest> jumboTxnWithWrongSignatureShouldFail() {
            var payload = new byte[10 * 1024];
            return hapiTest(
                    newKeyNamed("unrelatedKey").shape(SECP_256K1_SHAPE),

                    // Submit jumbo Ethereum txn with wrong key
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .markAsJumboTxn()
                            .signingWith("unrelatedKey")
                            .payingWith(RELAYER)
                            .gasLimit(1_000_000L)
                            .hasPrecheck(INVALID_ACCOUNT_ID));
        }

        @HapiTest
        @DisplayName("Mix of jumbo and non-jumbo transactions")
        public Stream<DynamicTest> mixOfJumboAndNonJumboTransactions() {
            final var payload = new byte[50 * 1024];
            return hapiTest(
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                            .via("autoAccount"),
                    ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .markAsJumboTxn()
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .gasLimit(1_000_000L),
                    cryptoCreate("receiver"),
                    cryptoTransfer(tinyBarsFromTo(GENESIS, "receiver", ONE_HUNDRED_HBARS)));
        }

        @RepeatableHapiTest(RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
        @DisplayName("Jumbo Ethereum transactions should fail due to insufficient payer balance")
        // JUMBO_N_14
        public Stream<DynamicTest> jumboTxnWithInsufficientPayerBalanceShouldFail() {
            return insufficientFeeCases.flatMap(txnSize -> hapiTest(
                    logIt("Invalid Jumbo Txn with insufficient balance and size: " + (txnSize / 1024) + "KB"),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(1L),
                    jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, new byte[txnSize])
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE)));
        }

        @DisplayName("Jumbo transaction gets bytes throttled at ingest")
        @LeakyHapiTest(overrides = {"jumboTransactions.maxBytesPerSec"})
        @Tag(MATS)
        public Stream<DynamicTest> jumboTransactionGetsThrottledAtIngest() {
            final var payloadSize = 127 * 1024;
            final var bytesPerSec = 130 * 1024;
            final var payload = new byte[payloadSize];
            return hapiTest(
                    overriding("jumboTransactions.maxBytesPerSec", String.valueOf(bytesPerSec)),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                    jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload).noLogging(),
                    // Wait for the bytes throttle bucked to be emptied
                    sleepFor(1_000),
                    jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .noLogging()
                            .deferStatusResolution(),
                    jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                            .noLogging()
                            .hasPrecheck(BUSY));
        }

        @HapiTest
        @DisplayName("Privileged account is exempt from bytes throttles")
        @LeakyHapiTest(overrides = {"jumboTransactions.maxBytesPerSec"})
        public Stream<DynamicTest> privilegedAccountIsExemptFromThrottles() {
            final var payloadSize = 127 * 1024;
            final var bytesPerSec = 60 * 1024;
            final var payload = new byte[payloadSize];
            final var initialNonce = new AtomicLong(0);
            return hapiTest(
                    overriding("jumboTransactions.maxBytesPerSec", String.valueOf(bytesPerSec)),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                    withOpContext((spec, op) -> allRunFor(
                            spec,
                            getAccountInfo(DEFAULT_PAYER).exposingEthereumNonceTo(initialNonce::set),
                            ethereumCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload)
                                    .nonce(initialNonce.get())
                                    .markAsJumboTxn()
                                    .gasLimit(1_000_000L)
                                    .noLogging())));
        }

        @HapiTest
        @DisplayName("Can't put jumbo transaction inside of batch")
        public Stream<DynamicTest> canNotPutJumboInsideOfBatch() {
            final var payloadSize = 127 * 1024;
            final var payload = new byte[payloadSize];
            return hapiTest(atomicBatch(jumboEthCall(CONTRACT_CALLDATA_SIZE, FUNCTION, payload))
                    .hasPrecheck(TRANSACTION_OVERSIZE)
                    // If we use subprocess network, the transaction should fail at gRPC level
                    .orUnavailableStatus());
        }
    }
}
