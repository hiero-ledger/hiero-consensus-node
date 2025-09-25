// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.SECP256K1;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_SYMBOL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenFreezeStatus;
import com.hederahashgraph.api.proto.java.TokenPauseStatus;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// Some of the test cases cannot be converted to use eth calls,
// since they use admin keys, which are held by the txn payer.
// In the case of an eth txn, we revoke the payers keys and the txn would fail.
// The only way an eth account to create a token is the admin key to be of a contractId type.
@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class CreatePrecompileSuite {
    public static final String CONTRACT_ADMIN_KEY = "contractAdminKey";
    public static final String ACCOUNT_TO_ASSOCIATE = "account3";
    public static final String ACCOUNT_TO_ASSOCIATE_KEY = "associateKey";

    public static final String FALSE = "false";
    public static final String CREATE_TOKEN_WITH_ALL_CUSTOM_FEES_AVAILABLE = "createTokenWithAllCustomFeesAvailable";
    private static final Logger log = LogManager.getLogger(CreatePrecompileSuite.class);
    private static final long CONTRACT_CREATE_GAS_TO_OFFER = 4_000_000L;
    private static final long GAS_TO_OFFER = 1_000_000L;
    public static final long AUTO_RENEW_PERIOD = 8_000_000L;
    public static final String TOKEN_SYMBOL = "tokenSymbol";
    public static final String TOKEN_NAME = "tokenName";
    public static final String MEMO = "memo";
    public static final String TOKEN_CREATE_CONTRACT_AS_KEY = "tokenCreateContractAsKey";
    private static final String ACCOUNT = "account";
    public static final String TOKEN_CREATE_CONTRACT = "TokenCreateContract";
    public static final String FIRST_CREATE_TXN = "firstCreateTxn";
    private static final String ACCOUNT_BALANCE = "ACCOUNT_BALANCE";
    public static final long DEFAULT_AMOUNT_TO_SEND = 20 * ONE_HBAR;
    public static final String ED25519KEY = "ed25519key";
    public static final String ECDSA_KEY = "ecdsa";
    public static final String EXISTING_TOKEN = "EXISTING_TOKEN";
    public static final String EXPLICIT_CREATE_RESULT = "Explicit create result is {}";
    private static final String CREATE_NFT_WITH_KEYS_AND_EXPIRY_FUNCTION = "createNFTTokenWithKeysAndExpiry";
    private static final KeyShape THRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);
    private static final String THRESHOLD_KEY = "ThreshKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final String TOKEN_MISC_OPERATIONS_CONTRACT = "TokenMiscOperations";
    private static final String CREATE_FUNGIBLE_TOKEN_WITH_KEYS_AND_EXPIRY_FUNCTION = "createTokenWithKeysAndExpiry";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("contracts.throttle.throttleByGas", "false"));
    }

    // TEST-001
    @HapiTest
    final Stream<DynamicTest> fungibleTokenCreateHappyPath() {
        final var tokenCreateContractAsKeyDelegate = "tokenCreateContractAsKeyDelegate";
        final var createTokenNum = new AtomicLong();
        final AtomicReference<byte[]> ed2551Key = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP256K1),
                newKeyNamed(CONTRACT_ADMIN_KEY),
                cryptoCreate(ACCOUNT_TO_ASSOCIATE),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                contractCreate(TOKEN_CREATE_CONTRACT)
                        .autoRenewAccountId(ACCOUNT)
                        .adminKey(CONTRACT_ADMIN_KEY)
                        .gas(CONTRACT_CREATE_GAS_TO_OFFER),
                newKeyNamed(THRESHOLD_KEY)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ED25519_ON, TOKEN_CREATE_CONTRACT)))
                        .exposingKeyTo(k -> ed2551Key.set(k.getThresholdKey()
                                .getKeys()
                                .getKeys(0)
                                .getEd25519()
                                .toByteArray())),
                cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                cryptoUpdate(ACCOUNT_TO_ASSOCIATE).key(THRESHOLD_KEY),
                withOpContext((spec, opLog) -> {
                    spec.registry()
                            .saveKey(
                                    ED25519KEY,
                                    spec.registry()
                                            .getKey(THRESHOLD_KEY)
                                            .getThresholdKey()
                                            .getKeys()
                                            .getKeys(0));
                    allRunFor(
                            spec,
                            contractCall(
                                            TOKEN_CREATE_CONTRACT,
                                            CREATE_FUNGIBLE_TOKEN_WITH_KEYS_AND_EXPIRY_FUNCTION,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getAccountID(ACCOUNT))),
                                            ed2551Key.get(),
                                            spec.registry()
                                                    .getKey(ECDSA_KEY)
                                                    .getECDSASecp256K1()
                                                    .toByteArray(),
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getContractId(TOKEN_CREATE_CONTRACT))),
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getContractId(TOKEN_CREATE_CONTRACT))),
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getAccountID(ACCOUNT))),
                                            AUTO_RENEW_PERIOD,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getAccountID(ACCOUNT_TO_ASSOCIATE))))
                                    .via(FIRST_CREATE_TXN)
                                    .gas(GAS_TO_OFFER)
                                    .sending(DEFAULT_AMOUNT_TO_SEND)
                                    .payingWith(ACCOUNT)
                                    .signedBy(THRESHOLD_KEY)
                                    .refusingEthConversion()
                                    .exposingResultTo(result -> {
                                        log.info(EXPLICIT_CREATE_RESULT, result[0]);
                                        final var res = (Address) result[0];
                                        createTokenNum.set(numberOfLongZero(HexFormat.of()
                                                .parseHex(res.toString().substring(2))));
                                    })
                                    .hasKnownStatus(SUCCESS),
                            newKeyNamed(TOKEN_CREATE_CONTRACT_AS_KEY).shape(CONTRACT.signedWith(TOKEN_CREATE_CONTRACT)),
                            newKeyNamed(tokenCreateContractAsKeyDelegate)
                                    .shape(DELEGATE_CONTRACT.signedWith(TOKEN_CREATE_CONTRACT)));
                }),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getContractInfo(TOKEN_CREATE_CONTRACT)
                                .has(ContractInfoAsserts.contractWith().autoRenewAccountId(ACCOUNT))
                                .logged(),
                        getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                        getAccountBalance(ACCOUNT).logged(),
                        getAccountBalance(TOKEN_CREATE_CONTRACT).logged(),
                        getContractInfo(TOKEN_CREATE_CONTRACT).logged(),
                        childRecordsCheck(
                                FIRST_CREATE_TXN,
                                ResponseCodeEnum.SUCCESS,
                                TransactionRecordAsserts.recordWith().status(ResponseCodeEnum.SUCCESS),
                                TransactionRecordAsserts.recordWith().status(ResponseCodeEnum.SUCCESS),
                                TransactionRecordAsserts.recordWith().status(ResponseCodeEnum.SUCCESS)),
                        sourcing(() ->
                                getAccountInfo(ACCOUNT_TO_ASSOCIATE).logged().hasTokenRelationShipCount(1)),
                        sourcing(() -> getTokenInfo(String.valueOf(createTokenNum.get()))
                                .logged()
                                .hasTokenType(TokenType.FUNGIBLE_COMMON)
                                .hasSymbol(TOKEN_SYMBOL)
                                .hasName(TOKEN_NAME)
                                .hasDecimals(8)
                                .hasTotalSupply(100)
                                .hasEntityMemo(MEMO)
                                .hasTreasury(ACCOUNT)
                                // Token doesn't inherit contract's auto-renew
                                // account if set in tokenCreate
                                .hasAutoRenewAccount(ACCOUNT)
                                .hasAutoRenewPeriod(AUTO_RENEW_PERIOD)
                                .hasSupplyType(TokenSupplyType.INFINITE)
                                .searchKeysGlobally()
                                .hasAdminKey(ED25519KEY)
                                .hasKycKey(ED25519KEY)
                                .hasFreezeKey(ECDSA_KEY)
                                .hasWipeKey(ECDSA_KEY)
                                .hasSupplyKey(TOKEN_CREATE_CONTRACT_AS_KEY)
                                .hasFeeScheduleKey(tokenCreateContractAsKeyDelegate)
                                .hasPauseKey(CONTRACT_ADMIN_KEY)
                                .hasPauseStatus(TokenPauseStatus.Unpaused)),
                        cryptoDelete(ACCOUNT).hasKnownStatus(ACCOUNT_IS_TREASURY))));
    }

    // TEST-002
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> inheritsSenderAutoRenewAccountIfAnyForNftCreate() {
        final var createdNftTokenNum = new AtomicLong();
        final AtomicReference<byte[]> ed2551Key = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(ED25519KEY).shape(ED25519),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT)
                        .autoRenewAccountId(ACCOUNT)
                        .gas(CONTRACT_CREATE_GAS_TO_OFFER),
                newKeyNamed(THRESHOLD_KEY)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ED25519_ON, TOKEN_CREATE_CONTRACT)))
                        .exposingKeyTo(k -> ed2551Key.set(k.getThresholdKey()
                                .getKeys()
                                .getKeys(0)
                                .getEd25519()
                                .toByteArray())),
                cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getContractInfo(TOKEN_CREATE_CONTRACT)
                                .has(ContractInfoAsserts.contractWith().autoRenewAccountId(ACCOUNT))
                                .logged())),
                withOpContext((spec, ignore) -> {
                    final var subop1 = balanceSnapshot(ACCOUNT_BALANCE, ACCOUNT);
                    final var subop2 = contractCall(
                                    TOKEN_CREATE_CONTRACT,
                                    CREATE_NFT_WITH_KEYS_AND_EXPIRY_FUNCTION,
                                    HapiParserUtil.asHeadlongAddress(
                                            asAddress(spec.registry().getAccountID(ACCOUNT))),
                                    ed2551Key.get(),
                                    HapiParserUtil.asHeadlongAddress(
                                            asAddress(spec.registry().getAccountID(ACCOUNT))),
                                    AUTO_RENEW_PERIOD)
                            .via(FIRST_CREATE_TXN)
                            .gas(GAS_TO_OFFER)
                            .payingWith(ACCOUNT)
                            .sending(DEFAULT_AMOUNT_TO_SEND)
                            .refusingEthConversion()
                            .exposingResultTo(result -> {
                                log.info("Explicit create result is" + " {}", result[0]);
                                final var res = (Address) result[0];
                                createdNftTokenNum.set(numberOfLongZero(
                                        // Remove the leading '0x'
                                        HexFormat.of().parseHex(res.toString().substring(2))));
                            })
                            .hasKnownStatus(SUCCESS);

                    allRunFor(
                            spec,
                            subop1,
                            subop2,
                            childRecordsCheck(
                                    FIRST_CREATE_TXN,
                                    SUCCESS,
                                    TransactionRecordAsserts.recordWith().status(SUCCESS)));

                    final var nftInfo = getTokenInfo(String.valueOf(createdNftTokenNum.get()))
                            .hasAutoRenewAccount(ACCOUNT)
                            .logged();

                    allRunFor(spec, nftInfo);
                }));
    }

    // TEST-001
    @HapiTest
    final Stream<DynamicTest> inheritsSenderAutoRenewAccountForTokenCreate() {
        final var createTokenNum = new AtomicLong();
        final AtomicReference<byte[]> ed2551Key = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP256K1),
                newKeyNamed(ACCOUNT_TO_ASSOCIATE_KEY),
                newKeyNamed(CONTRACT_ADMIN_KEY),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                cryptoCreate(ACCOUNT_TO_ASSOCIATE).key(ACCOUNT_TO_ASSOCIATE_KEY),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT)
                        .gas(CONTRACT_CREATE_GAS_TO_OFFER)
                        .adminKey(CONTRACT_ADMIN_KEY)
                        .autoRenewAccountId(ACCOUNT),
                newKeyNamed(THRESHOLD_KEY)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ED25519_ON, TOKEN_CREATE_CONTRACT)))
                        .exposingKeyTo(k -> ed2551Key.set(k.getThresholdKey()
                                .getKeys()
                                .getKeys(0)
                                .getEd25519()
                                .toByteArray())),
                cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                cryptoUpdate(ACCOUNT_TO_ASSOCIATE).key(THRESHOLD_KEY),
                getContractInfo(TOKEN_CREATE_CONTRACT)
                        .has(ContractInfoAsserts.contractWith().autoRenewAccountId(ACCOUNT))
                        .logged(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_CREATE_CONTRACT,
                                        CREATE_FUNGIBLE_TOKEN_WITH_KEYS_AND_EXPIRY_FUNCTION,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        ed2551Key.get(),
                                        spec.registry()
                                                .getKey(ECDSA_KEY)
                                                .getECDSASecp256K1()
                                                .toByteArray(),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(TOKEN_CREATE_CONTRACT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(TOKEN_CREATE_CONTRACT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        AUTO_RENEW_PERIOD,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT_TO_ASSOCIATE))))
                                .via(FIRST_CREATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .sending(DEFAULT_AMOUNT_TO_SEND)
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .refusingEthConversion()
                                .exposingResultTo(result -> {
                                    log.info(EXPLICIT_CREATE_RESULT, result[0]);
                                    final var res = (Address) result[0];
                                    createTokenNum.set(numberOfLongZero(HexFormat.of()
                                            .parseHex(res.toString().substring(2))));
                                })
                                .hasKnownStatus(SUCCESS),
                        sourcing(() -> getTokenInfo(String.valueOf(createTokenNum.get()))
                                .logged()
                                .hasAutoRenewAccount(ACCOUNT)
                                .hasPauseStatus(TokenPauseStatus.Unpaused)))));
    }

    // TEST-003
    @HapiTest
    final Stream<DynamicTest> nonFungibleTokenCreateHappyPath() {
        final var createdTokenNum = new AtomicLong();
        return hapiTest(
                newKeyNamed(ED25519KEY).shape(ED25519),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS).key(ED25519KEY),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                getAccountInfo(DEFAULT_CONTRACT_SENDER).savingSnapshot(DEFAULT_CONTRACT_SENDER),
                withOpContext((spec, opLog) ->
                        allRunFor(spec, contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER))),
                withOpContext((spec, ignore) -> {
                    final var subop1 = balanceSnapshot(ACCOUNT_BALANCE, ACCOUNT);
                    final var preCallSnap = balanceSnapshot("preCallSnap", TOKEN_CREATE_CONTRACT);
                    final var subop2 = contractCall(
                                    TOKEN_CREATE_CONTRACT,
                                    CREATE_NFT_WITH_KEYS_AND_EXPIRY_FUNCTION,
                                    HapiParserUtil.asHeadlongAddress(
                                            asAddress(spec.registry().getAccountID(ACCOUNT))),
                                    spec.registry()
                                            .getKey(ED25519KEY)
                                            .getEd25519()
                                            .toByteArray(),
                                    HapiParserUtil.asHeadlongAddress(
                                            asAddress(spec.registry().getAccountID(ACCOUNT))),
                                    AUTO_RENEW_PERIOD)
                            .via(FIRST_CREATE_TXN)
                            .gas(3_000_000)
                            .payingWith(ACCOUNT)
                            .sending(DEFAULT_AMOUNT_TO_SEND)
                            .exposingResultTo(result -> {
                                log.info("Explicit create result is" + " {}", result[0]);
                                final var res = (Address) result[0];
                                createdTokenNum.set(numberOfLongZero(
                                        HexFormat.of().parseHex(res.toString().substring(2))));
                            })
                            .refusingEthConversion()
                            .hasKnownStatus(SUCCESS);
                    final var subop3 = getTxnRecord(FIRST_CREATE_TXN);
                    allRunFor(
                            spec,
                            subop1,
                            preCallSnap,
                            subop2,
                            subop3,
                            childRecordsCheck(
                                    FIRST_CREATE_TXN,
                                    SUCCESS,
                                    TransactionRecordAsserts.recordWith().status(SUCCESS)));

                    final var callRecord = subop3.getResponseRecord();
                    final long totalFee = callRecord.getTransactionFee();
                    final long gasCost = callRecord.getContractCallResult().getGasUsed()
                            * spec.ratesProvider().currentTinybarGasPrice();
                    final long creationFee = totalFee - gasCost;
                    final var subop4 = getAccountBalance(ACCOUNT)
                            .hasTinyBars(changeFromSnapshot(ACCOUNT_BALANCE, -(gasCost + DEFAULT_AMOUNT_TO_SEND)));
                    final var contractBalanceCheck = getAccountBalance(TOKEN_CREATE_CONTRACT)
                            .hasTinyBars(changeFromSnapshot("preCallSnap", (DEFAULT_AMOUNT_TO_SEND - creationFee)));
                    final var getAccountTokenBalance =
                            getAccountBalance(ACCOUNT).hasTokenBalance(String.valueOf(createdTokenNum.get()), 0);
                    final var tokenInfo = getTokenInfo(String.valueOf(createdTokenNum.get()))
                            .hasTokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                            .hasSymbol(TOKEN_SYMBOL)
                            .hasName(TOKEN_NAME)
                            .hasDecimals(0)
                            .hasTotalSupply(0)
                            .hasEntityMemo(MEMO)
                            .hasTreasury(ACCOUNT)
                            .hasAutoRenewAccount(ACCOUNT)
                            .hasAutoRenewPeriod(AUTO_RENEW_PERIOD)
                            .hasSupplyType(TokenSupplyType.FINITE)
                            .hasFreezeDefault(TokenFreezeStatus.Frozen)
                            .hasMaxSupply(10)
                            .searchKeysGlobally()
                            .hasAdminKey(ED25519KEY)
                            .hasSupplyKey(ED25519KEY)
                            .hasPauseKey(ED25519KEY)
                            .hasFreezeKey(ED25519KEY)
                            .hasKycKey(ED25519KEY)
                            .hasFeeScheduleKey(ED25519KEY)
                            .hasWipeKey(ED25519KEY)
                            .hasPauseStatus(TokenPauseStatus.Unpaused)
                            .logged();
                    allRunFor(spec, subop4, getAccountTokenBalance, tokenInfo, contractBalanceCheck);
                }));
    }

    // TEST-005
    @HapiTest
    final Stream<DynamicTest> fungibleTokenCreateThenQueryAndTransfer() {
        final var createdTokenNum = new AtomicLong();
        final AtomicReference<byte[]> ed2551Key = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER),
                newKeyNamed(TOKEN_CREATE_CONTRACT_AS_KEY).shape(CONTRACT.signedWith(TOKEN_CREATE_CONTRACT)),
                newKeyNamed(THRESHOLD_KEY)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ED25519_ON, TOKEN_CREATE_CONTRACT)))
                        .exposingKeyTo(k -> ed2551Key.set(k.getThresholdKey()
                                .getKeys()
                                .getKeys(0)
                                .getEd25519()
                                .toByteArray())),
                cryptoCreate(ACCOUNT)
                        .balance(ONE_MILLION_HBARS)
                        .key(THRESHOLD_KEY)
                        .maxAutomaticTokenAssociations(1),
                withOpContext((spec, opLog) -> {
                    spec.registry()
                            .saveKey(
                                    ADMIN_KEY,
                                    spec.registry()
                                            .getKey(THRESHOLD_KEY)
                                            .getThresholdKey()
                                            .getKeys()
                                            .getKeys(0));
                    allRunFor(
                            spec,
                            contractCall(
                                            TOKEN_CREATE_CONTRACT,
                                            "createTokenThenQueryAndTransfer",
                                            ed2551Key.get(),
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getAccountID(ACCOUNT))),
                                            AUTO_RENEW_PERIOD)
                                    .via(FIRST_CREATE_TXN)
                                    .gas(GAS_TO_OFFER)
                                    .sending(DEFAULT_AMOUNT_TO_SEND)
                                    .payingWith(ACCOUNT)
                                    .signedBy(ACCOUNT)
                                    .exposingResultTo(result -> {
                                        log.info(EXPLICIT_CREATE_RESULT, result[0]);
                                        final var res = (Address) result[0];
                                        createdTokenNum.set(numberOfLongZero(HexFormat.of()
                                                .parseHex(res.toString().substring(2))));
                                    })
                                    .hasKnownStatus(SUCCESS));
                }),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                        getAccountBalance(ACCOUNT).logged(),
                        getAccountBalance(TOKEN_CREATE_CONTRACT).logged(),
                        getContractInfo(TOKEN_CREATE_CONTRACT).logged(),
                        childRecordsCheck(
                                FIRST_CREATE_TXN,
                                ResponseCodeEnum.SUCCESS,
                                TransactionRecordAsserts.recordWith().status(SUCCESS),
                                TransactionRecordAsserts.recordWith().status(SUCCESS),
                                TransactionRecordAsserts.recordWith().status(SUCCESS),
                                TransactionRecordAsserts.recordWith().status(SUCCESS)),
                        sourcing(() ->
                                getAccountBalance(ACCOUNT).hasTokenBalance(String.valueOf(createdTokenNum.get()), 20)),
                        sourcing(() -> getAccountBalance(TOKEN_CREATE_CONTRACT)
                                .hasTokenBalance(String.valueOf(createdTokenNum.get()), 10)),
                        sourcing(() -> getTokenInfo(String.valueOf(createdTokenNum.get()))
                                .hasTokenType(TokenType.FUNGIBLE_COMMON)
                                .hasSymbol(TOKEN_SYMBOL)
                                .hasName(TOKEN_NAME)
                                .hasDecimals(8)
                                .hasTotalSupply(30)
                                .hasEntityMemo(MEMO)
                                .hasTreasury(TOKEN_CREATE_CONTRACT)
                                .hasAutoRenewAccount(ACCOUNT)
                                .hasAutoRenewPeriod(AUTO_RENEW_PERIOD)
                                .hasSupplyType(TokenSupplyType.INFINITE)
                                .searchKeysGlobally()
                                .hasAdminKey("adminKey")
                                .hasSupplyKey(TOKEN_CREATE_CONTRACT_AS_KEY)
                                .hasPauseKey(TOKEN_CREATE_CONTRACT_AS_KEY)
                                .hasPauseStatus(TokenPauseStatus.Unpaused)
                                .logged()))));
    }

    // TEST-006
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> nonFungibleTokenCreateThenQuery() {
        final var createdTokenNum = new AtomicLong();
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT)
                        .autoRenewAccountId(ACCOUNT)
                        .gas(CONTRACT_CREATE_GAS_TO_OFFER),
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, TOKEN_CREATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_CREATE_CONTRACT,
                                        "createNonFungibleTokenThenQuery",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(TOKEN_CREATE_CONTRACT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        AUTO_RENEW_PERIOD)
                                .via(FIRST_CREATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .sending(DEFAULT_AMOUNT_TO_SEND)
                                .payingWith(ACCOUNT)
                                .signedByPayerAnd(THRESHOLD_KEY)
                                .refusingEthConversion()
                                .exposingResultTo(result -> {
                                    log.info(EXPLICIT_CREATE_RESULT, result[0]);
                                    final var res = (Address) result[0];
                                    createdTokenNum.set(numberOfLongZero(HexFormat.of()
                                            .parseHex(res.toString().substring(2))));
                                }),
                        newKeyNamed(TOKEN_CREATE_CONTRACT_AS_KEY).shape(CONTRACT.signedWith(TOKEN_CREATE_CONTRACT)))),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                getAccountBalance(ACCOUNT).logged(),
                getAccountBalance(TOKEN_CREATE_CONTRACT).logged(),
                getContractInfo(TOKEN_CREATE_CONTRACT).logged(),
                childRecordsCheck(
                        FIRST_CREATE_TXN,
                        ResponseCodeEnum.SUCCESS,
                        TransactionRecordAsserts.recordWith().status(SUCCESS),
                        TransactionRecordAsserts.recordWith().status(SUCCESS),
                        TransactionRecordAsserts.recordWith().status(SUCCESS)),
                sourcing(() -> getAccountBalance(TOKEN_CREATE_CONTRACT)
                        .hasTokenBalance(String.valueOf(createdTokenNum.get()), 0)),
                sourcing(() -> getTokenInfo(String.valueOf(createdTokenNum.get()))
                        .hasTokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .hasSymbol(TOKEN_SYMBOL)
                        .hasName(TOKEN_NAME)
                        .hasDecimals(0)
                        .hasTotalSupply(0)
                        .hasEntityMemo(MEMO)
                        .hasTreasury(TOKEN_CREATE_CONTRACT)
                        .hasAutoRenewAccount(ACCOUNT)
                        .hasAutoRenewPeriod(AUTO_RENEW_PERIOD)
                        .hasSupplyType(TokenSupplyType.INFINITE)
                        .searchKeysGlobally()
                        .hasAdminKey(TOKEN_CREATE_CONTRACT_AS_KEY)
                        .hasSupplyKey(TOKEN_CREATE_CONTRACT_AS_KEY)
                        .hasPauseStatus(TokenPauseStatus.PauseNotApplicable)
                        .logged()));
    }

    @HapiTest
    final Stream<DynamicTest> createTokenWithDefaultExpiryAndEmptyKeys() {
        final var tokenCreateContractAsKeyDelegate = "createTokenWithDefaultExpiryAndEmptyKeys";
        final var createdTokenNum = new AtomicLong();
        return hapiTest(
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER),
                newKeyNamed(THRESHOLD_KEY)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ED25519_ON, TOKEN_CREATE_CONTRACT))),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS).key(THRESHOLD_KEY),
                contractCall(TOKEN_CREATE_CONTRACT, tokenCreateContractAsKeyDelegate)
                        .via(FIRST_CREATE_TXN)
                        .gas(GAS_TO_OFFER)
                        .sending(DEFAULT_AMOUNT_TO_SEND)
                        .payingWith(ACCOUNT)
                        .exposingResultTo(result -> {
                            log.info(EXPLICIT_CREATE_RESULT, result[0]);
                            final var res = (Address) result[0];
                            createdTokenNum.set(numberOfLongZero(
                                    HexFormat.of().parseHex(res.toString().substring(2))));
                        })
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                getAccountBalance(ACCOUNT).logged(),
                getAccountBalance(TOKEN_CREATE_CONTRACT).logged(),
                getContractInfo(TOKEN_CREATE_CONTRACT).logged(),
                childRecordsCheck(
                        FIRST_CREATE_TXN,
                        ResponseCodeEnum.SUCCESS,
                        TransactionRecordAsserts.recordWith().status(SUCCESS)),
                sourcing(() -> getAccountBalance(TOKEN_CREATE_CONTRACT)
                        .hasTokenBalance(String.valueOf(createdTokenNum.get()), 200)),
                sourcing(() -> getTokenInfo(String.valueOf(createdTokenNum.get()))
                        .hasTokenType(TokenType.FUNGIBLE_COMMON)
                        .hasDecimals(8)
                        .hasTotalSupply(200)
                        .hasTreasury(TOKEN_CREATE_CONTRACT)
                        .hasAutoRenewPeriod(0L)
                        .searchKeysGlobally()
                        .hasPauseStatus(TokenPauseStatus.PauseNotApplicable)
                        .logged()));
    }

    // TEST-007 & TEST-016
    // Should fail on insufficient value sent
    @HapiTest
    final Stream<DynamicTest> tokenCreateWithEmptyKeysReverts() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                getAccountInfo(DEFAULT_CONTRACT_SENDER).savingSnapshot(DEFAULT_CONTRACT_SENDER),
                withOpContext((spec, opLog) ->
                        allRunFor(spec, contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER))),
                withOpContext((spec, ignore) -> {
                    final var balanceSnapshot = balanceSnapshot(
                            ACCOUNT_BALANCE, spec.isUsingEthCalls() ? DEFAULT_CONTRACT_SENDER : ACCOUNT);
                    final var hapiContractCall = contractCall(
                                    TOKEN_CREATE_CONTRACT,
                                    "createTokenWithEmptyKeysArray",
                                    HapiParserUtil.asHeadlongAddress(
                                            asAddress(spec.registry().getAccountID(ACCOUNT))),
                                    AUTO_RENEW_PERIOD)
                            .via(FIRST_CREATE_TXN)
                            .gas(GAS_TO_OFFER)
                            .sending(DEFAULT_AMOUNT_TO_SEND)
                            .payingWith(ACCOUNT)
                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED);
                    final var txnRecord = getTxnRecord(FIRST_CREATE_TXN);
                    allRunFor(
                            spec,
                            balanceSnapshot,
                            hapiContractCall,
                            txnRecord,
                            getAccountBalance(TOKEN_CREATE_CONTRACT).hasTinyBars(0L),
                            emptyChildRecordsCheck(FIRST_CREATE_TXN, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED));
                    final var delta = spec.isUsingEthCalls()
                            ? GAS_TO_OFFER * HapiEthereumCall.DEFAULT_GAS_PRICE_TINYBARS
                            : txnRecord.getResponseRecord().getTransactionFee();
                    final var effectivePayer = spec.isUsingEthCalls() ? DEFAULT_CONTRACT_SENDER : ACCOUNT;
                    final var changeFromSnapshot = getAccountBalance(effectivePayer)
                            .hasTinyBars(changeFromSnapshot(ACCOUNT_BALANCE, -(delta)));
                    allRunFor(spec, changeFromSnapshot);
                }),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                getAccountBalance(ACCOUNT).logged());
    }

    // TEST-008
    @HapiTest
    final Stream<DynamicTest> tokenCreateWithKeyWithMultipleKeyValuesReverts() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_CREATE_CONTRACT,
                                        "createTokenWithKeyWithMultipleValues",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        AUTO_RENEW_PERIOD)
                                .via(FIRST_CREATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .sending(DEFAULT_AMOUNT_TO_SEND)
                                .payingWith(ACCOUNT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                getAccountBalance(ACCOUNT).logged(),
                getAccountBalance(TOKEN_CREATE_CONTRACT).logged(),
                getContractInfo(TOKEN_CREATE_CONTRACT).logged(),
                emptyChildRecordsCheck(FIRST_CREATE_TXN, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED));
    }

    // TEST-009
    @HapiTest
    final Stream<DynamicTest> tokenCreateWithFixedFeeWithMultiplePaymentsReverts() {
        return hapiTest(
                newKeyNamed(ECDSA_KEY).shape(SECP256K1),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_CREATE_CONTRACT,
                                        "createTokenWithInvalidFixedFee",
                                        spec.registry()
                                                .getKey(ECDSA_KEY)
                                                .getECDSASecp256K1()
                                                .toByteArray(),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        AUTO_RENEW_PERIOD)
                                .via(FIRST_CREATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .sending(DEFAULT_AMOUNT_TO_SEND)
                                .payingWith(ACCOUNT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords(),
                getAccountBalance(ACCOUNT).logged(),
                getAccountBalance(TOKEN_CREATE_CONTRACT).logged(),
                getContractInfo(TOKEN_CREATE_CONTRACT).logged(),
                emptyChildRecordsCheck(FIRST_CREATE_TXN, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED));
    }

    // TEST-010 & TEST-017
    // Should fail on insufficient value sent
    @HapiTest
    final Stream<DynamicTest> createTokenWithEmptyTokenStruct() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                withOpContext((spec, opLog) ->
                        allRunFor(spec, contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER))),
                withOpContext((spec, ignore) -> {
                    final var accountSnapshot = spec.isUsingEthCalls()
                            ? balanceSnapshot(ACCOUNT_BALANCE, DEFAULT_CONTRACT_SENDER)
                            : balanceSnapshot(ACCOUNT_BALANCE, ACCOUNT);
                    final var contractCall = contractCall(TOKEN_CREATE_CONTRACT, "createTokenWithEmptyTokenStruct")
                            .via(FIRST_CREATE_TXN)
                            .gas(GAS_TO_OFFER)
                            .payingWith(ACCOUNT)
                            .sending(DEFAULT_AMOUNT_TO_SEND)
                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED);
                    final var txnRecord = getTxnRecord(FIRST_CREATE_TXN);
                    allRunFor(
                            spec,
                            accountSnapshot,
                            contractCall,
                            txnRecord,
                            childRecordsCheck(
                                    FIRST_CREATE_TXN,
                                    CONTRACT_REVERT_EXECUTED,
                                    TransactionRecordAsserts.recordWith()
                                            .status(MISSING_TOKEN_SYMBOL)
                                            .contractCallResult(ContractFnResultAsserts.resultWith()
                                                    .error(MISSING_TOKEN_SYMBOL.name()))));
                    final var delta = spec.isUsingEthCalls()
                            ? GAS_TO_OFFER * HapiEthereumCall.DEFAULT_GAS_PRICE_TINYBARS
                            : txnRecord.getResponseRecord().getTransactionFee();
                    final var effectivePayer = spec.isUsingEthCalls() ? DEFAULT_CONTRACT_SENDER : ACCOUNT;
                    final var accountBalance = getAccountBalance(effectivePayer)
                            .hasTinyBars(changeFromSnapshot(ACCOUNT_BALANCE, -(delta)));
                    allRunFor(
                            spec,
                            accountBalance,
                            getAccountBalance(TOKEN_CREATE_CONTRACT).hasTinyBars(0L));
                }),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                getAccountBalance(ACCOUNT).logged(),
                getAccountBalance(TOKEN_CREATE_CONTRACT).logged());
    }

    // TEST-011
    @HapiTest
    final Stream<DynamicTest> createTokenWithInvalidExpiry() {
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_CREATE_CONTRACT,
                                        "createTokenWithInvalidExpiry",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        AUTO_RENEW_PERIOD)
                                .via(FIRST_CREATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .sending(DEFAULT_AMOUNT_TO_SEND)
                                .payingWith(ACCOUNT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                getAccountBalance(ACCOUNT).logged(),
                getAccountBalance(TOKEN_CREATE_CONTRACT).logged(),
                getContractInfo(TOKEN_CREATE_CONTRACT).logged(),
                childRecordsCheck(
                        FIRST_CREATE_TXN,
                        ResponseCodeEnum.CONTRACT_REVERT_EXECUTED,
                        TransactionRecordAsserts.recordWith()
                                .status(INVALID_RENEWAL_PERIOD)
                                .contractCallResult(
                                        ContractFnResultAsserts.resultWith().error(INVALID_RENEWAL_PERIOD.name()))));
    }

    // TEST-013
    @HapiTest
    final Stream<DynamicTest> createTokenWithInvalidTreasury() {
        return hapiTest(
                newKeyNamed(ED25519KEY).shape(ED25519),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS).key(ED25519KEY),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_CREATE_CONTRACT,
                                        CREATE_NFT_WITH_KEYS_AND_EXPIRY_FUNCTION,
                                        HapiParserUtil.asHeadlongAddress(
                                                (byte[]) ArrayUtils.toPrimitive(asSolidityAddress(spec, 999_999_999L))),
                                        spec.registry()
                                                .getKey(ED25519KEY)
                                                .getEd25519()
                                                .toByteArray(),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        AUTO_RENEW_PERIOD)
                                .via(FIRST_CREATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .sending(DEFAULT_AMOUNT_TO_SEND)
                                .payingWith(ACCOUNT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords(),
                getAccountBalance(ACCOUNT).logged(),
                getAccountBalance(TOKEN_CREATE_CONTRACT).logged(),
                getContractInfo(TOKEN_CREATE_CONTRACT).logged(),
                childRecordsCheck(
                        FIRST_CREATE_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        TransactionRecordAsserts.recordWith()
                                .status(INVALID_ACCOUNT_ID)
                                .contractCallResult(
                                        ContractFnResultAsserts.resultWith().error(INVALID_ACCOUNT_ID.name()))));
    }

    // TEST-018
    // Should fail on insufficient value sent
    @HapiTest
    final Stream<DynamicTest> createTokenWithInsufficientValueSent() {
        return hapiTest(
                newKeyNamed(ED25519KEY).shape(ED25519),
                cryptoCreate(ACCOUNT).key(ED25519KEY).balance(ONE_MILLION_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                withOpContext((spec, opLog) ->
                        allRunFor(spec, contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER))),
                withOpContext((spec, ignore) -> {
                    final var balanceSnapshot = spec.isUsingEthCalls()
                            ? balanceSnapshot(ACCOUNT_BALANCE, DEFAULT_CONTRACT_SENDER)
                            : balanceSnapshot(ACCOUNT_BALANCE, ACCOUNT);
                    final long sentAmount = ONE_HBAR / 100;
                    final var hapiContractCall = contractCall(
                                    TOKEN_CREATE_CONTRACT,
                                    CREATE_NFT_WITH_KEYS_AND_EXPIRY_FUNCTION,
                                    HapiParserUtil.asHeadlongAddress(
                                            asAddress(spec.registry().getAccountID(ACCOUNT))),
                                    spec.registry()
                                            .getKey(ED25519KEY)
                                            .getEd25519()
                                            .toByteArray(),
                                    HapiParserUtil.asHeadlongAddress(
                                            asAddress(spec.registry().getAccountID(ACCOUNT))),
                                    AUTO_RENEW_PERIOD)
                            .via(FIRST_CREATE_TXN)
                            .gas(GAS_TO_OFFER)
                            .sending(sentAmount)
                            .payingWith(ACCOUNT)
                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED);
                    final var txnRecord = getTxnRecord(FIRST_CREATE_TXN);
                    allRunFor(
                            spec,
                            balanceSnapshot,
                            hapiContractCall,
                            txnRecord,
                            getAccountBalance(TOKEN_CREATE_CONTRACT).hasTinyBars(0L),
                            childRecordsCheck(
                                    FIRST_CREATE_TXN,
                                    ResponseCodeEnum.CONTRACT_REVERT_EXECUTED,
                                    TransactionRecordAsserts.recordWith()
                                            .status(INSUFFICIENT_TX_FEE)
                                            .contractCallResult(ContractFnResultAsserts.resultWith()
                                                    .error(INSUFFICIENT_TX_FEE.name()))));
                    final var delta = spec.isUsingEthCalls()
                            ? GAS_TO_OFFER * HapiEthereumCall.DEFAULT_GAS_PRICE_TINYBARS
                            : txnRecord.getResponseRecord().getTransactionFee();
                    final var effectivePayer = spec.isUsingEthCalls() ? DEFAULT_CONTRACT_SENDER : ACCOUNT;
                    var changeFromSnapshot = getAccountBalance(effectivePayer)
                            .hasTinyBars(changeFromSnapshot(ACCOUNT_BALANCE, -(delta)));
                    allRunFor(spec, changeFromSnapshot);
                }),
                getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords(),
                getAccountBalance(ACCOUNT));
    }

    // TEST-020
    @HapiTest
    final Stream<DynamicTest> delegateCallTokenCreateFails() {
        return hapiTest(
                newKeyNamed(ED25519KEY).shape(ED25519),
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS).key(ED25519KEY),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                contractCreate(TOKEN_CREATE_CONTRACT).gas(CONTRACT_CREATE_GAS_TO_OFFER),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_CREATE_CONTRACT,
                                        "delegateCallCreate",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        AUTO_RENEW_PERIOD)
                                .via(FIRST_CREATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .payingWith(ACCOUNT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getTxnRecord(FIRST_CREATE_TXN).hasNonStakingChildRecordCount(0),
                getAccountBalance(ACCOUNT),
                getAccountBalance(TOKEN_CREATE_CONTRACT),
                getContractInfo(TOKEN_CREATE_CONTRACT));
    }

    @HapiTest
    final Stream<DynamicTest> createTokenWithFixedFeeThenTransferAndAssessFee() {
        final var createTokenNum = new AtomicLong();
        final var FEE_COLLECTOR = "feeCollector";
        final var RECIPIENT = "recipient";
        final var SECOND_RECIPIENT = "secondRecipient";
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECIPIENT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_RECIPIENT),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                uploadInitCode(TOKEN_MISC_OPERATIONS_CONTRACT),
                contractCreate(TOKEN_MISC_OPERATIONS_CONTRACT)
                        .gas(CONTRACT_CREATE_GAS_TO_OFFER)
                        .autoRenewAccountId(ACCOUNT),
                newKeyNamed(THRESHOLD_KEY)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, TOKEN_MISC_OPERATIONS_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                cryptoUpdate(FEE_COLLECTOR).key(THRESHOLD_KEY),
                cryptoUpdate(RECIPIENT).key(THRESHOLD_KEY),
                cryptoUpdate(SECOND_RECIPIENT).key(THRESHOLD_KEY),
                cryptoTransfer(
                        TokenMovement.movingHbar(ONE_HUNDRED_HBARS).between(GENESIS, TOKEN_MISC_OPERATIONS_CONTRACT)),
                getContractInfo(TOKEN_MISC_OPERATIONS_CONTRACT)
                        .has(ContractInfoAsserts.contractWith().autoRenewAccountId(ACCOUNT))
                        .logged(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_MISC_OPERATIONS_CONTRACT,
                                        "createTokenWithHbarsFixedFeeAndTransferIt",
                                        10L,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(FEE_COLLECTOR))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECIPIENT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(SECOND_RECIPIENT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))))
                                .via(FIRST_CREATE_TXN)
                                .gas(CONTRACT_CREATE_GAS_TO_OFFER)
                                .sending(DEFAULT_AMOUNT_TO_SEND)
                                .payingWith(ACCOUNT)
                                .exposingResultTo(result -> {
                                    log.info(EXPLICIT_CREATE_RESULT, result[0]);
                                    final var res = (Address) result[0];
                                    createTokenNum.set(numberOfLongZero(HexFormat.of()
                                            .parseHex(res.toString().substring(2))));
                                })
                                .hasKnownStatus(SUCCESS))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord(FIRST_CREATE_TXN).andAllChildRecords().logged(),
                        getAccountBalance(RECIPIENT).hasTokenBalance(String.valueOf(createTokenNum.get()), 0L),
                        getAccountBalance(SECOND_RECIPIENT).hasTokenBalance(String.valueOf(createTokenNum.get()), 1L),
                        getAccountBalance(ACCOUNT).hasTokenBalance(String.valueOf(createTokenNum.get()), 199L),
                        getAccountBalance(FEE_COLLECTOR).hasTinyBars(10L))));
    }

    private static long numberOfLongZero(@NonNull final byte[] explicit) {
        return longFrom(
                explicit[12],
                explicit[13],
                explicit[14],
                explicit[15],
                explicit[16],
                explicit[17],
                explicit[18],
                explicit[19]);
    }

    private static long longFrom(
            final byte b1,
            final byte b2,
            final byte b3,
            final byte b4,
            final byte b5,
            final byte b6,
            final byte b7,
            final byte b8) {
        return (b1 & 0xFFL) << 56
                | (b2 & 0xFFL) << 48
                | (b3 & 0xFFL) << 40
                | (b4 & 0xFFL) << 32
                | (b5 & 0xFFL) << 24
                | (b6 & 0xFFL) << 16
                | (b7 & 0xFFL) << 8
                | (b8 & 0xFFL);
    }
}
