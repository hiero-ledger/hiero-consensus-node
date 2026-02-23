// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.schedule;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
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
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.precompile.TokenInfoHTSSuite.getTokenInfoStructForFungibleToken;
import static com.hedera.services.bdd.suites.contract.precompile.TokenInfoHTSSuite.getTokenInfoStructForNonFungibleToken;
import static com.hedera.services.bdd.suites.contract.precompile.TokenInfoHTSSuite.getTokenKeyFromSpec;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenKycStatus;
import com.hederahashgraph.api.proto.java.TokenNftInfo;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class GetScheduledInfoTest {

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

    @Contract(contract = "GetScheduleInfo", creationGas = 5_000_000)
    static SpecContract contract;

    @HapiTest
    @DisplayName("Cannot get scheduled info for non-existent fungible create schedule")
    public Stream<DynamicTest> cannotGetScheduledInfoForNonExistentFungibleCreateSchedule() {
        return hapiTest(withOpContext((spec, log) -> {
            final var callOp = contract.call(
                            GET_FUNGIBLE_CREATE_TOKEN_INFO, asHeadlongAddress(asSolidityAddress(spec, 1234)))
                    .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, RECORD_NOT_FOUND));
            allRunFor(spec, callOp);
        }));
    }

    @HapiTest
    @DisplayName("Cannot get scheduled info for non-existent NFT create schedule")
    public Stream<DynamicTest> cannotGetScheduledInfoForNonExistentNonFungibleCreateSchedule() {
        return hapiTest(withOpContext((spec, log) -> {
            final var callOp = contract.call(
                            GET_NON_FUNGIBLE_CREATE_TOKEN_INFO, asHeadlongAddress(asSolidityAddress(spec, 1234)))
                    .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, RECORD_NOT_FOUND));
            allRunFor(spec, callOp);
        }));
    }

    @HapiTest
    @DisplayName("Can get scheduled info for fungible create schedule")
    @Tag(MATS)
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
    @Tag(MATS)
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
}
