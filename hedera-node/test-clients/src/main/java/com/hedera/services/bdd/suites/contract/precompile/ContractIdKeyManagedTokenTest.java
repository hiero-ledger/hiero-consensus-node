// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicitContractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.PAY_RECEIVABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Unfrozen;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractIdKeyManagedTokenTest {
    private static final String MANAGEMENT_CONTRACT = "DoTokenManagement";
    private static final String WIPE_CONTRACT = "WipeTokenAccount";
    private static final String MINT_CONTRACT = "MintToken";
    private static final String UPDATE_CONTRACT = "TokenInfoSingularUpdate";
    private static final String TOKEN_CREATE_CONTRACT = "TokenCreateContract";

    private static final String JUST_FREEZE_ACCOUNT_ABI = getABIFor(FUNCTION, "justFreezeAccount", MANAGEMENT_CONTRACT);
    private static final String WIPE_FUNGIBLE_TOKEN_ABI = getABIFor(FUNCTION, "wipeFungibleToken", WIPE_CONTRACT);
    private static final String MINT_TOKEN_ABI = getABIFor(FUNCTION, "mintToken", MINT_CONTRACT);
    private static final String UPDATE_KEY_ED_ABI = getABIFor(FUNCTION, "updateTokenKeyEd", UPDATE_CONTRACT);
    private static final String UPDATE_TREASURY_ABI = getABIFor(FUNCTION, "updateTokenTreasury", UPDATE_CONTRACT);

    // KeyHelper.KeyType: ADMIN=0, KYC=1, FREEZE=2, WIPE=3, SUPPLY=4, FEE=5, PAUSE=6
    private static final int SUPPLY_KEY_TYPE = 4;

    private static final long INITIAL_SUPPLY = 1_000_000L;
    private static final long HOLDER_BALANCE = 10L;

    @HapiTest
    final Stream<DynamicTest> contractIdKeyOnFreezeRole() {
        final AtomicReference<Address> tokenAddr = new AtomicReference<>();
        final AtomicReference<Address> holderAddr = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(MANAGEMENT_CONTRACT, PAY_RECEIVABLE_CONTRACT),
                cryptoCreate("operator").balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate("treasury").balance(ONE_HBAR),
                cryptoCreate("holder").balance(ONE_HBAR).exposingEvmAddressTo(holderAddr::set),
                // The contract referenced by the token's role key
                contractCreate("keyContract").bytecode(PAY_RECEIVABLE_CONTRACT).gas(4_000_000L),
                doingContextual(spec -> spec.registry()
                        .saveKey(
                                "referencedKey",
                                Key.newBuilder()
                                        .setContractID(spec.registry().getContractId("keyContract"))
                                        .build())),
                tokenCreate("token")
                        .exposingAddressTo(tokenAddr::set)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(INITIAL_SUPPLY)
                        .treasury("treasury")
                        .freezeDefault(false)
                        .freezeKey("referencedKey")
                        // HapiTokenCreate.defaultSigners() includes the freeze key; a registry-injected
                        // contract-ID key has no KeyFactory control, so the defaults must be replaced
                        .signedBy(DEFAULT_PAYER, "treasury"),
                tokenAssociate("holder", "token"),
                getAccountInfo("holder").hasToken(relationshipWith("token").freeze(Unfrozen)),

                // Control: identical contract with an ordinary admin key -> rejected
                newKeyNamed("ordinaryAdmin"),
                contractCreate("control")
                        .bytecode(MANAGEMENT_CONTRACT)
                        .adminKey("ordinaryAdmin")
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator", "ordinaryAdmin"),
                sourcing(() -> explicitContractCall(
                                "control", JUST_FREEZE_ACCOUNT_ABI, tokenAddr.get(), holderAddr.get())
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("controlFreeze")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // FreezeUnfreezeTranslator passes NOOP_CUSTOMIZER, so INVALID_SIGNATURE is not remapped
                childRecordsCheck(
                        "controlFreeze", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_SIGNATURE)),
                getAccountInfo("holder").hasToken(relationshipWith("token").freeze(Unfrozen)),

                // The caller is created carrying X's copied key (a legitimate, immutable arrangement) ...
                contractCreate("callerContract")
                        .bytecode(MANAGEMENT_CONTRACT)
                        .adminKey("referencedKey")
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .hasKnownStatus(SUCCESS),
                getContractInfo("callerContract").has(contractWith().adminKey("referencedKey")),
                // ... but carrying X's key does not let it exercise X's freeze authority
                sourcing(() -> explicitContractCall(
                                "callerContract", JUST_FREEZE_ACCOUNT_ABI, tokenAddr.get(), holderAddr.get())
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("callerFreeze")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "callerFreeze", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_SIGNATURE)),
                getAccountInfo("holder").hasToken(relationshipWith("token").freeze(Unfrozen)));
    }

    @HapiTest
    final Stream<DynamicTest> contractIdKeyOnWipeRole() {
        final AtomicReference<Address> tokenAddr = new AtomicReference<>();
        final AtomicReference<Address> holderAddr = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(WIPE_CONTRACT, PAY_RECEIVABLE_CONTRACT),
                cryptoCreate("operator").balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate("treasury").balance(ONE_HBAR),
                cryptoCreate("holder")
                        .balance(ONE_HBAR)
                        .maxAutomaticTokenAssociations(1)
                        .exposingEvmAddressTo(holderAddr::set),
                contractCreate("keyContract").bytecode(PAY_RECEIVABLE_CONTRACT).gas(4_000_000L),
                doingContextual(spec -> spec.registry()
                        .saveKey(
                                "referencedKey",
                                Key.newBuilder()
                                        .setContractID(spec.registry().getContractId("keyContract"))
                                        .build())),
                // wipeKey is NOT in HapiTokenCreate.defaultSigners(), so no signedBy override is needed
                tokenCreate("token")
                        .exposingAddressTo(tokenAddr::set)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(INITIAL_SUPPLY)
                        .treasury("treasury")
                        .wipeKey("referencedKey"),
                getTokenInfo("token").searchKeysGlobally().hasWipeKey("referencedKey"),
                cryptoTransfer(moving(HOLDER_BALANCE, "token").between("treasury", "holder")),
                getAccountBalance("holder").hasTokenBalance("token", HOLDER_BALANCE),

                // Control
                newKeyNamed("ordinaryAdmin"),
                contractCreate("control")
                        .bytecode(WIPE_CONTRACT)
                        .adminKey("ordinaryAdmin")
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator", "ordinaryAdmin"),
                sourcing(() -> explicitContractCall(
                                "control", WIPE_FUNGIBLE_TOKEN_ABI, tokenAddr.get(), holderAddr.get(), 1L)
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("controlWipe")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // WipeTranslator passes NOOP_CUSTOMIZER, so INVALID_SIGNATURE is not remapped
                childRecordsCheck(
                        "controlWipe", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_SIGNATURE)),
                getAccountBalance("holder").hasTokenBalance("token", HOLDER_BALANCE),
                getTokenInfo("token").hasTotalSupply(INITIAL_SUPPLY),

                // The caller is created carrying X's copied key ...
                contractCreate("callerContract")
                        .bytecode(WIPE_CONTRACT)
                        .adminKey("referencedKey")
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .hasKnownStatus(SUCCESS),
                getContractInfo("callerContract").has(contractWith().adminKey("referencedKey")),
                // ... but cannot exercise X's wipe authority
                sourcing(() -> explicitContractCall(
                                "callerContract", WIPE_FUNGIBLE_TOKEN_ABI, tokenAddr.get(), holderAddr.get(), 1L)
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("callerWipe")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "callerWipe", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_SIGNATURE)),
                // nothing was wiped: holder balance and total supply are unchanged
                getAccountBalance("holder").hasTokenBalance("token", HOLDER_BALANCE),
                getTokenInfo("token").hasTotalSupply(INITIAL_SUPPLY));
    }

    @HapiTest
    final Stream<DynamicTest> contractIdKeyOnSupplyRole() {
        final AtomicReference<Address> tokenAddr = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(MINT_CONTRACT, PAY_RECEIVABLE_CONTRACT),
                cryptoCreate("operator").balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate("treasury").balance(ONE_HBAR),
                contractCreate("keyContract").bytecode(PAY_RECEIVABLE_CONTRACT).gas(4_000_000L),
                doingContextual(spec -> spec.registry()
                        .saveKey(
                                "referencedKey",
                                Key.newBuilder()
                                        .setContractID(spec.registry().getContractId("keyContract"))
                                        .build())),
                tokenCreate("token")
                        .exposingAddressTo(tokenAddr::set)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(INITIAL_SUPPLY)
                        .treasury("treasury")
                        .supplyKey("referencedKey"),
                getTokenInfo("token").searchKeysGlobally().hasSupplyKey("referencedKey"),

                // Control
                newKeyNamed("ordinaryAdmin"),
                contractCreate("control")
                        .bytecode(MINT_CONTRACT)
                        .adminKey("ordinaryAdmin")
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator", "ordinaryAdmin"),
                sourcing(() -> explicitContractCall(
                                "control", MINT_TOKEN_ABI, BigInteger.valueOf(1_000_000_000L), tokenAddr.get())
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("controlMint")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // MintTranslator uses the OutputFn overload -> STANDARD_FAILURE_CUSTOMIZER, which
                // remaps INVALID_SIGNATURE to INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE
                childRecordsCheck(
                        "controlMint",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                getTokenInfo("token").hasTotalSupply(INITIAL_SUPPLY),

                // The caller is created carrying X's copied key ...
                contractCreate("callerContract")
                        .bytecode(MINT_CONTRACT)
                        .adminKey("referencedKey")
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .hasKnownStatus(SUCCESS),
                getContractInfo("callerContract").has(contractWith().adminKey("referencedKey")),
                // ... but cannot exercise X's supply authority
                sourcing(() -> explicitContractCall(
                                "callerContract", MINT_TOKEN_ABI, BigInteger.valueOf(1_000_000_000L), tokenAddr.get())
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("callerMint")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "callerMint",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                getTokenInfo("token").hasTotalSupply(INITIAL_SUPPLY));
    }

    @HapiTest
    final Stream<DynamicTest> adminKeylessTokenRejectsKeyUpdate() {
        final AtomicReference<Address> tokenAddr = new AtomicReference<>();
        final AtomicReference<byte[]> newSupplyKeyBytes = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(UPDATE_CONTRACT, PAY_RECEIVABLE_CONTRACT),
                cryptoCreate("operator").balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate("treasury").balance(ONE_HBAR),
                contractCreate("keyContract").bytecode(PAY_RECEIVABLE_CONTRACT).gas(4_000_000L),
                newKeyNamed("operatorOwnedKey").shape(ED25519_ON),
                doingContextual(spec -> {
                    spec.registry()
                            .saveKey(
                                    "referencedKey",
                                    Key.newBuilder()
                                            .setContractID(spec.registry().getContractId("keyContract"))
                                            .build());
                    newSupplyKeyBytes.set(spec.registry()
                            .getKey("operatorOwnedKey")
                            .getEd25519()
                            .toByteArray());
                }),
                // no admin key: the token is immutable
                tokenCreate("token")
                        .exposingAddressTo(tokenAddr::set)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(INITIAL_SUPPLY)
                        .treasury("treasury")
                        .supplyKey("referencedKey"),
                getTokenInfo("token").searchKeysGlobally().hasSupplyKey("referencedKey"),
                contractCreate("callerContract")
                        .bytecode(UPDATE_CONTRACT)
                        .adminKey("referencedKey")
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .hasKnownStatus(SUCCESS),
                sourcing(() -> explicitContractCall(
                                "callerContract",
                                UPDATE_KEY_ED_ABI,
                                tokenAddr.get(),
                                newSupplyKeyBytes.get(),
                                SUPPLY_KEY_TYPE)
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("immutableKeyUpdate")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // UpdateCommonDecoder.FAILURE_CUSTOMIZER rewrites INVALID_SIGNATURE to TOKEN_IS_IMMUTABLE
                // when the token has no admin key
                childRecordsCheck(
                        "immutableKeyUpdate",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(TOKEN_IS_IMMUTABLE)),
                getTokenInfo("token").searchKeysGlobally().hasSupplyKey("referencedKey"));
    }

    @HapiTest
    final Stream<DynamicTest> contractIdKeyDoesNotAllowRoleKeyReplacement() {
        final AtomicLong createdTokenNum = new AtomicLong();
        final AtomicReference<Address> tokenAddr = new AtomicReference<>();
        final AtomicReference<byte[]> newSupplyKeyBytes = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("funder").balance(ONE_MILLION_HBARS),
                cryptoCreate("operator").balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT, UPDATE_CONTRACT),
                // X creates a token whose admin and supply roles are both held by X's contract-ID key
                contractCreate(TOKEN_CREATE_CONTRACT)
                        .autoRenewAccountId("funder")
                        .gas(4_000_000L),
                newKeyNamed("operatorOwnedKey").shape(ED25519_ON),
                newKeyNamed("funderThreshold")
                        .shape(KeyShape.threshOf(1, KeyShape.ED25519, KeyShape.CONTRACT)
                                .signedWith(sigs(ON, TOKEN_CREATE_CONTRACT))),
                cryptoUpdate("funder").key("funderThreshold"),
                doingContextual(spec -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_CREATE_CONTRACT,
                                        "createNonFungibleTokenThenQuery",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(TOKEN_CREATE_CONTRACT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID("funder"))),
                                        8_000_000L)
                                .gas(1_000_000L)
                                .sending(30 * ONE_HBAR)
                                .payingWith("funder")
                                .signedByPayerAnd("funderThreshold")
                                .refusingEthConversion()
                                .exposingResultTo(result -> {
                                    final var res = (Address) result[0];
                                    tokenAddr.set(res);
                                    createdTokenNum.set(numberOfLongZero(HexFormat.of()
                                            .parseHex(res.toString().substring(2))));
                                }))),
                doingContextual(spec -> {
                    spec.registry()
                            .saveKey(
                                    "referencedKey",
                                    Key.newBuilder()
                                            .setContractID(spec.registry().getContractId(TOKEN_CREATE_CONTRACT))
                                            .build());
                    newSupplyKeyBytes.set(spec.registry()
                            .getKey("operatorOwnedKey")
                            .getEd25519()
                            .toByteArray());
                }),
                sourcing(() -> getTokenInfo(String.valueOf(createdTokenNum.get()))
                        .searchKeysGlobally()
                        .hasAdminKey("referencedKey")
                        .hasSupplyKey("referencedKey")),

                // Caller contract carrying the referenced contract-ID key attempts the role-key update
                contractCreate("callerContract")
                        .bytecode(UPDATE_CONTRACT)
                        .adminKey("referencedKey")
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .hasKnownStatus(SUCCESS),
                sourcing(() -> explicitContractCall(
                                "callerContract",
                                UPDATE_KEY_ED_ABI,
                                tokenAddr.get(),
                                newSupplyKeyBytes.get(),
                                SUPPLY_KEY_TYPE)
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("callerKeyUpdate")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // the role-key update requires a threshold key that the payer shortcut does not elide,
                // so verification against the active contract fails and the call reverts
                childRecordsCheck(
                        "callerKeyUpdate",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_SIGNATURE)),
                // supply role is unchanged
                sourcing(() -> getTokenInfo(String.valueOf(createdTokenNum.get()))
                        .searchKeysGlobally()
                        .hasSupplyKey("referencedKey")));
    }

    @HapiTest
    final Stream<DynamicTest> contractIdKeyCannotChangeTreasury() {
        final AtomicLong createdTokenNum = new AtomicLong();
        final AtomicReference<Address> tokenAddr = new AtomicReference<>();
        final AtomicReference<Address> callerAddr = new AtomicReference<>();
        final AtomicReference<Address> controlAddr = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("funder").balance(ONE_MILLION_HBARS),
                cryptoCreate("operator").balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(TOKEN_CREATE_CONTRACT, UPDATE_CONTRACT),
                // X is a normal token-managing contract
                contractCreate(TOKEN_CREATE_CONTRACT)
                        .autoRenewAccountId("funder")
                        .gas(4_000_000L),
                newKeyNamed("funderThreshold")
                        .shape(KeyShape.threshOf(1, KeyShape.ED25519, KeyShape.CONTRACT)
                                .signedWith(sigs(ON, TOKEN_CREATE_CONTRACT))),
                cryptoUpdate("funder").key("funderThreshold"),
                doingContextual(spec -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_CREATE_CONTRACT,
                                        "createNonFungibleTokenThenQuery",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(TOKEN_CREATE_CONTRACT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID("funder"))),
                                        8_000_000L)
                                .gas(1_000_000L)
                                .sending(30 * ONE_HBAR)
                                .payingWith("funder")
                                .signedByPayerAnd("funderThreshold")
                                .refusingEthConversion()
                                .exposingResultTo(result -> {
                                    final var res = (Address) result[0];
                                    tokenAddr.set(res);
                                    createdTokenNum.set(numberOfLongZero(HexFormat.of()
                                            .parseHex(res.toString().substring(2))));
                                }))),
                doingContextual(spec -> spec.registry()
                        .saveKey(
                                "referencedKey",
                                Key.newBuilder()
                                        .setContractID(spec.registry().getContractId(TOKEN_CREATE_CONTRACT))
                                        .build())),
                // Baseline: the token is admin'd by X and treasured by X
                sourcing(() -> getTokenInfo(String.valueOf(createdTokenNum.get()))
                        .searchKeysGlobally()
                        .hasAdminKey("referencedKey")
                        .hasTreasury(TOKEN_CREATE_CONTRACT)),

                // Control
                newKeyNamed("ordinaryAdmin"),
                contractCreate("control")
                        .bytecode(UPDATE_CONTRACT)
                        .adminKey("ordinaryAdmin")
                        .maxAutomaticTokenAssociations(1)
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator", "ordinaryAdmin")
                        .exposingAddressTo(controlAddr::set),
                sourcing(() -> explicitContractCall("control", UPDATE_TREASURY_ABI, tokenAddr.get(), controlAddr.get())
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("controlTreasury")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "controlTreasury",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_SIGNATURE)),
                sourcing(() -> getTokenInfo(String.valueOf(createdTokenNum.get()))
                        .searchKeysGlobally()
                        .hasTreasury(TOKEN_CREATE_CONTRACT)),

                // The caller is created carrying X's copied key ...
                contractCreate("callerContract")
                        .bytecode(UPDATE_CONTRACT)
                        .adminKey("referencedKey")
                        .maxAutomaticTokenAssociations(1)
                        .gas(4_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .exposingAddressTo(callerAddr::set)
                        .hasKnownStatus(SUCCESS),
                getContractInfo("callerContract").has(contractWith().adminKey("referencedKey")),
                // ... but cannot seize X's treasury role
                sourcing(() -> explicitContractCall(
                                "callerContract", UPDATE_TREASURY_ABI, tokenAddr.get(), callerAddr.get())
                        .gas(2_000_000L)
                        .payingWith("operator")
                        .signedBy("operator")
                        .via("callerTreasury")
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                childRecordsCheck(
                        "callerTreasury", CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_SIGNATURE)),
                // treasury role is unchanged
                sourcing(() -> getTokenInfo(String.valueOf(createdTokenNum.get()))
                        .searchKeysGlobally()
                        .hasTreasury(TOKEN_CREATE_CONTRACT)));
    }

    @HapiTest
    final Stream<DynamicTest> genuineControllingContractCanWipeItsOwnToken() {
        final AtomicReference<Address> tokenAddr = new AtomicReference<>();
        final AtomicReference<Address> holderAddr = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(WIPE_CONTRACT),
                cryptoCreate("treasury").balance(ONE_HBAR),
                cryptoCreate("holder")
                        .balance(ONE_HBAR)
                        .maxAutomaticTokenAssociations(1)
                        .exposingEvmAddressTo(holderAddr::set),
                // admin key omitted -> the contract's own account key is Key{contractID:self}
                contractCreate("manager").bytecode(WIPE_CONTRACT).omitAdminKey().gas(4_000_000L),
                getContractInfo("manager").has(contractWith().immutableContractKey("manager")),
                doingContextual(spec -> spec.registry()
                        .saveKey(
                                "managerKey",
                                Key.newBuilder()
                                        .setContractID(spec.registry().getContractId("manager"))
                                        .build())),
                // the token is genuinely managed by this contract: its wipe key is the contract's own key
                tokenCreate("token")
                        .exposingAddressTo(tokenAddr::set)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(INITIAL_SUPPLY)
                        .treasury("treasury")
                        .wipeKey("managerKey"),
                getTokenInfo("token").searchKeysGlobally().hasWipeKey("managerKey"),
                cryptoTransfer(moving(HOLDER_BALANCE, "token").between("treasury", "holder")),
                // the contract exercising its OWN wipe authority remains valid after the fix
                sourcing(() -> explicitContractCall(
                                "manager", WIPE_FUNGIBLE_TOKEN_ABI, tokenAddr.get(), holderAddr.get(), 1L)
                        .gas(2_000_000L)
                        .payingWith(DEFAULT_PAYER)
                        .via("ownWipe")
                        .hasKnownStatus(SUCCESS)),
                childRecordsCheck("ownWipe", SUCCESS, recordWith().status(SUCCESS)),
                getAccountBalance("holder").hasTokenBalance("token", HOLDER_BALANCE - 1),
                getTokenInfo("token").hasTotalSupply(INITIAL_SUPPLY - 1));
    }

    /** Long-zero address -> entity number (mirrors the private helper in CreatePrecompileSuite). */
    private static long numberOfLongZero(final byte[] explicit) {
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
