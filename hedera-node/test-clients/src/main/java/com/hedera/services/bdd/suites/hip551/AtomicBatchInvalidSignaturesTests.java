package com.hedera.services.bdd.suites.hip551;

import com.hedera.hapi.node.base.TokenSupplyType;
import com.hedera.services.bdd.junit.HapiTestLifecycle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.RECEIVER_SIG_REQUIRED;
import static com.hedera.hapi.node.token.schema.TokenCreateTransactionBodySchema.AUTO_RENEW_ACCOUNT;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.EMPTY_KEY_LIST;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.MAX_CALL_DATA_SIZE;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.HapiSuite.salted;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.ADMIN_KEY;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.fees.CryptoServiceFeesSuite.FEES_ACCOUNT;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.CIVILIAN;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.ThrottleDefsLoader.protoDefsFromResource;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class AtomicBatchInvalidSignaturesTests {


    private static final String DEFAULT_BATCH_OPERATOR = "DEFAULT_BATCH_OPERATOR";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @Nested
    @DisplayName("Contract Association Batch Tests")
    class ContractAssociationBatch {


        @HapiTest
        @DisplayName("Batch with token creation, contract creation, and association - missing admin key")
        public Stream<DynamicTest> fullBatchTokenContractAssociationWithoutAdminKey() {
            final var batchOperator = "batchOperator";
            final var misc = "someToken";
            final var contract = "defaultContract";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),

                    // Complete batch: create token, create contract, associate them
                    atomicBatch(
                            tokenCreate(misc)
                                    .batchKey(batchOperator),

                            createDefaultContract(contract)
                                    .omitAdminKey()
                                    .batchKey(batchOperator),

                            tokenAssociate(contract, misc)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify nothing was created due to batch atomicity
                    getTokenInfo(misc).nodePayment(ONE_HBAR).hasAnswerOnlyPrecheck(INVALID_TOKEN_ID),
                    getContractInfo(contract).nodePayment(ONE_HBAR).hasAnswerOnlyPrecheck(INVALID_CONTRACT_ID)
            );
        }

        @HapiTest
        @DisplayName("Batch with multiple contract associations - mixed admin key scenarios")
        public Stream<DynamicTest> mixedContractAssociationScenarios() {
            final var batchOperator = "batchOperator";
            final var token1 = "token1";
            final var token2 = "token2";
            final var contractWithKey = "contractWithKey";
            final var contractWithoutKey = "contractWithoutKey";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(token1),
                    tokenCreate(token2),
                    createDefaultContract(contractWithKey), // Has admin key
                    createDefaultContract(contractWithoutKey).omitAdminKey(), // No admin key

                    // Batch mixing valid and invalid associations
                    atomicBatch(
                            // This should work - contract has admin key
                            tokenAssociate(contractWithKey, token1)
                                    .batchKey(batchOperator),

                            // This should fail - contract has no admin key
                            tokenAssociate(contractWithoutKey, token2)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify no associations occurred due to batch failure
                    getContractInfo(contractWithKey)
                            .hasNoTokenRelationship(token1));
            );
        }


        @HapiTest
        @DisplayName("Successful batch with proper admin keys")
        public Stream<DynamicTest> successfulBatchWithProperAdminKeys() {
            final var batchOperator = "batchOperator";
            final var adminKey = "adminKey";
            final var token1 = "token1";
            final var token2 = "token2";
            final var contract = "contract";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(adminKey),

                    // Successful batch: create everything with proper keys
                    atomicBatch(
                            tokenCreate(token1)
                                    .batchKey(batchOperator),

                            tokenCreate(token2)
                                    .batchKey(batchOperator),

                            createDefaultContract(contract)
                                    .adminKey(adminKey)
                                    .batchKey(batchOperator),

                            tokenAssociate(contract, token1)
                                    .batchKey(batchOperator),

                            tokenAssociate(contract, token2)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator),

                    // Verify all operations succeeded
                    getTokenInfo(token1).logged(),
                    getTokenInfo(token2).logged(),
                    getContractInfo(contract).logged(),
                    // Verify associations by checking token balances
                    getAccountBalance(contract).hasTokenBalance(token1, 0),
                    getAccountBalance(contract).hasTokenBalance(token2, 0)
            );
        }

        @HapiTest
        @DisplayName("Batch with contract admin key revocation during execution")
        public Stream<DynamicTest> contractAdminKeyRevocationDuringBatch() {
            final var batchOperator = "batchOperator";
            final var adminKey = "adminKey";
            final var misc = "someToken";
            final var contract = "contract";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(adminKey),
                    tokenCreate(misc),
                    createDefaultContract(contract).adminKey(adminKey),

                    atomicBatch(
                            // First: Remove the admin key from contract
                            contractUpdate(contract)
                                    .newKey(EMPTY_KEY_LIST)
                                    .batchKey(batchOperator),

                            // Second: Try to associate token (should fail - no admin key)
                            tokenAssociate(contract, misc)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify contract still has original admin key due to rollback
                    getContractInfo(contract)
                            .hasNoTokenRelationship(misc));
        }

        @HapiTest
        @DisplayName("Batch with multiple contracts and complex association patterns")
        public Stream<DynamicTest> complexContractAssociationPatterns() {
            final var batchOperator = "batchOperator";
            final var adminKey1 = "adminKey1";
            final var adminKey2 = "adminKey2";
            final var token1 = "token1";
            final var token2 = "token2";
            final var contract1 = "contract1";
            final var contract2 = "contract2";
            final var contractNoKey = "contractNoKey";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(adminKey1),
                    newKeyNamed(adminKey2),
                    tokenCreate(token1),
                    tokenCreate(token2),
                    createDefaultContract(contract1).adminKey(adminKey1),
                    createDefaultContract(contract2).adminKey(adminKey2),

                    atomicBatch(
                            // Create contract without admin key
                            createDefaultContract(contractNoKey)
                                    .omitAdminKey()
                                    .batchKey(batchOperator),

                            // Valid associations
                            tokenAssociate(contract1, token1)
                                    .batchKey(batchOperator),

                            tokenAssociate(contract2, token2)
                                    .batchKey(batchOperator),

                            // Invalid association - contract has no admin key
                            tokenAssociate(contractNoKey, token1)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify rollback - no new contracts or associations
                    getContractInfo(contractNoKey).nodePayment(ONE_HBAR).hasAnswerOnlyPrecheck(INVALID_CONTRACT_ID),
                    getContractInfo(contract1).hasNoTokenRelationship(token1)
                    .hasNoTokenRelationship(token2),
                    getContractInfo(contract2).hasNoTokenRelationship(token1)
                            .hasNoTokenRelationship(token2)
            );
        }

        @HapiTest
        @DisplayName("Batch with contract deletion and association attempt")
        public Stream<DynamicTest> contractDeletionAndAssociationAttempt() {
            final var batchOperator = "batchOperator";
            final var adminKey = "adminKey";
            final var misc = "someToken";
            final var contract = "contract";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(adminKey),
                    tokenCreate(misc),
                    createDefaultContract(contract).adminKey(adminKey),

                    atomicBatch(
                            // First: Delete the contract
                            contractDelete(contract)
                                    .batchKey(batchOperator),

                            // Second: Try to associate token with deleted contract
                            tokenAssociate(contract, misc)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify contract still exists due to batch rollback
                    getContractInfo(contract).logged(),
                    // Verify no association occurred
                    getAccountBalance(contract).hasTokenBalance(misc, 0)
            );
        }
    }

    @Nested
    @DisplayName("Token Creation Batch Tests")
    class TokenCreationBatchTests {

        @HapiTest
        @DisplayName("Batch with invalid token create transactions")
        public Stream<DynamicTest> batchWithInvalidTokenCreateTransactions() {
            final var batchOperator = "batchOperator";
            final var alice = "ALICE";
            final var validToken = "validToken";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(alice).balance(ONE_HUNDRED_HBARS),

                    // Batch with missing token name - should fail entire batch
                    atomicBatch(
                            cryptoCreate("account1").batchKey(batchOperator),
                            tokenCreate(null).batchKey(batchOperator), // Invalid - missing name
                            cryptoCreate("account2").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify no accounts were created due to batch failure
                    getAccountBalance("account1").hasTinyBars(0L),
                    getAccountBalance("account2").hasTinyBars(0L),

                    // Batch with invalid signature - should fail entire batch
                    atomicBatch(
                            cryptoCreate("account3").batchKey(batchOperator),
                            tokenCreate("invalidSigToken")
                                    .treasury(alice)
                                    .batchKey(batchOperator)
                                    .signedBy(DEFAULT_PAYER), // Missing alice signature
                            cryptoCreate("account4").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify no accounts were created due to batch failure
                    getAccountBalance("account3").hasTinyBars(0L),
                    getAccountBalance("account4").hasTinyBars(0L)
            );
        }

        @HapiTest
        @DisplayName("Batch with missing treasury signature failure")
        public Stream<DynamicTest> batchWithMissingTreasurySignature() {
            final var batchOperator = "batchOperator";
            final var memo = "JUMP";
            final var saltedName = salted("PRIMARY");
            final var pauseKey = "pauseKey";
            final var tokenName = "PRIMARY";
            final var successfulToken = "SUCCESSFUL_TOKEN";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TOKEN_TREASURY).balance(0L),
                    cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed("freezeKey"),
                    newKeyNamed("kycKey"),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed("wipeKey"),
                    newKeyNamed("feeScheduleKey"),
                    newKeyNamed(pauseKey),

                    // Batch that should fail due to missing treasury signature
                    atomicBatch(
                            cryptoCreate("testAccount1").batchKey(batchOperator),
                            tokenCreate(tokenName)
                                    .supplyType(TokenSupplyType.FINITE)
                                    .entityMemo(memo)
                                    .name(saltedName)
                                    .treasury(TOKEN_TREASURY)
                                    .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                    .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                    .maxSupply(1000)
                                    .initialSupply(500)
                                    .decimals(1)
                                    .adminKey(ADMIN_KEY)
                                    .freezeKey("freezeKey")
                                    .kycKey("kycKey")
                                    .supplyKey(SUPPLY_KEY)
                                    .wipeKey("wipeKey")
                                    .feeScheduleKey("feeScheduleKey")
                                    .pauseKey(pauseKey)
                                    .batchKey(batchOperator)
                                    .signedBy(DEFAULT_PAYER, ADMIN_KEY, AUTO_RENEW_ACCOUNT), // Missing TOKEN_TREASURY
                            cryptoCreate("testAccount2").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify nothing was created due to batch failure
                    getAccountBalance("testAccount1").hasTinyBars(0L),
                    getAccountBalance("testAccount2").hasTinyBars(0L),
                    getTokenInfo(tokenName).nodePayment(ONE_HBAR).hasAnswerOnlyPrecheck(INVALID_TOKEN_ID)
            );
        }

        @HapiTest
        @DisplayName("Batch with fee collector signing requirements")
        public Stream<DynamicTest> batchFeeCollectorSigningRequirements() {
            final var batchOperator = "batchOperator";
            final var customFeesKey = "customFeesKey";
            final var htsCollector = "htsCollector";
            final var hbarCollector = "hbarCollector";
            final var tokenCollector = "tokenCollector";
            final var feeDenom = "feeDenom";
            final var token = "token";
            final var successfulToken = "successfulToken";
            final var numerator = 1L;
            final var denominator = 10L;
            final var minimumToCollect = 1L;
            final var maximumToCollect = 10L;
            final var htsAmount = 5L;
            final var hbarAmount = 100L;

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(customFeesKey),
                    cryptoCreate(htsCollector).receiverSigRequired(true),
                    cryptoCreate(hbarCollector),
                    cryptoCreate(tokenCollector),
                    cryptoCreate(TOKEN_TREASURY),
                    tokenCreate(feeDenom).treasury(htsCollector),

                    // Batch with invalid fee collector signature - should fail
                    atomicBatch(
                            cryptoCreate("testAccount").batchKey(batchOperator),
                            tokenCreate(token)
                                    .treasury(TOKEN_TREASURY)
                                    .withCustom(fractionalFee(
                                            numerator, 0, minimumToCollect, OptionalLong.of(maximumToCollect), tokenCollector))
                                    .batchKey(batchOperator)
                                    .signedBy(DEFAULT_PAYER, TOKEN_TREASURY), // Missing tokenCollector signature
                            cryptoCreate("testAccount2").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify nothing was created
                    getAccountBalance("testAccount").hasTinyBars(0L),
                    getAccountBalance("testAccount2").hasTinyBars(0L),
                    getTokenInfo(token).nodePayment(ONE_HBAR).hasAnswerOnlyPrecheck(INVALID_TOKEN_ID),

                    // Batch with missing HTS collector signature - should fail
                    atomicBatch(
                            cryptoCreate("testAccount3").batchKey(batchOperator),
                            tokenCreate("token2")
                                    .treasury(TOKEN_TREASURY)
                                    .withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
                                    .batchKey(batchOperator)
                                    .signedBy(DEFAULT_PAYER, TOKEN_TREASURY), // Missing htsCollector signature
                            cryptoCreate("testAccount4").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify nothing was created
                    getAccountBalance("testAccount3").hasTinyBars(0L),
                    getAccountBalance("testAccount4").hasTinyBars(0L),

                    // Successful batch with all proper signatures
                    atomicBatch(
                            cryptoCreate("successAccount").batchKey(batchOperator),
                            tokenCreate(successfulToken)
                                    .treasury(TOKEN_TREASURY)
                                    .withCustom(fixedHbarFee(hbarAmount, hbarCollector))
                                    .withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
                                    .withCustom(fractionalFee(
                                            numerator,
                                            denominator,
                                            minimumToCollect,
                                            OptionalLong.of(maximumToCollect),
                                            tokenCollector))
                                    .batchKey(batchOperator)
                                    .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, htsCollector, tokenCollector),
                            cryptoCreate("successAccount2").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator),

                    // Verify all operations succeeded
                    getAccountBalance("successAccount").hasTinyBars(0L),
                    getAccountBalance("successAccount2").hasTinyBars(0L),
                    getTokenInfo(successfulToken)
                            .hasCustom(fixedHbarFeeInSchedule(hbarAmount, hbarCollector))
                            .hasCustom(fixedHtsFeeInSchedule(htsAmount, feeDenom, htsCollector))
                            .hasCustom(fractionalFeeInSchedule(
                                    numerator,
                                    denominator,
                                    minimumToCollect,
                                    OptionalLong.of(maximumToCollected),
                                    false,
                                    tokenCollector))
            );
        }

        @HapiTest
        @DisplayName("Batch creation requires appropriate signatures")
        public Stream<DynamicTest> batchCreationRequiresAppropriateSigs() {
            final var batchOperator = "batchOperator";
            final var payer = "PAYER";
            final var successfulToken = "successfulToken";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(payer).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TOKEN_TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),

                    // Batch with missing admin key signature - should fail
                    atomicBatch(
                            cryptoCreate("account1").batchKey(batchOperator),
                            tokenCreate("shouldntWork")
                                    .treasury(TOKEN_TREASURY)
                                    .payingWith(payer)
                                    .adminKey(ADMIN_KEY)
                                    .batchKey(batchOperator)
                                    .signedBy(payer), // Missing ADMIN_KEY signature
                            cryptoCreate("account2").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify nothing was created
                    getAccountBalance("account1").hasTinyBars(0L),
                    getAccountBalance("account2").hasTinyBars(0L),

                    // Batch with missing treasury signature - should fail
                    atomicBatch(
                            cryptoCreate("account3").batchKey(batchOperator),
                            tokenCreate("shouldntWorkEither")
                                    .treasury(TOKEN_TREASURY)
                                    .payingWith(payer)
                                    .adminKey(ADMIN_KEY)
                                    .batchKey(batchOperator)
                                    .signedBy(payer, ADMIN_KEY), // Missing TOKEN_TREASURY signature
                            cryptoCreate("account4").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify nothing was created
                    getAccountBalance("account3").hasTinyBars(0L),
                    getAccountBalance("account4").hasTinyBars(0L),

                    // Successful batch with all required signatures
                    atomicBatch(
                            cryptoCreate("successAccount1").batchKey(batchOperator),
                            tokenCreate(successfulToken)
                                    .treasury(TOKEN_TREASURY)
                                    .payingWith(payer)
                                    .adminKey(ADMIN_KEY)
                                    .batchKey(batchOperator)
                                    .signedBy(payer, ADMIN_KEY, TOKEN_TREASURY),
                            cryptoCreate("successAccount2").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator),

                    // Verify all operations succeeded
                    getAccountBalance("successAccount1").hasTinyBars(0L),
                    getAccountBalance("successAccount2").hasTinyBars(0L),
                    getTokenInfo(successfulToken)
                            .hasTreasury(TOKEN_TREASURY)
                            .logged()
            );
        }

        @HapiTest
        @DisplayName("Mixed valid and invalid token operations in batch")
        public Stream<DynamicTest> mixedValidInvalidTokenOperationsInBatch() {
            final var batchOperator = "batchOperator";
            final var alice = "alice";
            final var bob = "bob";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(alice).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),

                    // Batch mixing valid and invalid operations
                    atomicBatch(
                            // Valid account creation
                            cryptoCreate("validAccount").batchKey(batchOperator),

                            // Valid token creation
                            tokenCreate("validToken")
                                    .treasury(alice)
                                    .batchKey(batchOperator)
                                    .signedBy(DEFAULT_PAYER, alice),

                            // Invalid token creation (missing treasury signature)
                            tokenCreate("invalidToken")
                                    .treasury(bob)
                                    .adminKey(ADMIN_KEY)
                                    .batchKey(batchOperator)
                                    .signedBy(DEFAULT_PAYER, ADMIN_KEY), // Missing bob signature

                            // Another valid account creation
                            cryptoCreate("anotherValidAccount").batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify entire batch was rolled back - nothing should exist
                    getAccountBalance("validAccount").hasTinyBars(0L),
                    getAccountBalance("anotherValidAccount").hasTinyBars(0L),
                    getTokenInfo("validToken").nodePayment(ONE_HBAR).hasAnswerOnlyPrecheck(INVALID_TOKEN_ID),
                    getTokenInfo("invalidToken").nodePayment(ONE_HBAR).hasAnswerOnlyPrecheck(INVALID_TOKEN_ID)
            );
        }
    }
}
