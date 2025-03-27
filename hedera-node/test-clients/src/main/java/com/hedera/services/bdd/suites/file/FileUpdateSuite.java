// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file;

import static com.hedera.services.bdd.junit.ContextRequirement.PERMISSION_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.UPGRADE_FILE_CONTENT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.BYTES_4K;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUtf8Bytes;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateSpecialFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.utils.contracts.SimpleBytesResult.bigIntResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CONTRACT_STORAGE_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MESSAGE_SIZE_TOO_LARGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.FreezeNotApplicable;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Frozen;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Unfrozen;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.KycNotApplicable;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Revoked;
import static java.lang.Long.parseLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.suites.token.TokenAssociationSpecs;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.DynamicTest;

@SuppressWarnings("java:S1192")
public class FileUpdateSuite {
    private static final Logger log = LogManager.getLogger(FileUpdateSuite.class);
    private static final String CONTRACT = "CreateTrivial";
    private static final String CREATE_TXN = "create";
    public static final String INSERT_ABI = "insert";
    private static final String INDIRECT_GET_ABI = "getIndirect";
    private static final String CHAIN_ID_GET_ABI = "getChainID";
    private static final String INVALID_ENTITY_ID = "1.2.3";
    public static final String CIVILIAN = "civilian";
    public static final String TEST_TOPIC = "testTopic";

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                fileCreate("file").contents("ABC"),
                submitModified(withSuccessivelyVariedBodyIds(), () -> fileUpdate("file")
                        .contents("DEF")));
    }

    @HapiTest
    final Stream<DynamicTest> associateHasExpectedSemantics() {
        return hapiTest(flattened(
                TokenAssociationSpecs.basicKeysAndTokens(),
                cryptoCreate("misc").balance(0L),
                tokenAssociate("misc", TokenAssociationSpecs.FREEZABLE_TOKEN_ON_BY_DEFAULT),
                tokenAssociate("misc", TokenAssociationSpecs.FREEZABLE_TOKEN_ON_BY_DEFAULT)
                        .hasKnownStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT),
                tokenAssociate("misc", INVALID_ENTITY_ID).hasKnownStatus(INVALID_TOKEN_ID),
                tokenAssociate("misc", INVALID_ENTITY_ID, INVALID_ENTITY_ID)
                        .hasPrecheck(TOKEN_ID_REPEATED_IN_TOKEN_LIST),
                tokenDissociate("misc", INVALID_ENTITY_ID, INVALID_ENTITY_ID)
                        .hasPrecheck(TOKEN_ID_REPEATED_IN_TOKEN_LIST),
                tokenAssociate("misc", TokenAssociationSpecs.FREEZABLE_TOKEN_OFF_BY_DEFAULT),
                tokenAssociate("misc", TokenAssociationSpecs.KNOWABLE_TOKEN, TokenAssociationSpecs.VANILLA_TOKEN),
                getAccountInfo("misc")
                        .hasToken(relationshipWith(TokenAssociationSpecs.FREEZABLE_TOKEN_ON_BY_DEFAULT)
                                .kyc(KycNotApplicable)
                                .freeze(Frozen))
                        .hasToken(relationshipWith(TokenAssociationSpecs.FREEZABLE_TOKEN_OFF_BY_DEFAULT)
                                .kyc(KycNotApplicable)
                                .freeze(Unfrozen))
                        .hasToken(relationshipWith(TokenAssociationSpecs.KNOWABLE_TOKEN)
                                .kyc(Revoked)
                                .freeze(FreezeNotApplicable))
                        .hasToken(relationshipWith(TokenAssociationSpecs.VANILLA_TOKEN)
                                .kyc(KycNotApplicable)
                                .freeze(FreezeNotApplicable))
                        .logged()));
    }

    @LeakyHapiTest(overrides = {"tokens.maxCustomFeesAllowed"})
    final Stream<DynamicTest> notTooManyFeeScheduleCanBeCreated() {
        final var denom = "fungible";
        final var token = "token";
        return hapiTest(
                overriding("tokens.maxCustomFeesAllowed", "1"),
                tokenCreate(denom),
                tokenCreate(token)
                        .treasury(DEFAULT_PAYER)
                        .withCustom(fixedHbarFee(1, DEFAULT_PAYER))
                        .withCustom(fixedHtsFee(1, denom, DEFAULT_PAYER))
                        .hasKnownStatus(CUSTOM_FEES_LIST_TOO_LONG));
    }

    @LeakyHapiTest(requirement = UPGRADE_FILE_CONTENT)
    final Stream<DynamicTest> optimisticSpecialFileUpdate() {
        final var appendsPerBurst = 128;
        final var specialFile = asEntityString(159);
        final var contents = randomUtf8Bytes(64 * BYTES_4K);
        final var specialFileContents = ByteString.copyFrom(contents);
        final byte[] expectedHash;
        try {
            expectedHash = MessageDigest.getInstance("SHA-384").digest(contents);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return hapiTest(
                updateSpecialFile(GENESIS, specialFile, specialFileContents, BYTES_4K, appendsPerBurst),
                getFileInfo(specialFile).hasMemo(CommonUtils.hex(expectedHash)));
    }

    @LeakyHapiTest(requirement = PERMISSION_OVERRIDES)
    final Stream<DynamicTest> apiPermissionsChangeDynamically() {
        final var civilian = CIVILIAN;
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1_000_000_000L)),
                cryptoCreate(civilian).balance(ONE_HUNDRED_HBARS),
                getFileContents(API_PERMISSIONS).logged(),
                tokenCreate("poc").payingWith(civilian),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of("tokenCreate", "0-1")),
                getFileContents(API_PERMISSIONS).logged(),
                tokenCreate("poc")
                        .payingWith(civilian)
                        .hasPrecheckFrom(NOT_SUPPORTED, OK)
                        .hasKnownStatus(UNAUTHORIZED),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of("tokenCreate", "0-*")),
                tokenCreate("secondPoc").payingWith(civilian));
    }

    @HapiTest
    final Stream<DynamicTest> updateFeesCompatibleWithCreates() {
        final long origLifetime = 7_200_000L;
        final long extension = 700_000L;
        final byte[] old2k = randomUtf8Bytes(BYTES_4K / 2);
        final byte[] new4k = randomUtf8Bytes(BYTES_4K);
        final byte[] new2k = randomUtf8Bytes(BYTES_4K / 2);

        return hapiTest(
                fileCreate("test").contents(old2k).lifetime(origLifetime).via(CREATE_TXN),
                fileUpdate("test").contents(new4k).extendingExpiryBy(0).via("updateTo4"),
                fileUpdate("test").contents(new2k).extendingExpiryBy(0).via("updateTo2"),
                fileUpdate("test").extendingExpiryBy(extension).via("extend"),
                withOpContext((spec, opLog) -> {
                    final var createOp = getTxnRecord(CREATE_TXN);
                    final var to4kOp = getTxnRecord("updateTo4");
                    final var to2kOp = getTxnRecord("updateTo2");
                    final var extensionOp = getTxnRecord("extend");
                    allRunFor(spec, createOp, to4kOp, to2kOp, extensionOp);
                    final var createFee = createOp.getResponseRecord().getTransactionFee();
                    opLog.info("Creation : {} ", createFee);
                    opLog.info(
                            "New 4k   : {} ({})",
                            to4kOp.getResponseRecord().getTransactionFee(),
                            (to4kOp.getResponseRecord().getTransactionFee() - createFee));
                    opLog.info(
                            "New 2k   : {} ({})",
                            to2kOp.getResponseRecord().getTransactionFee(),
                            +(to2kOp.getResponseRecord().getTransactionFee() - createFee));
                    opLog.info(
                            "Extension: {} ({})",
                            extensionOp.getResponseRecord().getTransactionFee(),
                            (extensionOp.getResponseRecord().getTransactionFee() - createFee));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> vanillaUpdateSucceeds() {
        final byte[] old4K = randomUtf8Bytes(BYTES_4K);
        final byte[] new4k = randomUtf8Bytes(BYTES_4K);
        final String firstMemo = "Originally";
        final String secondMemo = "Subsequently";

        return hapiTest(
                fileCreate("test").entityMemo(firstMemo).contents(old4K),
                fileUpdate("test").entityMemo(ZERO_BYTE_MEMO).contents(new4k).hasPrecheck(INVALID_ZERO_BYTE_IN_STRING),
                fileUpdate("test").entityMemo(secondMemo).contents(new4k),
                getFileContents("test").hasContents(ignore -> new4k),
                getFileInfo("test").hasMemo(secondMemo));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateImmutableFile() {
        final String file1 = "FILE_1";
        final String file2 = "FILE_2";
        return hapiTest(
                fileCreate(file1).contents("Hello World").unmodifiable(),
                fileCreate(file2).contents("Hello World").waclShape(SigControl.emptyList()),
                fileUpdate(file1)
                        .contents("Goodbye World")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(UNAUTHORIZED),
                fileUpdate(file2)
                        .contents("Goodbye World")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(UNAUTHORIZED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateExpirationPastMaxLifetime() {
        return hapiTest(
                fileCreate("test"), doWithStartupConfig("entities.maxLifetime", maxLifetime -> fileUpdate("test")
                        .lifetime(parseLong(maxLifetime) + 12_345L)
                        .hasPrecheck(AUTORENEW_DURATION_NOT_IN_RANGE)));
    }

    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    final Stream<DynamicTest> maxRefundIsEnforced() {
        return hapiTest(
                overriding("contracts.maxRefundPercentOfGasLimit", "5"),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, CREATE_TXN).gas(1000000L),
                contractCallLocal(CONTRACT, INDIRECT_GET_ABI)
                        .gas(300000L)
                        .has(resultWith().gasUsed(285000L)));
    }

    // C.f. https://github.com/hashgraph/hedera-services/pull/8908
    @LeakyHapiTest(overrides = {"contracts.maxRefundPercentOfGasLimit"})
    final Stream<DynamicTest> allUnusedGasIsRefundedIfSoConfigured() {
        return hapiTest(
                overriding("contracts.maxRefundPercentOfGasLimit", "100"),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).gas(100_000L),
                contractCall(CONTRACT, CREATE_TXN).gas(1_000_000L),
                contractCallLocal(CONTRACT, INDIRECT_GET_ABI)
                        .gas(300_000L)
                        .has(resultWith().gasUsed(26_515)));
    }

    @LeakyHapiTest(overrides = {"contracts.maxGasPerSec"})
    final Stream<DynamicTest> gasLimitOverMaxGasLimitFailsPrecheck() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).gas(1_000_000L),
                overriding("contracts.maxGasPerSec", "100"),
                contractCallLocal(CONTRACT, INDIRECT_GET_ABI)
                        .gas(101L)
                        // for some reason BUSY is returned in CI
                        .hasCostAnswerPrecheckFrom(MAX_GAS_LIMIT_EXCEEDED, BUSY));
    }

    @LeakyHapiTest(overrides = {"contracts.maxKvPairs.individual", "contracts.maxKvPairs.aggregate"})
    final Stream<DynamicTest> kvLimitsEnforced() {
        final var contract = "User";
        final var gasToOffer = 1_000_000;
        return hapiTest(
                uploadInitCode(contract),
                /* This contract has 0 key/value mappings at creation */
                contractCreate(contract),
                /* Now we update the per-contract limit to 10 mappings */
                overriding("contracts.maxKvPairs.individual", "10"),
                /* The first call to insert adds 5 mappings */
                contractCall(contract, INSERT_ABI, BigInteger.ONE, BigInteger.ONE)
                        .payingWith(GENESIS)
                        .gas(gasToOffer),
                /* Each subsequent call to adds 3 mappings; so 8 total after this */
                contractCall(contract, INSERT_ABI, BigInteger.TWO, BigInteger.valueOf(4))
                        .payingWith(GENESIS)
                        .gas(gasToOffer),
                /* And this one fails because 8 + 3 = 11 > 10 */
                contractCall(contract, INSERT_ABI, BigInteger.valueOf(3), BigInteger.valueOf(9))
                        .payingWith(GENESIS)
                        .hasKnownStatus(MAX_CONTRACT_STORAGE_EXCEEDED)
                        .gas(gasToOffer),
                /* Confirm the storage size didn't change */
                getContractInfo(contract).has(contractWith().numKvPairs(8)),
                /* Now we update the per-contract limit to 1B mappings, but the aggregate limit to just 1 */
                overridingTwo(
                        "contracts.maxKvPairs.individual", "1000000000",
                        "contracts.maxKvPairs.aggregate", "1"),
                contractCall(contract, INSERT_ABI, BigInteger.valueOf(3), BigInteger.valueOf(9))
                        .payingWith(GENESIS)
                        .hasKnownStatus(MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED)
                        .gas(gasToOffer),
                getContractInfo(contract).has(contractWith().numKvPairs(8)),
                /* Now raise the limits and confirm we can use more storage */
                overridingTwo(
                        "contracts.maxKvPairs.individual", "1000000000",
                        "contracts.maxKvPairs.aggregate", "10000000000"),
                contractCall(contract, INSERT_ABI, BigInteger.valueOf(3), BigInteger.valueOf(9))
                        .payingWith(GENESIS)
                        .gas(gasToOffer),
                contractCall(contract, INSERT_ABI, BigInteger.valueOf(4), BigInteger.valueOf(16))
                        .payingWith(GENESIS)
                        .gas(gasToOffer),
                getContractInfo(contract).has(contractWith().numKvPairs(14)));
    }

    @SuppressWarnings("java:S5960")
    @LeakyHapiTest(overrides = {"contracts.maxGasPerSec"})
    final Stream<DynamicTest> serviceFeeRefundedIfConsGasExhausted() {
        final var contract = "User";
        final var gasToOffer = 15_000_000;
        final var civilian = "payer";
        final var unrefundedTxn = "unrefundedTxn";
        final var refundedTxn = "refundedTxn";

        return hapiTest(
                overriding("contracts.maxGasPerSec", gasToOffer + ""),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                cryptoCreate(civilian).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                contractCreate(contract),
                ethereumCall(contract, INSERT_ABI, BigInteger.ONE, BigInteger.valueOf(4))
                        .gasLimit(gasToOffer)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(civilian)
                        .via(unrefundedTxn),
                usableTxnIdNamed(refundedTxn).payerId(civilian),
                contractCall(contract, INSERT_ABI, BigInteger.TWO, BigInteger.valueOf(4))
                        .payingWith(GENESIS)
                        .gas(gasToOffer)
                        .hasAnyStatusAtAll()
                        .deferStatusResolution(),
                uncheckedSubmit(ethereumCall(contract, INSERT_ABI, BigInteger.valueOf(3), BigInteger.valueOf(4))
                                .gasLimit(gasToOffer)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(civilian)
                                .txnId(refundedTxn))
                        .payingWith(GENESIS),
                sleepFor(6_000L),
                withOpContext((spec, opLog) -> {
                    final var unrefundedOp = getTxnRecord(unrefundedTxn);
                    final var refundedOp = getTxnRecord(refundedTxn).assertingNothingAboutHashes();
                    allRunFor(spec, refundedOp, unrefundedOp);
                    final var status =
                            refundedOp.getResponseRecord().getReceipt().getStatus();
                    if (status == SUCCESS) {
                        log.info("Latency allowed gas throttle bucket to drain" + " completely");
                    } else {
                        assertEquals(CONSENSUS_GAS_EXHAUSTED, status);
                        final var hash = refundedOp.getResponseRecord().getEthereumHash();
                        assertEquals(32, hash.size(), "Expected a 32-byte hash");
                        final var origFee = unrefundedOp.getResponseRecord().getTransactionFee();
                        final var feeSansRefund = refundedOp.getResponseRecord().getTransactionFee();
                        assertTrue(
                                feeSansRefund < origFee,
                                "Expected service fee to be refunded, but sans fee "
                                        + feeSansRefund
                                        + " was not less than "
                                        + origFee);
                    }
                }));
    }

    @LeakyHapiTest(overrides = {"contracts.chainId"})
    final Stream<DynamicTest> chainIdChangesDynamically() {
        final var chainIdUser = "ChainIdUser";
        final var otherChainId = 0xABCDL;
        final var firstCallTxn = "firstCallTxn";
        final var secondCallTxn = "secondCallTxn";
        return hapiTest(
                uploadInitCode(chainIdUser),
                contractCreate(chainIdUser),
                contractCall(chainIdUser, CHAIN_ID_GET_ABI).via(firstCallTxn),
                doSeveralWithStartupConfig("contracts.chainId", chainId -> {
                    final var expectedChainId = bigIntResult(parseLong(chainId));
                    return specOps(
                            contractCallLocal(chainIdUser, CHAIN_ID_GET_ABI)
                                    .has(resultWith().contractCallResult(expectedChainId)),
                            getTxnRecord(firstCallTxn)
                                    .hasPriority(recordWith()
                                            .contractCallResult(resultWith().contractCallResult(expectedChainId))),
                            contractCallLocal(chainIdUser, "getSavedChainID")
                                    .has(resultWith().contractCallResult(expectedChainId)));
                }),
                overriding("contracts.chainId", "" + otherChainId),
                contractCreate(chainIdUser),
                contractCall(chainIdUser, CHAIN_ID_GET_ABI).via(secondCallTxn),
                contractCallLocal(chainIdUser, CHAIN_ID_GET_ABI)
                        .has(resultWith().contractCallResult(bigIntResult(otherChainId))),
                getTxnRecord(secondCallTxn)
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith().contractCallResult(bigIntResult(otherChainId)))),
                contractCallLocal(chainIdUser, "getSavedChainID")
                        .has(resultWith().contractCallResult(bigIntResult(otherChainId))));
    }

    @LeakyHapiTest(
            overrides = {
                "accounts.maxNumber",
                "contracts.maxNumber",
                "files.maxNumber",
                "scheduling.maxNumber",
                "tokens.maxNumber",
                "topics.maxNumber"
            })
    final Stream<DynamicTest> entitiesNotCreatableAfterUsageLimitsReached() {
        final var notToBe = "ne'erToBe";
        return hapiTest(
                uploadInitCode("Multipurpose"),
                overridingAllOf(Map.of(
                        "accounts.maxNumber", "0",
                        "contracts.maxNumber", "0",
                        "files.maxNumber", "0",
                        "scheduling.maxNumber", "0",
                        "tokens.maxNumber", "0",
                        "topics.maxNumber", "0")),
                cryptoCreate(notToBe).hasKnownStatus(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED),
                contractCreate("Multipurpose").hasKnownStatus(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED),
                fileCreate(notToBe).contents("NOPE").hasKnownStatus(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED),
                scheduleCreate(notToBe, cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1)))
                        .hasKnownStatus(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED),
                tokenCreate(notToBe).hasKnownStatus(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED),
                createTopic(notToBe).hasKnownStatus(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED));
    }

    @LeakyHapiTest(overrides = {"consensus.message.maxBytesAllowed"})
    final Stream<DynamicTest> messageSubmissionSizeChange() {
        final var defaultMaxBytesAllowed = 1024;
        final var longMessage = TxnUtils.randomUtf8Bytes(defaultMaxBytesAllowed);

        return hapiTest(
                newKeyNamed("submitKey"),
                createTopic(TEST_TOPIC).submitKeyName("submitKey"),
                cryptoCreate(CIVILIAN),
                submitMessageTo(TEST_TOPIC)
                        .message("testmessage")
                        .payingWith(CIVILIAN)
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(SUCCESS),
                overriding("consensus.message.maxBytesAllowed", String.valueOf(defaultMaxBytesAllowed - 1)),
                submitMessageTo(TEST_TOPIC)
                        .message(longMessage)
                        .payingWith(CIVILIAN)
                        .hasRetryPrecheckFrom(BUSY)
                        .hasKnownStatus(MESSAGE_SIZE_TOO_LARGE));
    }
}
