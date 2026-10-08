// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.exposeTargetLedgerIdTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.precompile.TokenInfoHTSSuite.getTokenInfoStructForFungibleToken;
import static com.hedera.services.bdd.suites.contract.precompile.TokenInfoHTSSuite.getTokenInfoStructForNonFungibleToken;
import static com.hedera.services.bdd.suites.contract.precompile.TokenInfoHTSSuite.getTokenKeyFromSpec;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Function;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.schedule.HapiScheduleCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.TokenKycStatus;
import com.hederahashgraph.api.proto.java.TokenNftInfo;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class GetScheduledInfoTest {

    // Use a high entity number to avoid collisions with sequentially assigned entity IDs
    // from concurrently running tests, which could cause INVALID_SCHEDULE_ID instead of
    // RECORD_NOT_FOUND when the colliding entity is not a matching token creation schedule
    private static final long NON_EXISTENT_SCHEDULE_NUM = 999_999_999L;
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String HTS_COLLECTOR = "denomFee";
    private static final String TOKEN_TREASURY = "treasury";
    private static final String GET_FUNGIBLE_CREATE_TOKEN_INFO = "getFungibleCreateTokenInfo";
    private static final String GET_NON_FUNGIBLE_CREATE_TOKEN_INFO = "getNonFungibleCreateTokenInfo";
    private static final String ADMIN_KEY = TokenKeyType.ADMIN_KEY.name();
    private static final String KYC_KEY = TokenKeyType.KYC_KEY.name();
    private static final String SUPPLY_KEY = TokenKeyType.SUPPLY_KEY.name();
    private static final String FREEZE_KEY = TokenKeyType.FREEZE_KEY.name();
    private static final String WIPE_KEY = TokenKeyType.WIPE_KEY.name();
    private static final String FEE_SCHEDULE_KEY = TokenKeyType.FEE_SCHEDULE_KEY.name();
    private static final String PAUSE_KEY = TokenKeyType.PAUSE_KEY.name();
    private static final String FEE_DENOM = "denom";
    private static final int NUMERATOR = 1;
    private static final int DENOMINATOR = 2;
    private static final int MINIMUM_TO_COLLECT = 5;
    private static final int MAXIMUM_TO_COLLECT = 400;
    private static final String SCHEDULED_CREATE_FT = "scheduledCreateFT";
    private static final String SCHEDULED_CREATE_NFT = "scheduledCreateNFT";
    private static final String HSS_ADDRESS = "0x000000000000000000000000000000000000016b";

    @Contract(contract = "GetScheduleInfo", creationGas = 5_000_000)
    static SpecContract contract;

    @Contract(contract = "MakeCalls", creationGas = 3_000_000)
    static SpecContract makeCalls;

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Cannot get scheduled info for non-existent fungible create schedule")
    public Stream<DynamicTest> cannotGetScheduledInfoForNonExistentFungibleCreateSchedule() {
        return hapiTest(withOpContext((spec, log) -> {
            final var callOp = contract.call(
                            GET_FUNGIBLE_CREATE_TOKEN_INFO,
                            asHeadlongAddress(asSolidityAddress(spec, NON_EXISTENT_SCHEDULE_NUM)))
                    .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, RECORD_NOT_FOUND));
            allRunFor(spec, callOp);
        }));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    @DisplayName("Cannot get scheduled info for non-existent NFT create schedule")
    public Stream<DynamicTest> cannotGetScheduledInfoForNonExistentNonFungibleCreateSchedule() {
        return hapiTest(withOpContext((spec, log) -> {
            final var callOp = contract.call(
                            GET_NON_FUNGIBLE_CREATE_TOKEN_INFO,
                            asHeadlongAddress(asSolidityAddress(spec, NON_EXISTENT_SCHEDULE_NUM)))
                    .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, RECORD_NOT_FOUND));
            allRunFor(spec, callOp);
        }));
    }

    @HapiTest
    @DisplayName("Can get scheduled info for fungible create schedule")
    public Stream<DynamicTest> canGetScheduleInfoForFungibleCreateSchedule() {
        final var scheduleId = new AtomicReference<ScheduleID>();
        final var ledgerId = new AtomicReference<ByteString>();
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    cryptoCreate(AUTO_RENEW_ACCOUNT),
                    cryptoCreate(HTS_COLLECTOR),
                    cryptoCreate(TOKEN_TREASURY),
                    newKeyNamed(FREEZE_KEY),
                    newKeyNamed(KYC_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    newKeyNamed(PAUSE_KEY),
                    exposeTargetLedgerIdTo(ledgerId::set),
                    newKeyNamed(ADMIN_KEY),
                    scheduleCreate(
                                    "scheduledCreateFT",
                                    tokenCreate("scheduledCreateFT")
                                            .supplyType(TokenSupplyType.FINITE)
                                            .expiry(0)
                                            .adminKey(ADMIN_KEY)
                                            .freezeKey(FREEZE_KEY)
                                            .kycKey(KYC_KEY)
                                            .supplyKey(SUPPLY_KEY)
                                            .wipeKey(WIPE_KEY)
                                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                                            .pauseKey(PAUSE_KEY)
                                            .treasury(TOKEN_TREASURY)
                                            .symbol("SCFT")
                                            .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                            .initialSupply(500L)
                                            .maxSupply(1000L)
                                            .withCustom(fixedHbarFee(500L, HTS_COLLECTOR))
                                            // Include a fractional fee with no minimum to collect
                                            .withCustom(fractionalFee(
                                                    NUMERATOR,
                                                    DENOMINATOR * 2L,
                                                    0,
                                                    OptionalLong.empty(),
                                                    TOKEN_TREASURY))
                                            .withCustom(fractionalFee(
                                                    NUMERATOR,
                                                    DENOMINATOR,
                                                    MINIMUM_TO_COLLECT,
                                                    OptionalLong.of(MAXIMUM_TO_COLLECT),
                                                    TOKEN_TREASURY)))
                            .exposingCreatedIdTo(scheduleId::set));
            allRunFor(
                    spec,
                    contract.call(GET_FUNGIBLE_CREATE_TOKEN_INFO, ConversionUtils.headlongAddressOf(scheduleId.get()))
                            .via("getFungibleTokenInfoTxn"),
                    childRecordsCheck(
                            "getFungibleTokenInfoTxn",
                            SUCCESS,
                            recordWith()
                                    .contractCallResult(resultWith()
                                            .contractCallResult(htsPrecompileResult()
                                                    .forFunction(FunctionType.HAPI_GET_FUNGIBLE_TOKEN_INFO)
                                                    .withStatus(SUCCESS)
                                                    .withTokenInfo(getTokenInfoStructForFungibleToken(
                                                            spec,
                                                            "scheduledCreateFT",
                                                            "SCFT",
                                                            "GetScheduledInfoTest.canGetScheduleInfoForFungibleCreateSchedule",
                                                            spec.registry().getAccountID(TOKEN_TREASURY),
                                                            getTokenKeyFromSpec(spec, TokenKeyType.ADMIN_KEY),
                                                            0,
                                                            ledgerId.get(),
                                                            TokenKycStatus.Revoked))))));
        }));
    }

    @HapiTest
    @DisplayName("Can get scheduled info for nft create schedule")
    public Stream<DynamicTest> canGetScheduleInfoForNonFungibleCreateSchedule() {
        final var scheduleId = new AtomicReference<ScheduleID>();
        final var ledgerId = new AtomicReference<ByteString>();
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(
                    spec,
                    cryptoCreate(AUTO_RENEW_ACCOUNT),
                    cryptoCreate(HTS_COLLECTOR),
                    cryptoCreate(TOKEN_TREASURY),
                    newKeyNamed(FREEZE_KEY),
                    newKeyNamed(KYC_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(FEE_SCHEDULE_KEY),
                    newKeyNamed(PAUSE_KEY),
                    newKeyNamed(TokenKeyType.METADATA_KEY.name()),
                    exposeTargetLedgerIdTo(ledgerId::set),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(FEE_DENOM).treasury(HTS_COLLECTOR),
                    scheduleCreate(
                                    "scheduledCreateNFT",
                                    tokenCreate("scheduledCreateNFT")
                                            .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                            .supplyType(TokenSupplyType.FINITE)
                                            .expiry(0)
                                            .adminKey(ADMIN_KEY)
                                            .freezeKey(FREEZE_KEY)
                                            .kycKey(KYC_KEY)
                                            .supplyKey(SUPPLY_KEY)
                                            .wipeKey(WIPE_KEY)
                                            .feeScheduleKey(FEE_SCHEDULE_KEY)
                                            .pauseKey(PAUSE_KEY)
                                            .treasury(TOKEN_TREASURY)
                                            .symbol("SCNFT")
                                            .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                            .initialSupply(0L)
                                            .maxSupply(10L)
                                            .withCustom(royaltyFeeWithFallback(
                                                    1,
                                                    2,
                                                    fixedHtsFeeInheritingRoyaltyCollector(100, FEE_DENOM),
                                                    HTS_COLLECTOR)))
                            .exposingCreatedIdTo(scheduleId::set));
            allRunFor(
                    spec,
                    contract.call(
                                    GET_NON_FUNGIBLE_CREATE_TOKEN_INFO,
                                    ConversionUtils.headlongAddressOf(scheduleId.get()))
                            .via("getInfoTxn"),
                    childRecordsCheck(
                            "getInfoTxn",
                            SUCCESS,
                            recordWith()
                                    .contractCallResult(resultWith()
                                            .contractCallResult(htsPrecompileResult()
                                                    .forFunction(FunctionType.HAPI_GET_NON_FUNGIBLE_TOKEN_INFO)
                                                    .withStatus(SUCCESS)
                                                    .withTokenInfo(getTokenInfoStructForNonFungibleToken(
                                                            spec,
                                                            "scheduledCreateNFT",
                                                            "SCNFT",
                                                            "GetScheduledInfoTest.canGetScheduleInfoForNonFungibleCreateSchedule",
                                                            spec.registry().getAccountID(TOKEN_TREASURY),
                                                            getTokenKeyFromSpec(spec, TokenKeyType.ADMIN_KEY),
                                                            0,
                                                            ledgerId.get(),
                                                            TokenKycStatus.Revoked,
                                                            0L))
                                                    .withNftTokenInfo(TokenNftInfo.newBuilder()
                                                            .setAccountID(spec.registry()
                                                                    .getAccountID(TOKEN_TREASURY))
                                                            .setSpenderId(spec.registry()
                                                                    .getAccountID(TOKEN_TREASURY))
                                                            .setCreationTime(Timestamp.newBuilder()
                                                                    .setSeconds(0)
                                                                    .setNanos(0)
                                                                    .build())
                                                            .setLedgerId(ledgerId.get())
                                                            .setMetadata(ByteString.EMPTY)
                                                            .setNftID(NftID.newBuilder()
                                                                    .build())
                                                            .build())))));
        }));
    }

    @HapiTest
    @DisplayName("Cannot get scheduled info for deleted fungible create schedule")
    public Stream<DynamicTest> cannotGetScheduledInfoForDeletedFungibleCreateSchedule() {
        final var scheduleId = new AtomicReference<ScheduleID>();
        final var ledgerId = new AtomicReference<ByteString>();
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, scheduledTokenCreateSetup());
            allRunFor(
                    spec,
                    exposeTargetLedgerIdTo(ledgerId::set),
                    // Treasury and auto-renew signatures are withheld, so the schedule stays pending until deleted
                    scheduledFungibleCreate().adminKey(ADMIN_KEY).exposingCreatedIdTo(scheduleId::set),
                    scheduleDelete(SCHEDULED_CREATE_FT).signedBy(DEFAULT_PAYER, ADMIN_KEY),
                    getScheduleInfo(SCHEDULED_CREATE_FT).isDeleted().isNotExecuted());
            allRunFor(
                    spec,
                    callReturnsEmptyInfo(
                            GET_FUNGIBLE_CREATE_TOKEN_INFO,
                            scheduleId.get(),
                            SCHEDULE_ALREADY_DELETED,
                            emptyFungibleInfo(SCHEDULE_ALREADY_DELETED, ledgerId.get())));
        }));
    }

    @HapiTest
    @DisplayName("Cannot get scheduled info for executed fungible create schedule")
    public Stream<DynamicTest> cannotGetScheduledInfoForExecutedFungibleCreateSchedule() {
        final var scheduleId = new AtomicReference<ScheduleID>();
        final var ledgerId = new AtomicReference<ByteString>();
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, scheduledTokenCreateSetup());
            allRunFor(
                    spec,
                    exposeTargetLedgerIdTo(ledgerId::set),
                    // All required signatures are present, so the scheduled token create executes immediately
                    scheduledFungibleCreate()
                            .alsoSigningWith(TOKEN_TREASURY, AUTO_RENEW_ACCOUNT, ADMIN_KEY, HTS_COLLECTOR)
                            .via("executedFungibleCreateTxn")
                            .exposingCreatedIdTo(scheduleId::set),
                    getScheduleInfo(SCHEDULED_CREATE_FT).isExecuted().isNotDeleted(),
                    getTxnRecord("executedFungibleCreateTxn")
                            .scheduled()
                            .hasPriority(recordWith().status(SUCCESS)));
            allRunFor(
                    spec,
                    callReturnsEmptyInfo(
                            GET_FUNGIBLE_CREATE_TOKEN_INFO,
                            scheduleId.get(),
                            SCHEDULE_ALREADY_EXECUTED,
                            emptyFungibleInfo(SCHEDULE_ALREADY_EXECUTED, ledgerId.get())));
        }));
    }

    @HapiTest
    @DisplayName("Cannot get scheduled info for deleted NFT create schedule")
    public Stream<DynamicTest> cannotGetScheduledInfoForDeletedNonFungibleCreateSchedule() {
        final var scheduleId = new AtomicReference<ScheduleID>();
        final var ledgerId = new AtomicReference<ByteString>();
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, scheduledTokenCreateSetup());
            allRunFor(
                    spec,
                    exposeTargetLedgerIdTo(ledgerId::set),
                    tokenCreate(FEE_DENOM).treasury(HTS_COLLECTOR),
                    // Treasury and auto-renew signatures are withheld, so the schedule stays pending until deleted
                    scheduledNonFungibleCreate().adminKey(ADMIN_KEY).exposingCreatedIdTo(scheduleId::set),
                    scheduleDelete(SCHEDULED_CREATE_NFT).signedBy(DEFAULT_PAYER, ADMIN_KEY),
                    getScheduleInfo(SCHEDULED_CREATE_NFT).isDeleted().isNotExecuted());
            allRunFor(
                    spec,
                    callReturnsEmptyInfo(
                            GET_NON_FUNGIBLE_CREATE_TOKEN_INFO,
                            scheduleId.get(),
                            SCHEDULE_ALREADY_DELETED,
                            emptyNonFungibleInfo(SCHEDULE_ALREADY_DELETED, ledgerId.get())));
        }));
    }

    @HapiTest
    @DisplayName("Cannot get scheduled info for executed NFT create schedule")
    public Stream<DynamicTest> cannotGetScheduledInfoForExecutedNonFungibleCreateSchedule() {
        final var scheduleId = new AtomicReference<ScheduleID>();
        final var ledgerId = new AtomicReference<ByteString>();
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, scheduledTokenCreateSetup());
            allRunFor(
                    spec,
                    exposeTargetLedgerIdTo(ledgerId::set),
                    tokenCreate(FEE_DENOM).treasury(HTS_COLLECTOR),
                    // All required signatures are present, so the scheduled token create executes immediately
                    scheduledNonFungibleCreate()
                            .alsoSigningWith(TOKEN_TREASURY, AUTO_RENEW_ACCOUNT, ADMIN_KEY, HTS_COLLECTOR)
                            .via("executedNonFungibleCreateTxn")
                            .exposingCreatedIdTo(scheduleId::set),
                    getScheduleInfo(SCHEDULED_CREATE_NFT).isExecuted().isNotDeleted(),
                    getTxnRecord("executedNonFungibleCreateTxn")
                            .scheduled()
                            .hasPriority(recordWith().status(SUCCESS)));
            allRunFor(
                    spec,
                    callReturnsEmptyInfo(
                            GET_NON_FUNGIBLE_CREATE_TOKEN_INFO,
                            scheduleId.get(),
                            SCHEDULE_ALREADY_EXECUTED,
                            emptyNonFungibleInfo(SCHEDULE_ALREADY_EXECUTED, ledgerId.get())));
        }));
    }

    @HapiTest
    @DisplayName("Scheduled info call for executed schedule completes with status instead of reverting")
    public Stream<DynamicTest> scheduledInfoCallForExecutedScheduleCompletesWithStatus() {
        final var scheduleId = new AtomicReference<ScheduleID>();
        final var ledgerId = new AtomicReference<ByteString>();
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, scheduledTokenCreateSetup());
            allRunFor(
                    spec,
                    exposeTargetLedgerIdTo(ledgerId::set),
                    scheduledFungibleCreate()
                            .alsoSigningWith(TOKEN_TREASURY, AUTO_RENEW_ACCOUNT, ADMIN_KEY, HTS_COLLECTOR)
                            .exposingCreatedIdTo(scheduleId::set),
                    getScheduleInfo(SCHEDULED_CREATE_FT).isExecuted());
            final var callData = new Function("getScheduledCreateFungibleTokenInfo(address)")
                    .encodeCallWithArgs(ConversionUtils.headlongAddressOf(scheduleId.get()))
                    .array();
            // Call the system contract directly, so the caller sees the raw call outcome and output
            allRunFor(
                    spec,
                    makeCalls
                            .call("makeCallWithoutAmount", asHeadlongAddress(HSS_ADDRESS), callData)
                            .via("directScheduledInfoTxn"),
                    getTxnRecord("directScheduledInfoTxn")
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .resultViaFunctionName(
                                                    "makeCallWithoutAmount",
                                                    makeCalls.name(),
                                                    isLiteralResult(new Object[] {
                                                        true,
                                                        emptyFungibleInfo(SCHEDULE_ALREADY_EXECUTED, ledgerId.get())
                                                                .getBytes()
                                                                .toArray()
                                                    })))),
                    childRecordsCheck(
                            "directScheduledInfoTxn", SUCCESS, recordWith().status(SCHEDULE_ALREADY_EXECUTED)));
        }));
    }

    /**
     * Calls the given scheduled info function through the {@code GetScheduleInfo} contract. The contract reverts on any
     * non-SUCCESS response code, but the system contract child record keeps its status and output.
     */
    private static SpecOperation[] callReturnsEmptyInfo(
            final String function,
            final ScheduleID scheduleId,
            final ResponseCodeEnum status,
            final HTSPrecompileResult expectedOutput) {
        final var txnName = function + status.name();
        return new SpecOperation[] {
            contract.call(function, ConversionUtils.headlongAddressOf(scheduleId))
                    .via(txnName)
                    .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
            childRecordsCheck(
                    txnName,
                    CONTRACT_REVERT_EXECUTED,
                    recordWith().status(status).contractCallResult(resultWith().contractCallResult(expectedOutput)))
        };
    }

    private static HTSPrecompileResult emptyFungibleInfo(final ResponseCodeEnum status, final ByteString ledgerId) {
        return htsPrecompileResult()
                .forFunction(FunctionType.HAPI_GET_FUNGIBLE_TOKEN_INFO)
                .withStatus(status)
                .withTokenInfo(TokenInfo.newBuilder().setLedgerId(ledgerId).build());
    }

    private static HTSPrecompileResult emptyNonFungibleInfo(final ResponseCodeEnum status, final ByteString ledgerId) {
        return htsPrecompileResult()
                .forFunction(FunctionType.HAPI_GET_NON_FUNGIBLE_TOKEN_INFO)
                .withStatus(status)
                .withTokenInfo(TokenInfo.newBuilder().setLedgerId(ledgerId).build())
                .withNftTokenInfo(TokenNftInfo.getDefaultInstance());
    }

    private static SpecOperation[] scheduledTokenCreateSetup() {
        return new SpecOperation[] {
            cryptoCreate(AUTO_RENEW_ACCOUNT),
            cryptoCreate(HTS_COLLECTOR),
            cryptoCreate(TOKEN_TREASURY),
            newKeyNamed(FREEZE_KEY),
            newKeyNamed(KYC_KEY),
            newKeyNamed(SUPPLY_KEY),
            newKeyNamed(WIPE_KEY),
            newKeyNamed(FEE_SCHEDULE_KEY),
            newKeyNamed(PAUSE_KEY),
            newKeyNamed(ADMIN_KEY)
        };
    }

    private static HapiScheduleCreate<HapiTokenCreate> scheduledFungibleCreate() {
        return scheduleCreate(
                SCHEDULED_CREATE_FT,
                tokenCreate(SCHEDULED_CREATE_FT)
                        .supplyType(TokenSupplyType.FINITE)
                        .expiry(0)
                        .adminKey(ADMIN_KEY)
                        .freezeKey(FREEZE_KEY)
                        .kycKey(KYC_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .wipeKey(WIPE_KEY)
                        .feeScheduleKey(FEE_SCHEDULE_KEY)
                        .pauseKey(PAUSE_KEY)
                        .treasury(TOKEN_TREASURY)
                        .symbol("SCFT")
                        .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                        .initialSupply(500L)
                        .maxSupply(1000L)
                        .withCustom(fixedHbarFee(500L, HTS_COLLECTOR))
                        // Include a fractional fee with no minimum to collect
                        .withCustom(fractionalFee(NUMERATOR, DENOMINATOR * 2L, 0, OptionalLong.empty(), TOKEN_TREASURY))
                        .withCustom(fractionalFee(
                                NUMERATOR,
                                DENOMINATOR,
                                MINIMUM_TO_COLLECT,
                                OptionalLong.of(MAXIMUM_TO_COLLECT),
                                TOKEN_TREASURY)));
    }

    private static HapiScheduleCreate<HapiTokenCreate> scheduledNonFungibleCreate() {
        return scheduleCreate(
                SCHEDULED_CREATE_NFT,
                tokenCreate(SCHEDULED_CREATE_NFT)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .expiry(0)
                        .adminKey(ADMIN_KEY)
                        .freezeKey(FREEZE_KEY)
                        .kycKey(KYC_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .wipeKey(WIPE_KEY)
                        .feeScheduleKey(FEE_SCHEDULE_KEY)
                        .pauseKey(PAUSE_KEY)
                        .treasury(TOKEN_TREASURY)
                        .symbol("SCNFT")
                        .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                        .initialSupply(0L)
                        .maxSupply(10L)
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHtsFeeInheritingRoyaltyCollector(100, FEE_DENOM), HTS_COLLECTOR)));
    }
}
