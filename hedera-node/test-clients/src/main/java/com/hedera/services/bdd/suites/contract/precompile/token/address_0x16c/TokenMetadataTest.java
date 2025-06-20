// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile.token.address_0x16c;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_16C;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.METADATA_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.PAUSE_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.exposeTargetLedgerIdTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.INFINITE_VALUE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.Key;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecKey;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FixedFee;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenInfo;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("metadata tests")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
public class TokenMetadataTest {

    @Account(maxAutoAssociations = 10, tinybarBalance = ONE_MILLION_HBARS)
    static SpecAccount alice;

    @Contract(contract = "CreateTokenVersioned", creationGas = 5_000_000L, variant = VARIANT_16C)
    static SpecContract contractTarget;

    @Contract(contract = "TokenInfo", creationGas = 5_000_000L, variant = VARIANT_16C)
    static SpecContract tokenInfoContract;

    @FungibleToken(name = "fungibleToken", initialSupply = 1_000L, maxSupply = 1_200L)
    static SpecFungibleToken fungibleToken;

    @NonFungibleToken(
            numPreMints = 5,
            keys = {SUPPLY_KEY, PAUSE_KEY, ADMIN_KEY, METADATA_KEY})
    static SpecNonFungibleToken nft;

    @Key()
    SpecKey key;

    @BeforeAll
    static void beforeAll(final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                alice.authorizeContract(contractTarget)
                        .alsoAuthorizing(TokenKeyType.SUPPLY_KEY, TokenKeyType.METADATA_KEY),
                nft.authorizeContracts(contractTarget)
                        .alsoAuthorizing(TokenKeyType.SUPPLY_KEY, TokenKeyType.METADATA_KEY),
                fungibleToken.authorizeContracts(contractTarget));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> testUpdateMetadata() {
        return Stream.of(nft, fungibleToken)
                .flatMap(token -> hapiTest(
                        contractTarget
                                .call("updateTokenMetadata", token, "randomMetaNew777")
                                .gas(1_000_000L)
                                .andAssert(txn -> txn.hasKnownStatus(SUCCESS)),
                        token.getInfo().andAssert(info -> info.hasMetadata("randomMetaNew777"))));
    }

    @HapiTest
    public Stream<DynamicTest> testUpdateTokenKeys() {
        return hapiTest(contractTarget
                .call("updateTokenKeys", nft, alice.getED25519KeyBytes(), contractTarget)
                .gas(1_000_000L)
                .payingWith(alice)
                .andAssert(txn -> txn.hasKnownStatus(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> createTokenVersionedHappyPath() {
        final AtomicReference<Address> newToken = new AtomicReference<>();
        final AtomicReference<ByteString> ledgerId = new AtomicReference<>();
        return hapiTest(withOpContext((spec, opLog) -> {
            final var create = contractTarget
                    .call("createTokenWithMetadata")
                    .sending(2000 * ONE_HBAR)
                    .gas(1_000_000L)
                    .exposingResultTo(res -> newToken.set((Address) res[0]));
            final var ledger = exposeTargetLedgerIdTo(ledgerId::set);
            allRunFor(spec, create, ledger);
            final var getInfo = tokenInfoContract
                    .call("getInformationForToken", newToken.get())
                    .gas(100_000L)
                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS).via("getInfo").logged());
            final var childRecord = childRecordsCheck(
                    "getInfo",
                    SUCCESS,
                    recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith()
                                    .contractCallResult(htsPrecompileResult()
                                            .forFunction(FunctionType.HAPI_GET_TOKEN_INFO_V2)
                                            .withStatus(SUCCESS)
                                            .withTokenInfo(tokenInfo(spec, ledgerId, newToken)
                                                    .build()))));
            allRunFor(spec, getInfo, childRecord);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createTokenVersionedWithKeyHappyPath() {
        final AtomicReference<Address> newToken = new AtomicReference<>();
        final AtomicReference<ByteString> ledgerId = new AtomicReference<>();
        return hapiTest(withOpContext((spec, opLog) -> {
            final var create = contractTarget
                    .call("createTokenWithMetadataAndKey")
                    .sending(2000 * ONE_HBAR)
                    .gas(1_000_000L)
                    .exposingResultTo(res -> newToken.set((Address) res[0]));
            final var ledger = exposeTargetLedgerIdTo(ledgerId::set);
            allRunFor(spec, create, ledger);
            final var getInfo = tokenInfoContract
                    .call("getInformationForToken", newToken.get())
                    .gas(100_000L)
                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS).via("getInfo").logged());
            final var childRecord = childRecordsCheck(
                    "getInfo",
                    SUCCESS,
                    recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith()
                                    .contractCallResult(htsPrecompileResult()
                                            .forFunction(FunctionType.HAPI_GET_TOKEN_INFO_V2)
                                            .withStatus(SUCCESS)
                                            .withTokenInfo(tokenInfo(spec, ledgerId, newToken)
                                                    .setMetadataKey(metaKey(spec))
                                                    .build()))));
            allRunFor(spec, getInfo, childRecord);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createTokenVersionedWithCustomFeesHappyPath() {
        final AtomicReference<Address> newToken = new AtomicReference<>();
        final AtomicReference<ByteString> ledgerId = new AtomicReference<>();
        return hapiTest(withOpContext((spec, opLog) -> {
            final var create = contractTarget
                    .call("createTokenWithMetadataAndCustomFees")
                    .sending(2000 * ONE_HBAR)
                    .gas(1_000_000L)
                    .exposingResultTo(res -> newToken.set((Address) res[0]));
            final var ledger = exposeTargetLedgerIdTo(ledgerId::set);
            allRunFor(spec, create, ledger);
            final var getInfo = tokenInfoContract
                    .call("getInformationForToken", newToken.get())
                    .gas(100_000L)
                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS).via("getInfo").logged());
            final var childRecord = childRecordsCheck(
                    "getInfo",
                    SUCCESS,
                    recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith()
                                    .contractCallResult(htsPrecompileResult()
                                            .forFunction(FunctionType.HAPI_GET_TOKEN_INFO_V2)
                                            .withStatus(SUCCESS)
                                            .withTokenInfo(tokenInfo(spec, ledgerId, newToken)
                                                    .addCustomFees(customFee(spec))
                                                    .build()))));
            allRunFor(spec, getInfo, childRecord);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createTokenVersionedWithKeyAndCustomFeesHappyPath() {
        final AtomicReference<Address> newToken = new AtomicReference<>();
        final AtomicReference<ByteString> ledgerId = new AtomicReference<>();
        return hapiTest(withOpContext((spec, opLog) -> {
            final var create = contractTarget
                    .call("createTokenWithMetadataAndKeyAndCustomFees")
                    .sending(2000 * ONE_HBAR)
                    .gas(1_000_000L)
                    .exposingResultTo(res -> newToken.set((Address) res[0]));
            final var ledger = exposeTargetLedgerIdTo(ledgerId::set);
            allRunFor(spec, create, ledger);
            final var getInfo = tokenInfoContract
                    .call("getInformationForToken", newToken.get())
                    .gas(100_000L)
                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS).via("getInfo").logged());
            final var childRecord = childRecordsCheck(
                    "getInfo",
                    SUCCESS,
                    recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith()
                                    .contractCallResult(htsPrecompileResult()
                                            .forFunction(FunctionType.HAPI_GET_TOKEN_INFO_V2)
                                            .withStatus(SUCCESS)
                                            .withTokenInfo(tokenInfo(spec, ledgerId, newToken)
                                                    .addCustomFees(customFee(spec))
                                                    .setMetadataKey(metaKey(spec))
                                                    .build()))));
            allRunFor(spec, getInfo);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createNftWithMetaHappyPath() {
        final AtomicReference<Address> newToken = new AtomicReference<>();
        final AtomicReference<ByteString> ledgerId = new AtomicReference<>();
        return hapiTest(withOpContext((spec, opLog) -> {
            final var create = contractTarget
                    .call("createNftWithMetadata")
                    .sending(2000 * ONE_HBAR)
                    .gas(1_000_000L)
                    .exposingResultTo(res -> newToken.set((Address) res[0]));
            final var ledger = exposeTargetLedgerIdTo(ledgerId::set);
            allRunFor(spec, create, ledger);
            final var getInfo = tokenInfoContract
                    .call("getInformationForToken", newToken.get())
                    .gas(100_000L)
                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS).via("getInfo").logged());
            final var childRecord = childRecordsCheck(
                    "getInfo",
                    SUCCESS,
                    recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith()
                                    .contractCallResult(htsPrecompileResult()
                                            .forFunction(FunctionType.HAPI_GET_TOKEN_INFO_V2)
                                            .withStatus(SUCCESS)
                                            .withTokenInfo(nftInfo(spec, ledgerId, newToken)
                                                    .build()))));
            allRunFor(spec, getInfo, childRecord);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createNftWithMetaAndKeyHappyPath() {
        final AtomicReference<Address> newToken = new AtomicReference<>();
        final AtomicReference<ByteString> ledgerId = new AtomicReference<>();
        return hapiTest(withOpContext((spec, opLog) -> {
            final var create = contractTarget
                    .call("createNftWithMetaAndKey")
                    .sending(2000 * ONE_HBAR)
                    .gas(5_000_000L)
                    .exposingResultTo(res -> newToken.set((Address) res[0]));
            final var ledger = exposeTargetLedgerIdTo(ledgerId::set);
            allRunFor(spec, create, ledger);
            final var getInfo = tokenInfoContract
                    .call("getInformationForToken", newToken.get())
                    .gas(100_000L)
                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS).via("getInfo").logged());
            final var childRecord = childRecordsCheck(
                    "getInfo",
                    SUCCESS,
                    recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith()
                                    .contractCallResult(htsPrecompileResult()
                                            .forFunction(FunctionType.HAPI_GET_TOKEN_INFO_V2)
                                            .withStatus(SUCCESS)
                                            .withTokenInfo(nftInfo(spec, ledgerId, newToken)
                                                    .setMetadataKey(metaKey(spec))
                                                    .build()))));
            allRunFor(spec, getInfo, childRecord);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createNftWithMetaAndCustomFeesHappyPath() {
        final AtomicReference<Address> newToken = new AtomicReference<>();
        final AtomicReference<ByteString> ledgerId = new AtomicReference<>();
        return hapiTest(withOpContext((spec, opLog) -> {
            final var create = contractTarget
                    .call("createNftWithMetadataAndCustomFees")
                    .sending(2000 * ONE_HBAR)
                    .gas(1_000_000L)
                    .exposingResultTo(res -> newToken.set((Address) res[0]));
            final var ledger = exposeTargetLedgerIdTo(ledgerId::set);
            allRunFor(spec, create, ledger);
            final var getInfo = tokenInfoContract
                    .call("getInformationForToken", newToken.get())
                    .gas(100_000L)
                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS).via("getInfo").logged());
            final var childRecord = childRecordsCheck(
                    "getInfo",
                    SUCCESS,
                    recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith()
                                    .contractCallResult(htsPrecompileResult()
                                            .forFunction(FunctionType.HAPI_GET_TOKEN_INFO_V2)
                                            .withStatus(SUCCESS)
                                            .withTokenInfo(nftInfo(spec, ledgerId, newToken)
                                                    .addCustomFees(customFee(spec))
                                                    .build()))));
            allRunFor(spec, getInfo, childRecord);
        }));
    }

    @HapiTest
    final Stream<DynamicTest> createNftWithMetaAndKeyAndCustomFeesHappyPath() {
        final AtomicReference<Address> newToken = new AtomicReference<>();
        final AtomicReference<ByteString> ledgerId = new AtomicReference<>();
        return hapiTest(withOpContext((spec, opLog) -> {
            final var create = contractTarget
                    .call("createNftWithMetaAndKeyAndCustomFees")
                    .sending(2000 * ONE_HBAR)
                    .gas(1_000_000L)
                    .exposingResultTo(res -> newToken.set((Address) res[0]));
            final var ledger = exposeTargetLedgerIdTo(ledgerId::set);
            allRunFor(spec, create, ledger);
            final var getInfo = tokenInfoContract
                    .call("getInformationForToken", newToken.get())
                    .gas(100_000L)
                    .andAssert(txn -> txn.hasKnownStatus(SUCCESS).via("getInfo").logged());
            final var childRecord = childRecordsCheck(
                    "getInfo",
                    SUCCESS,
                    recordWith()
                            .status(SUCCESS)
                            .contractCallResult(resultWith()
                                    .contractCallResult(htsPrecompileResult()
                                            .forFunction(FunctionType.HAPI_GET_TOKEN_INFO_V2)
                                            .withStatus(SUCCESS)
                                            .withTokenInfo(nftInfo(spec, ledgerId, newToken)
                                                    .setMetadataKey(metaKey(spec))
                                                    .addCustomFees(customFee(spec))
                                                    .build()))));
            allRunFor(spec, getInfo, childRecord);
        }));
    }

    private TokenInfo.Builder tokenInfo(
            final HapiSpec spec, final AtomicReference<ByteString> ledgerId, final AtomicReference<Address> newToken) {
        return TokenInfo.newBuilder()
                .setLedgerId(ledgerId.get())
                .setName("testToken")
                .setTokenId(TokenID.newBuilder()
                        .setTokenNum(newToken.get().value().longValue())
                        .build())
                .setSymbol("test")
                .setTotalSupply(100L)
                .setDecimals(4)
                .setTokenType(FUNGIBLE_COMMON)
                .setSupplyTypeValue(INFINITE_VALUE)
                .setExpiry(Timestamp.newBuilder().setSeconds(-9223372036854775808L))
                .setAutoRenewPeriod(Duration.newBuilder().setSeconds(THREE_MONTHS_IN_SECONDS))
                .setTreasury(spec.registry().getAccountID(contractTarget.name()))
                .setMetadata(ByteString.copyFrom("testmeta".getBytes(StandardCharsets.UTF_8)));
    }

    private TokenInfo.Builder nftInfo(
            final HapiSpec spec, final AtomicReference<ByteString> ledgerId, final AtomicReference<Address> newToken) {
        return TokenInfo.newBuilder()
                .setLedgerId(ledgerId.get())
                .setName("nft")
                .setTokenId(TokenID.newBuilder()
                        .setTokenNum(newToken.get().value().longValue())
                        .build())
                .setSymbol("nft")
                .setTokenType(NON_FUNGIBLE_UNIQUE)
                .setExpiry(Timestamp.newBuilder().setSeconds(-9223372036854775808L))
                .setSupplyKey(metaKey(spec))
                .setAutoRenewPeriod(Duration.newBuilder().setSeconds(THREE_MONTHS_IN_SECONDS))
                .setTreasury(spec.registry().getAccountID(contractTarget.name()))
                .setMetadata(ByteString.copyFrom("testmeta".getBytes(StandardCharsets.UTF_8)));
    }

    private com.hederahashgraph.api.proto.java.Key metaKey(final HapiSpec spec) {
        final var contractID = spec.registry().getContractId(contractTarget.name());
        return com.hederahashgraph.api.proto.java.Key.newBuilder()
                .setContractID(contractID)
                .build();
    }

    private CustomFee customFee(final HapiSpec spec) {
        final var fixedFee = FixedFee.newBuilder().setAmount(10);
        final var accountID = spec.registry().getAccountID(contractTarget.name());
        return CustomFee.newBuilder()
                .setFixedFee(fixedFee)
                .setFeeCollectorAccountId(accountID)
                .build();
    }
}
