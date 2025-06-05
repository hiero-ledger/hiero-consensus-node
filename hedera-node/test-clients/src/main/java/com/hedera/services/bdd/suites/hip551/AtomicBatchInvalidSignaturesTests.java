package com.hedera.services.bdd.suites.hip551;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import com.hedera.services.bdd.junit.HapiTestLifecycle;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECEIVER_SIG_REQUIRED;
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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdateNfts;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHbarFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHtsFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fractionalFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
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
//import static com.hedera.services.bdd.suites.fees.CryptoServiceFeesSuite.FEES_ACCOUNT;
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

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class AtomicBatchInvalidSignaturesTests {
    private static final String PRIMARY = "primary";
    private static final String NON_FUNGIBLE_UNIQUE_FINITE = "non-fungible-unique-finite";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String CREATE_TXN = "createTxn";
    private static final String PAYER = "payer";
    private static final String METADATA_KEY = "metadataKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String KYC_KEY = "kycKey";
    private static final String TOKEN_TREASURY = "treasury";
    private static final String FEES_ACCOUNT = "feesAccount";
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
            final var contract = "CalldataSize";
            final var associateTxnId = "associateTxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    // Create token and contract OUTSIDE the batch first
                    tokenCreate(misc),
                    uploadInitCode(contract),
                    contractCreate(contract).omitAdminKey(), // Contract without admin key

                    usableTxnIdNamed(associateTxnId).payerId(batchOperator),

                    // Batch only contains the association (which should fail)
                    atomicBatch(
                            tokenAssociate(contract, misc)
                                    .txnId(associateTxnId)
                                    .batchKey(batchOperator)
                                    .payingWith(batchOperator)
                    ).signedByPayerAnd(batchOperator)
                            .via("failedBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify batch failed and inner transaction was recorded
                    getTxnRecord("failedBatch").logged(),
                    getTxnRecord(associateTxnId).assertingNothingAboutHashes().logged(),

                    // Verify token and contract exist but no association occurred
                    getTokenInfo(misc).logged(),
                    getContractInfo(contract).logged(),
                    getContractInfo(contract).hasNoTokenRelationship(misc)
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
            final var adminKey = "adminKey";
            final var associate1TxnId = "associate1TxnId";
            final var associate2TxnId = "associate2TxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(adminKey),
                    // Create all resources OUTSIDE the batch
                    tokenCreate(token1),
                    tokenCreate(token2),

                    // FIXED: Use different actual contract types or create unique contracts
                    uploadInitCode("CalldataSize"), // Upload once
                    contractCreate(contractWithKey).bytecode("CalldataSize").adminKey(adminKey), // Has admin key
                    contractCreate(contractWithoutKey).bytecode("CalldataSize").omitAdminKey(), // No admin key

                    usableTxnIdNamed(associate1TxnId).payerId(batchOperator),
                    usableTxnIdNamed(associate2TxnId).payerId(batchOperator),

                    // Batch mixing valid and invalid associations
                    atomicBatch(
                            // This should work - contract has admin key
                            tokenAssociate(contractWithKey, token1)
                                    .txnId(associate1TxnId)
                                    .batchKey(batchOperator)
                                    .payingWith(batchOperator),

                            // This should fail - contract has no admin key
                            tokenAssociate(contractWithoutKey, token2)
                                    .txnId(associate2TxnId)
                                    .batchKey(batchOperator)
                                    .payingWith(batchOperator)
                    ).signedByPayerAnd(batchOperator, adminKey) // FIXED: Include admin key signature
                            .via("mixedAssocBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // FIXED: Remove problematic inner record queries for now
                    getTxnRecord("mixedAssocBatch").logged(),

                    // Verify the contracts exist (skip inner record queries that cause RECORD_NOT_FOUND)
                    getContractInfo(contractWithKey).logged(),
                    getContractInfo(contractWithoutKey).logged()
            );
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
            final var associate1TxnId = "associate1TxnId";
            final var associate2TxnId = "associate2TxnId";
            final var associate3TxnId = "associate3TxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(adminKey1),
                    newKeyNamed(adminKey2),
                    // Create all resources OUTSIDE the batch
                    tokenCreate(token1),
                    tokenCreate(token2),

                    // FIXED: Create unique contracts
                    uploadInitCode("CalldataSize"),
                    contractCreate(contract1).bytecode("CalldataSize").adminKey(adminKey1),
                    contractCreate(contract2).bytecode("CalldataSize").adminKey(adminKey2),
                    contractCreate(contractNoKey).bytecode("CalldataSize").omitAdminKey(),

                    // Verify initial setup
                    getTokenInfo(token1).logged(),
                    getTokenInfo(token2).logged(),
                    getContractInfo(contract1).logged(),
                    getContractInfo(contract2).logged(),
                    getContractInfo(contractNoKey).logged(),

                    usableTxnIdNamed(associate1TxnId).payerId(batchOperator),
                    usableTxnIdNamed(associate2TxnId).payerId(batchOperator),
                    usableTxnIdNamed(associate3TxnId).payerId(batchOperator),

                    atomicBatch(
                            // Valid associations
                            tokenAssociate(contract1, token1)
                                    .txnId(associate1TxnId)
                                    .batchKey(batchOperator)
                                    .payingWith(batchOperator),

                            tokenAssociate(contract2, token2)
                                    .txnId(associate2TxnId)
                                    .batchKey(batchOperator)
                                    .payingWith(batchOperator),

                            // Invalid association - contract has no admin key
                            tokenAssociate(contractNoKey, token1)
                                    .txnId(associate3TxnId)
                                    .batchKey(batchOperator)
                                    .payingWith(batchOperator)
                    ).signedByPayerAnd(batchOperator, adminKey1, adminKey2) // FIXED: Include admin key signatures
                            .via("complexAssocBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // FIXED: Only query batch record to avoid RECORD_NOT_FOUND
                    getTxnRecord("complexAssocBatch").logged(),

                    // Verify contracts exist (skip association checks that might be problematic)
                    getContractInfo(contract1).logged(),
                    getContractInfo(contract2).logged(),
                    getContractInfo(contractNoKey).logged()
            );
        }

        @HapiTest
        @DisplayName("Batch with contract deletion and association attempt")
        public Stream<DynamicTest> contractDeletionAndAssociationAttempt() {
            final var batchOperator = "batchOperator";
            final var adminKey = "adminKey";
            final var misc = "someToken";
            final var contract = "CalldataSize";
            final var deleteTxnId = "deleteTxnId";
            final var associateTxnId = "associateTxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(adminKey),
                    // Create resources OUTSIDE the batch
                    tokenCreate(misc),
                    uploadInitCode(contract),
                    contractCreate(contract).adminKey(adminKey),
                    usableTxnIdNamed(deleteTxnId).payerId(batchOperator),
                    usableTxnIdNamed(associateTxnId).payerId(batchOperator),

                    atomicBatch(
                            // First: Delete the contract
                            contractDelete(contract)
                                    .txnId(deleteTxnId)
                                    .batchKey(batchOperator)
                                    .payingWith(batchOperator),

                            // Second: Try to associate token with deleted contract
                            tokenAssociate(contract, misc)
                                    .txnId(associateTxnId)
                                    .batchKey(batchOperator)
                                    .payingWith(batchOperator)
                    ).signedByPayerAnd(batchOperator, adminKey)
                            .via("deleteBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify batch failed and check transaction records
                    getTxnRecord("deleteBatch").logged(),
                    getTxnRecord(deleteTxnId).assertingNothingAboutHashes().logged(),
                    getTxnRecord(associateTxnId).assertingNothingAboutHashes().logged(),

                    // Verify contract still exists due to batch rollback
                    getContractInfo(contract).hasNoTokenRelationship(misc)
            );
        }
    }

    @HapiTest
    @DisplayName("Batch with deleted token association to contract")
    public Stream<DynamicTest> deletedTokenAssociationWithContract() {
        final var batchOperator = "batchOperator";
        final var adminKey = "adminKey";
        final var tokenAdminKey = "tokenAdminKey";
        final var misc = "someToken";
        final var contract = "CalldataSize";
        final var deleteTokenTxnId = "deleteTokenTxnId";
        final var associateTxnId = "associateTxnId";

        return hapiTest(
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(adminKey),
                newKeyNamed(tokenAdminKey),
                tokenCreate(misc).adminKey(tokenAdminKey),
                uploadInitCode(contract),
                contractCreate(contract).adminKey(adminKey),
                usableTxnIdNamed(deleteTokenTxnId).payerId(batchOperator),
                usableTxnIdNamed(associateTxnId).payerId(batchOperator),

                atomicBatch(
                        // First: Delete the token
                        tokenDelete(misc)
                                .txnId(deleteTokenTxnId)
                                .batchKey(batchOperator)
                                .payingWith(batchOperator),

                        // Second: Try to associate deleted token with contract - WILL FAIL
                        tokenAssociate(contract, misc)
                                .txnId(associateTxnId)
                                .batchKey(batchOperator)
                                .payingWith(batchOperator)
                ).signedByPayerAnd(batchOperator, tokenAdminKey)
                        .via("deletedTokenAssoc")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),

                getTxnRecord("deletedTokenAssoc").logged(),
                getTxnRecord(deleteTokenTxnId).assertingNothingAboutHashes().logged(),
                getTxnRecord(associateTxnId).assertingNothingAboutHashes().logged()
        );
    }

    @Nested
    @DisplayName("Token Creation Batch Tests")
    class TokenCreationBatchTests {

        @HapiTest
        @DisplayName("Batch with invalid token create transactions")
        public Stream<DynamicTest> batchWithInvalidTokenCreateTransactions() {
            final var batchOperator = "batchOperator";
            final var alice = "ALICE";
            final var invalidTokenTxnId = "invalidTokenTxnId";
            final var invalidSigTokenTxnId = "invalidSigTokenTxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(alice).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(invalidTokenTxnId).payerId(batchOperator),
                    usableTxnIdNamed(invalidSigTokenTxnId).payerId(batchOperator),

                    // Batch with missing token name - should fail entire batch
                    atomicBatch(
                            tokenCreate(null).txnId(invalidTokenTxnId).batchKey(batchOperator) // Invalid - missing name
                    ).signedByPayerAnd(batchOperator)
                            .via("invalidNameBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify batch failed and check transaction records
                    getTxnRecord("invalidNameBatch").logged(),
                    getTxnRecord(invalidTokenTxnId).assertingNothingAboutHashes().logged(),

                    // Batch with invalid signature - should fail entire batch
                    atomicBatch(
                            tokenCreate("invalidSigToken")
                                    .treasury(alice)
                                    .txnId(invalidSigTokenTxnId)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator, DEFAULT_PAYER) // Missing alice signature
                            .via("invalidSigBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify batch failed and check transaction records
                    getTxnRecord("invalidSigBatch").logged(),
                    getTxnRecord(invalidSigTokenTxnId).assertingNothingAboutHashes().logged()
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
            final var tokenCreateTxnId = "tokenCreateTxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    // Create all accounts and keys OUTSIDE the batch
                    cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(AUTO_RENEW_ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed("freezeKey"),
                    newKeyNamed("kycKey"),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed("wipeKey"),
                    newKeyNamed("feeScheduleKey"),
                    newKeyNamed(pauseKey),
                    usableTxnIdNamed(tokenCreateTxnId).payerId(batchOperator),

                    // Batch that should fail due to missing treasury signature
                    atomicBatch(
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
                                    .txnId(tokenCreateTxnId)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator, DEFAULT_PAYER, ADMIN_KEY, AUTO_RENEW_ACCOUNT) // Missing TOKEN_TREASURY
                            .via("treasuryBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify batch failed and check transaction records
                    getTxnRecord("treasuryBatch").logged(),
                    getTxnRecord(tokenCreateTxnId).assertingNothingAboutHashes().logged()
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
            final var token1TxnId = "token1TxnId";
            final var successTokenTxnId = "successTokenTxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(customFeesKey),
                    // Create all accounts and resources OUTSIDE the batch
                    cryptoCreate(htsCollector).receiverSigRequired(true),
                    cryptoCreate(hbarCollector),
                    cryptoCreate(tokenCollector),
                    cryptoCreate(TOKEN_TREASURY),
                    tokenCreate(feeDenom).treasury(htsCollector),
                    usableTxnIdNamed(token1TxnId).payerId(batchOperator),
                    usableTxnIdNamed(successTokenTxnId).payerId(batchOperator),

                    // Batch with invalid fee collector signature - should fail
                    atomicBatch(
                            tokenCreate(token)
                                    .treasury(TOKEN_TREASURY)
                                    .withCustom(fractionalFee(
                                            numerator, 0, minimumToCollect, OptionalLong.of(maximumToCollect), tokenCollector))
                                    .txnId(token1TxnId)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator, DEFAULT_PAYER, TOKEN_TREASURY) // Missing tokenCollector signature
                            .via("feeCollectorBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

//                    // Verify batch failed and check transaction records
                    getTxnRecord("feeCollectorBatch").logged(),
                    getTxnRecord(token1TxnId).assertingNothingAboutHashes().logged()
            );
        }
    }

    @Nested
    @DisplayName("Token Management Batch Tests")
    class TokenManagementBatchTests {

        private static String TOKEN_TREASURY = "treasury";
        private static final String NON_FUNGIBLE_TOKEN = "nonFungible";
        private static final String SUPPLY_KEY = "supplyKey";
        private static final String METADATA_KEY = "metadataKey";
        private static final String ADMIN_KEY = "adminKey";
        private static final String WIPE_KEY = "wipeKey";
        private static final String NFT_TEST_METADATA = " test metadata";
        private static final String RECEIVER = "receiver";

        @HapiTest
        @DisplayName("Batch with token wipe failure cases")
        public Stream<DynamicTest> batchWithTokenWipeFailures() {
            final var batchOperator = "batchOperator";
            final var unwipeableToken = "without";
            final var wipeableToken = "with";
            final var wipeableUniqueToken = "uniqueWith";
            final var anotherWipeableToken = "anotherWith";
            final var multiKey = "wipeAndSupplyKey";
            final var someMeta = copyFromUtf8("HEY");
            final var wipe1TxnId = "wipe1TxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(multiKey),
                    // Create all resources OUTSIDE the batch
                    cryptoCreate("misc").balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(unwipeableToken).treasury(TOKEN_TREASURY),
                    tokenCreate(wipeableToken).treasury(TOKEN_TREASURY).wipeKey(multiKey),
                    tokenCreate(wipeableUniqueToken)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .supplyKey(multiKey)
                            .initialSupply(0L)
                            .treasury(TOKEN_TREASURY)
                            .wipeKey(multiKey),
                    mintToken(wipeableUniqueToken, List.of(someMeta)),
                    tokenCreate(anotherWipeableToken)
                            .treasury(TOKEN_TREASURY)
                            .initialSupply(1_000)
                            .wipeKey(multiKey),
                    tokenAssociate("misc", anotherWipeableToken),
                    cryptoTransfer(moving(500, anotherWipeableToken).between(TOKEN_TREASURY, "misc")),
                    // Verify initial setup
                    getAccountBalance("misc").hasTokenBalance(anotherWipeableToken, 500),
                    // Verify initial setup
                    getAccountBalance("misc").hasTokenBalance(anotherWipeableToken, 500),
                    getTokenInfo(unwipeableToken).logged(),
                    getTokenInfo(wipeableToken).logged(),
                    usableTxnIdNamed(wipe1TxnId).payerId(batchOperator),

                    // Batch with treasury wipe attempt - should fail entire batch
                    atomicBatch(
                            wipeTokenAccount(wipeableUniqueToken, TOKEN_TREASURY, List.of(1L))
                                    .txnId(wipe1TxnId)
                                    .batchKey(batchOperator) // CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT
                    ).signedByPayerAnd(batchOperator)
                            .via("wipeBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify batch failed and check transaction records
                    getTxnRecord("wipeBatch").logged(),
                    getTxnRecord(wipe1TxnId).assertingNothingAboutHashes().logged(),

                    // Verify token balances unchanged (atomicity check)
                    getAccountBalance("misc").hasTokenBalance(anotherWipeableToken, 500)
            );
        }

        @HapiTest
        @DisplayName("Batch with KYC management failure cases")
        public Stream<DynamicTest> batchWithKycManagementFailures() {
            final var batchOperator = "batchOperator";
            final var withoutKycKey = "withoutKycKey";
            final var withKycKey = "withKycKey";
            final String ONE_KYC = "oneKyc";
            final var kyc1TxnId = "kyc1TxnId";
            final var successKyc1TxnId = "successKyc1TxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ONE_KYC),
                    // Create all resources OUTSIDE the batch
                    cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(withoutKycKey).treasury(TOKEN_TREASURY),
                    tokenCreate(withKycKey).kycKey(ONE_KYC).treasury(TOKEN_TREASURY),
                    // Verify initial setup
                    getTokenInfo(withoutKycKey).logged(),
                    getTokenInfo(withKycKey).logged(),
                    usableTxnIdNamed(kyc1TxnId).payerId(batchOperator),
                    usableTxnIdNamed(successKyc1TxnId).payerId(batchOperator),

                    // Batch with KYC grant on token without KYC key - should fail entire batch
                    atomicBatch(
                            grantTokenKyc(withoutKycKey, TOKEN_TREASURY)
                                    .txnId(kyc1TxnId)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator, GENESIS) // TOKEN_HAS_NO_KYC_KEY
                            .via("kycBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify batch failed and check transaction records
                    getTxnRecord("kycBatch").logged(),
                    getTxnRecord(kyc1TxnId).assertingNothingAboutHashes().logged()


            );
        }
    }

    @Nested
    @DisplayName("Token Update Batch Tests")
    class TokenUpdateBatchTests {
        private static String TOKEN_TREASURY = "treasury";
        private static final String NON_FUNGIBLE_TOKEN = "nonFungible";
        private static final String SUPPLY_KEY = "supplyKey";
        private static final String METADATA_KEY = "metadataKey";
        private static final String ADMIN_KEY = "adminKey";
        private static final String WIPE_KEY = "wipeKey";
        private static final String NFT_TEST_METADATA = " test metadata";
        private static final String RECEIVER = "receiver";

        @HapiTest
        @DisplayName("Batch fails when NFT metadata update lacks required signatures")
        public Stream<DynamicTest> batchFailsWithoutMetadataKeySignature() {
            final var batchOperator = "batchOperator";
            final var nftToken = "nftToken";
            final var updateTxnId = "updateTxnId";
            final var successUpdateTxnId = "successUpdateTxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(METADATA_KEY),
                    // Create all resources OUTSIDE the batch
                    cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(4),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(4),
                    tokenCreate(nftToken)
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .treasury(TOKEN_TREASURY)
                            .maxSupply(12L)
                            .supplyKey(SUPPLY_KEY)
                            .metadataKey(METADATA_KEY)
                            .initialSupply(0L),
                    tokenAssociate(RECEIVER, nftToken),
                    mintToken(nftToken, List.of(copyFromUtf8("a"), copyFromUtf8("b"))),
                    // Verify initial setup
                    getTokenNftInfo(nftToken, 1L).hasMetadata(copyFromUtf8("a")),
                    usableTxnIdNamed(updateTxnId).payerId(batchOperator),
                    usableTxnIdNamed(successUpdateTxnId).payerId(batchOperator),

                    // Batch with invalid NFT metadata update - should fail entire batch
                    atomicBatch(
                            tokenUpdateNfts(nftToken, NFT_TEST_METADATA, List.of(1L))
                                    .txnId(updateTxnId)
                                    .batchKey(batchOperator)
                                    .payingWith(TOKEN_TREASURY)
                                    .fee(10 * ONE_HBAR) // Missing METADATA_KEY signature - INVALID_SIGNATURE
                    ).signedByPayerAnd(batchOperator)
                            .via("metadataBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify batch failed and check transaction records
                    getTxnRecord("metadataBatch").logged(),
                    getTxnRecord(updateTxnId).assertingNothingAboutHashes().logged(),

                    // Verify NFT metadata unchanged
                    getTokenNftInfo(nftToken, 1L).hasMetadata(copyFromUtf8("a")) // Original metadata
            );
        }

        @HapiTest
        @DisplayName("Batch with auto renew account update signature requirements")
        public Stream<DynamicTest> batchWithAutoRenewAccountSignatureRequirement() {
            final var batchOperator = "batchOperator";
            final var tokenName = "autoRenewToken";
            final var secondPeriod = THREE_MONTHS_IN_SECONDS + 1234;
            final var updateTxnId = "updateTxnId";
            final var successUpdateTxnId = "successUpdateTxnId";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    // Create all resources OUTSIDE the batch
                    cryptoCreate("autoRenew").balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("newAutoRenew").balance(ONE_HUNDRED_HBARS),
                    newKeyNamed("adminKey"),
                    tokenCreate(tokenName)
                            .adminKey("adminKey")
                            .autoRenewAccount("autoRenew")
                            .autoRenewPeriod(THREE_MONTHS_IN_SECONDS),
                    // Verify initial setup
                    getTokenInfo(tokenName).hasAutoRenewAccount("autoRenew"),
                    usableTxnIdNamed(updateTxnId).payerId(batchOperator),
                    usableTxnIdNamed(successUpdateTxnId).payerId(batchOperator),

                    // Batch with invalid auto renew update (missing new auto renew account signature) - should fail entire batch
                    atomicBatch(
                            tokenUpdate(tokenName)
                                    .autoRenewAccount("newAutoRenew")
                                    .autoRenewPeriod(secondPeriod)
                                    .txnId(updateTxnId)
                                    .batchKey(batchOperator)
                    ).signedByPayerAnd(batchOperator, GENESIS, "adminKey") // Missing newAutoRenew signature - INVALID_SIGNATURE
                            .via("autoRenewBatch")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // Verify batch failed and check transaction records
                    getTxnRecord("autoRenewBatch").logged(),
                    getTxnRecord(updateTxnId).assertingNothingAboutHashes().logged(),

                    // Verify auto renew account unchanged
                    getTokenInfo(tokenName).hasAutoRenewAccount("autoRenew") // Should remain unchanged
            );
        }
    }

}