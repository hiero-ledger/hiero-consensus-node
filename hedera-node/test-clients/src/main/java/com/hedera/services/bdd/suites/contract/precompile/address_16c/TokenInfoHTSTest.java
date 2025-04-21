// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.address_16c;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_16C;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
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
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.precompile.TokenInfoHTSSuite.getTokenKeyFromSpec;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.token.HapiGetTokenInfo;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FixedFee;
import com.hederahashgraph.api.proto.java.Fraction;
import com.hederahashgraph.api.proto.java.FractionalFee;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.RoyaltyFee;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.TokenKycStatus;
import com.hederahashgraph.api.proto.java.TokenNftInfo;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class TokenInfoHTSTest {

    private static final String TOKEN_INFO_CONTRACT = "TokenInfoContract";
    private static final String ADMIN_KEY = TokenKeyType.ADMIN_KEY.name();
    private static final String KYC_KEY = TokenKeyType.KYC_KEY.name();
    private static final String SUPPLY_KEY = TokenKeyType.SUPPLY_KEY.name();
    private static final String FREEZE_KEY = TokenKeyType.FREEZE_KEY.name();
    private static final String WIPE_KEY = TokenKeyType.WIPE_KEY.name();
    private static final String FEE_SCHEDULE_KEY = TokenKeyType.FEE_SCHEDULE_KEY.name();
    private static final String PAUSE_KEY = TokenKeyType.PAUSE_KEY.name();

    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String FEE_DENOM = "denom";
    public static final String HTS_COLLECTOR = "denomFee";
    private static final String CREATE_TXN = "CreateTxn";
    private static final String FUNGIBLE_SYMBOL = "FT";
    private static final String FUNGIBLE_TOKEN_NAME = "FungibleToken";
    private static final String NON_FUNGIBLE_SYMBOL = "NFT";
    private static final String META = "First";
    private static final String MEMO = "JUMP";
    private static final String NFT_OWNER = "NFT Owner";
    private static final String NFT_SPENDER = "NFT Spender";
    private static final String NON_FUNGIBLE_TOKEN_NAME = "NonFungibleToken";
    private static final String APPROVE_TXN = "approveTxn";

    private static final int NUMERATOR = 1;
    private static final int DENOMINATOR = 2;
    private static final int MINIMUM_TO_COLLECT = 5;
    private static final int MAXIMUM_TO_COLLECT = 400;
    private static final int MAX_SUPPLY = 1000;

    @HapiTest
    final Stream<DynamicTest> happyPathGetFungibleTokenInfo() {
        final int decimals = 1;
        final AtomicReference<ByteString> targetLedgerId = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                cryptoCreate(HTS_COLLECTOR),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(KYC_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                newKeyNamed(FEE_SCHEDULE_KEY),
                newKeyNamed(PAUSE_KEY),
                newKeyNamed(TokenKeyType.METADATA_KEY.name()),
                uploadInitCode(Optional.empty(), VARIANT_16C, TOKEN_INFO_CONTRACT),
                contractCreate(TOKEN_INFO_CONTRACT).gas(6_000_000L),
                uploadInitCode("TokenInfo"),
                contractCreate("TokenInfo").gas(6_000_000L),
                tokenCreate(FUNGIBLE_TOKEN_NAME)
                        .supplyType(TokenSupplyType.FINITE)
                        .entityMemo(MEMO)
                        .name(FUNGIBLE_TOKEN_NAME)
                        .symbol(FUNGIBLE_SYMBOL)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .maxSupply(MAX_SUPPLY)
                        .initialSupply(500)
                        .decimals(decimals)
                        .adminKey(ADMIN_KEY)
                        .freezeKey(FREEZE_KEY)
                        .kycKey(KYC_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .wipeKey(WIPE_KEY)
                        .feeScheduleKey(FEE_SCHEDULE_KEY)
                        .pauseKey(PAUSE_KEY)
                        .metadataKey(TokenKeyType.METADATA_KEY.name())
                        .metaData("metadata")
                        .withCustom(fixedHbarFee(500L, HTS_COLLECTOR))
                        // Also include a fractional fee with no minimum to collect
                        .withCustom(fractionalFee(NUMERATOR, DENOMINATOR * 2L, 0, OptionalLong.empty(), TOKEN_TREASURY))
                        .withCustom(fractionalFee(
                                NUMERATOR,
                                DENOMINATOR,
                                MINIMUM_TO_COLLECT,
                                OptionalLong.of(MAXIMUM_TO_COLLECT),
                                TOKEN_TREASURY))
                        .via(CREATE_TXN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        "getInformationForFungibleToken",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN_NAME))))
                                .via("FUNGIBLE_TOKEN_INFO_TXN_16C")
                                .gas(1_000_000L))),
                exposeTargetLedgerIdTo(targetLedgerId::set),
                withOpContext((spec, opLog) -> {
                    final var getTokenInfoQuery = getTokenInfo(FUNGIBLE_TOKEN_NAME);
                    allRunFor(spec, getTokenInfoQuery);
                    final var expirySecond = getTokenInfoQuery
                            .getResponse()
                            .getTokenGetInfo()
                            .getTokenInfo()
                            .getExpiry()
                            .getSeconds();

                    allRunFor(
                            spec,
                            childRecordsCheck(
                                    "FUNGIBLE_TOKEN_INFO_TXN_16C",
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(FunctionType.HAPI_GET_FUNGIBLE_TOKEN_INFO_V2)
                                                            .withStatus(SUCCESS)
                                                            .withDecimals(decimals)
                                                            .withTokenInfo(
                                                                    getTokenInfoStructForFungibleToken16c(
                                                                            spec,
                                                                            FUNGIBLE_TOKEN_NAME,
                                                                            FUNGIBLE_SYMBOL,
                                                                            MEMO,
                                                                            spec.registry()
                                                                                    .getAccountID(TOKEN_TREASURY),
                                                                            getTokenKeyFromSpec(
                                                                                    spec, TokenKeyType.ADMIN_KEY),
                                                                            expirySecond,
                                                                            targetLedgerId.get(),
                                                                            TokenKycStatus.Revoked))))));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> happyPathGetNonFungibleTokenInfo() {
        final int maxSupply = 10;
        final ByteString meta = ByteString.copyFrom(META.getBytes(StandardCharsets.UTF_8));
        final AtomicReference<ByteString> targetLedgerId = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                cryptoCreate(NFT_OWNER),
                cryptoCreate(NFT_SPENDER),
                cryptoCreate(HTS_COLLECTOR),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(KYC_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                newKeyNamed(FEE_SCHEDULE_KEY),
                newKeyNamed(PAUSE_KEY),
                newKeyNamed(TokenKeyType.METADATA_KEY.name()),
                uploadInitCode(Optional.empty(), VARIANT_16C, TOKEN_INFO_CONTRACT),
                contractCreate(TOKEN_INFO_CONTRACT).gas(6_000_000L),
                uploadInitCode("TokenInfo"),
                contractCreate("TokenInfo").gas(6_000_000L),
                tokenCreate(FEE_DENOM).treasury(HTS_COLLECTOR),
                tokenCreate(NON_FUNGIBLE_TOKEN_NAME)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .entityMemo(MEMO)
                        .name(NON_FUNGIBLE_TOKEN_NAME)
                        .symbol(NON_FUNGIBLE_SYMBOL)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .maxSupply(maxSupply)
                        .initialSupply(0)
                        .adminKey(ADMIN_KEY)
                        .freezeKey(FREEZE_KEY)
                        .kycKey(KYC_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .wipeKey(WIPE_KEY)
                        .feeScheduleKey(FEE_SCHEDULE_KEY)
                        .pauseKey(PAUSE_KEY)
                        .metadataKey(TokenKeyType.METADATA_KEY.name())
                        .metaData("metadata")
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHtsFeeInheritingRoyaltyCollector(100, FEE_DENOM), HTS_COLLECTOR))
                        .via(CREATE_TXN),
                mintToken(NON_FUNGIBLE_TOKEN_NAME, List.of(meta)),
                tokenAssociate(NFT_OWNER, List.of(NON_FUNGIBLE_TOKEN_NAME)),
                tokenAssociate(NFT_SPENDER, List.of(NON_FUNGIBLE_TOKEN_NAME)),
                grantTokenKyc(NON_FUNGIBLE_TOKEN_NAME, NFT_OWNER),
                cryptoTransfer(
                        TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN_NAME, 1L).between(TOKEN_TREASURY, NFT_OWNER)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftAllowance(NFT_OWNER, NON_FUNGIBLE_TOKEN_NAME, NFT_SPENDER, false, List.of(1L))
                        .via(APPROVE_TXN)
                        .logged()
                        .signedBy(DEFAULT_PAYER, NFT_OWNER)
                        .fee(ONE_HBAR),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        "getInformationForNonFungibleToken",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN_NAME))),
                                        1L)
                                .via("NON_FUNGIBLE_TOKEN_INFO_TXN_16C")
                                .gas(1_000_000L))),
                exposeTargetLedgerIdTo(targetLedgerId::set),
                withOpContext((spec, opLog) -> {
                    final var getTokenInfoQuery = getTokenInfo(NON_FUNGIBLE_TOKEN_NAME);
                    allRunFor(spec, getTokenInfoQuery);
                    final var expirySecond = getTokenInfoQuery
                            .getResponse()
                            .getTokenGetInfo()
                            .getTokenInfo()
                            .getExpiry()
                            .getSeconds();

                    final var nftTokenInfo =
                            getTokenNftInfoForCheck(spec, getTokenInfoQuery, meta, targetLedgerId.get());

                    allRunFor(
                            spec,
                            childRecordsCheck(
                                    "NON_FUNGIBLE_TOKEN_INFO_TXN_16C",
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(
                                                                    FunctionType.HAPI_GET_NON_FUNGIBLE_TOKEN_INFO_V2)
                                                            .withStatus(SUCCESS)
                                                            .withTokenInfo(
                                                                    getTokenInfoStructForNonFungibleToken16c(
                                                                            spec,
                                                                            spec.registry()
                                                                                    .getAccountID(TOKEN_TREASURY),
                                                                            getTokenKeyFromSpec(
                                                                                    spec, TokenKeyType.ADMIN_KEY),
                                                                            expirySecond,
                                                                            targetLedgerId.get(),
                                                                            TokenKycStatus.Revoked,
                                                                            1L))
                                                            .withNftTokenInfo(nftTokenInfo)))));
                }));
    }

    private TokenInfo getTokenInfoStructForFungibleToken16c(
            final HapiSpec spec,
            final String tokenName,
            final String symbol,
            final String memo,
            final AccountID treasury,
            final Key adminKey,
            final long expirySecond,
            ByteString ledgerId,
            final TokenKycStatus kycDefault) {

        final ByteString meta = ByteString.copyFrom("metadata".getBytes(StandardCharsets.UTF_8));

        return buildBaseTokenInfo(spec, tokenName, symbol, memo, treasury, adminKey, expirySecond, ledgerId, kycDefault)
                .setMetadata(meta)
                .setMetadataKey(getTokenKeyFromSpec(spec, TokenKeyType.METADATA_KEY))
                .build();
    }

    private TokenInfo getTokenInfoStructForNonFungibleToken16c(
            final HapiSpec spec,
            final AccountID treasury,
            final Key adminKey,
            final long expirySecond,
            final ByteString ledgerId,
            final TokenKycStatus kycDefault,
            final long totalSupply) {
        final ByteString meta = ByteString.copyFrom("metadata".getBytes(StandardCharsets.UTF_8));
        return buildTokenInfo(
                spec,
                NON_FUNGIBLE_TOKEN_NAME,
                NON_FUNGIBLE_SYMBOL,
                MEMO,
                treasury,
                adminKey,
                expirySecond,
                ledgerId,
                meta,
                true,
                kycDefault,
                totalSupply);
    }

    private static TokenInfo buildTokenInfo(
            final HapiSpec spec,
            final String tokenName,
            final String symbol,
            final String memo,
            final AccountID treasury,
            final Key adminKey,
            final long expirySecond,
            final ByteString ledgerId,
            final ByteString metadata,
            final boolean includeMetadataKey,
            final TokenKycStatus kycDefault,
            final long totalSupply) {
        final var autoRenewAccount = spec.registry().getAccountID(AUTO_RENEW_ACCOUNT);

        TokenInfo.Builder builder = TokenInfo.newBuilder()
                .setLedgerId(ledgerId)
                .setSupplyTypeValue(TokenSupplyType.FINITE_VALUE)
                .setExpiry(Timestamp.newBuilder().setSeconds(expirySecond))
                .setAutoRenewAccount(autoRenewAccount)
                .setAutoRenewPeriod(Duration.newBuilder()
                        .setSeconds(THREE_MONTHS_IN_SECONDS)
                        .build())
                .setSymbol(symbol)
                .setName(tokenName)
                .setMemo(memo)
                .setTreasury(treasury)
                .setTotalSupply(totalSupply)
                .setMaxSupply(10L)
                .addAllCustomFees(getCustomFeeForNFT(spec))
                .setAdminKey(adminKey)
                .setKycKey(getTokenKeyFromSpec(spec, TokenKeyType.KYC_KEY))
                .setFreezeKey(getTokenKeyFromSpec(spec, TokenKeyType.FREEZE_KEY))
                .setWipeKey(getTokenKeyFromSpec(spec, TokenKeyType.WIPE_KEY))
                .setSupplyKey(getTokenKeyFromSpec(spec, TokenKeyType.SUPPLY_KEY))
                .setFeeScheduleKey(getTokenKeyFromSpec(spec, TokenKeyType.FEE_SCHEDULE_KEY))
                .setPauseKey(getTokenKeyFromSpec(spec, TokenKeyType.PAUSE_KEY))
                .setDefaultKycStatus(kycDefault);

        if (metadata != null) {
            builder.setMetadata(metadata);
        }

        if (includeMetadataKey) {
            builder.setMetadataKey(getTokenKeyFromSpec(spec, TokenKeyType.METADATA_KEY));
        }

        return builder.build();
    }

    private static TokenInfo.Builder buildBaseTokenInfo(
            final HapiSpec spec,
            final String tokenName,
            final String symbol,
            final String memo,
            final AccountID treasury,
            final Key adminKey,
            final long expirySecond,
            ByteString ledgerId,
            final TokenKycStatus kycDefault) {

        final var autoRenewAccount = spec.registry().getAccountID(AUTO_RENEW_ACCOUNT);
        final var customFees = getExpectedCustomFees(spec);

        return TokenInfo.newBuilder()
                .setLedgerId(ledgerId)
                .setSupplyTypeValue(TokenSupplyType.FINITE_VALUE)
                .setExpiry(Timestamp.newBuilder().setSeconds(expirySecond))
                .setAutoRenewAccount(autoRenewAccount)
                .setAutoRenewPeriod(Duration.newBuilder()
                        .setSeconds(THREE_MONTHS_IN_SECONDS)
                        .build())
                .setSymbol(symbol)
                .setName(tokenName)
                .setMemo(memo)
                .setTreasury(treasury)
                .setTotalSupply(500L)
                .setMaxSupply(MAX_SUPPLY)
                .addAllCustomFees(customFees)
                .setAdminKey(adminKey)
                .setKycKey(getTokenKeyFromSpec(spec, TokenKeyType.KYC_KEY))
                .setFreezeKey(getTokenKeyFromSpec(spec, TokenKeyType.FREEZE_KEY))
                .setWipeKey(getTokenKeyFromSpec(spec, TokenKeyType.WIPE_KEY))
                .setSupplyKey(getTokenKeyFromSpec(spec, TokenKeyType.SUPPLY_KEY))
                .setFeeScheduleKey(getTokenKeyFromSpec(spec, TokenKeyType.FEE_SCHEDULE_KEY))
                .setPauseKey(getTokenKeyFromSpec(spec, TokenKeyType.PAUSE_KEY))
                .setDefaultKycStatus(kycDefault);
    }

    @NonNull
    private static ArrayList<CustomFee> getExpectedCustomFees(final HapiSpec spec) {
        final var fixedFee = FixedFee.newBuilder().setAmount(500L).build();
        final var customFixedFee = CustomFee.newBuilder()
                .setFixedFee(fixedFee)
                .setFeeCollectorAccountId(spec.registry().getAccountID(HTS_COLLECTOR))
                .build();

        final var firstFraction = Fraction.newBuilder()
                .setNumerator(NUMERATOR)
                .setDenominator(DENOMINATOR * 2L)
                .build();
        final var firstFractionalFee =
                FractionalFee.newBuilder().setFractionalAmount(firstFraction).build();
        final var firstCustomFractionalFee = CustomFee.newBuilder()
                .setFractionalFee(firstFractionalFee)
                .setFeeCollectorAccountId(spec.registry().getAccountID(TOKEN_TREASURY))
                .build();

        final var fraction = Fraction.newBuilder()
                .setNumerator(NUMERATOR)
                .setDenominator(DENOMINATOR)
                .build();
        final var fractionalFee = FractionalFee.newBuilder()
                .setFractionalAmount(fraction)
                .setMinimumAmount(MINIMUM_TO_COLLECT)
                .setMaximumAmount(MAXIMUM_TO_COLLECT)
                .build();
        final var customFractionalFee = CustomFee.newBuilder()
                .setFractionalFee(fractionalFee)
                .setFeeCollectorAccountId(spec.registry().getAccountID(TOKEN_TREASURY))
                .build();

        final var customFees = new ArrayList<CustomFee>();
        customFees.add(customFixedFee);
        customFees.add(firstCustomFractionalFee);
        customFees.add(customFractionalFee);
        return customFees;
    }

    @NonNull
    private static ArrayList<CustomFee> getCustomFeeForNFT(final HapiSpec spec) {
        final var fraction = Fraction.newBuilder()
                .setNumerator(NUMERATOR)
                .setDenominator(DENOMINATOR)
                .build();
        final var fallbackFee = FixedFee.newBuilder()
                .setAmount(100L)
                .setDenominatingTokenId(spec.registry().getTokenID(FEE_DENOM))
                .build();
        final var royaltyFee = RoyaltyFee.newBuilder()
                .setExchangeValueFraction(fraction)
                .setFallbackFee(fallbackFee)
                .build();

        final var customRoyaltyFee = CustomFee.newBuilder()
                .setRoyaltyFee(royaltyFee)
                .setFeeCollectorAccountId(spec.registry().getAccountID(HTS_COLLECTOR))
                .build();

        final var customFees = new ArrayList<CustomFee>();
        customFees.add(customRoyaltyFee);

        return customFees;
    }

    private TokenNftInfo getTokenNftInfoForCheck(
            final HapiSpec spec, final HapiGetTokenInfo getTokenInfoQuery, final ByteString meta, ByteString ledgerId) {
        final var tokenId =
                getTokenInfoQuery.getResponse().getTokenGetInfo().getTokenInfo().getTokenId();

        final var getNftTokenInfoQuery = getTokenNftInfo(NON_FUNGIBLE_TOKEN_NAME, 1L);
        allRunFor(spec, getNftTokenInfoQuery);
        final var creationTime =
                getNftTokenInfoQuery.getResponse().getTokenGetNftInfo().getNft().getCreationTime();

        final var ownerId = spec.registry().getAccountID(NFT_OWNER);
        final var spenderId = spec.registry().getAccountID(NFT_SPENDER);

        return TokenNftInfo.newBuilder()
                .setLedgerId(ledgerId)
                .setNftID(NftID.newBuilder()
                        .setTokenID(tokenId)
                        .setSerialNumber(1L)
                        .build())
                .setAccountID(ownerId)
                .setCreationTime(creationTime)
                .setMetadata(meta)
                .setSpenderId(spenderId)
                .build();
    }
}
