// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.fixedHbarFeeInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.transferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.captureOneChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.Utils.contractIdFromHexedMirrorAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.getInitcodeOf;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** End-to-end checks that native key requirements do not inherit a contract account's authority. */
@Tag(SMART_CONTRACT)
public class IdentityScopedSignatureRequirementsTest {
    @LeakyHapiTest(overrides = {"hooks.hooksEnabled"})
    final Stream<DynamicTest> hookStorageRequiresOwnerOrIndependentAdmin() {
        return hapiTest(
                overriding("hooks.hooksEnabled", "true"),
                newKeyNamed("hookAdmin"),
                cryptoCreate("attacker").balance(ONE_HUNDRED_HBARS),
                uploadInitCode("PayableConstructor"),
                contractCreate("PayableConstructor"),
                cryptoCreate("owner").withHook(spec -> accountAllowanceHook(1L, "PayableConstructor")
                        .apply(spec)
                        .copyBuilder()
                        .adminKey(toPbj(spec.registry().getKey("hookAdmin")))
                        .build()),
                accountEvmHookStore("owner", 1L)
                        .putSlot(Bytes.fromHex("01"), Bytes.fromHex("02"))
                        .payingWith("attacker")
                        .signedBy("attacker")
                        .hasKnownStatus(INVALID_SIGNATURE),
                accountEvmHookStore("owner", 1L)
                        .putSlot(Bytes.fromHex("01"), Bytes.fromHex("02"))
                        .payingWith("attacker")
                        .signedBy("attacker", "owner"),
                accountEvmHookStore("owner", 1L)
                        .putSlot(Bytes.fromHex("01"), Bytes.fromHex("03"))
                        .payingWith("attacker")
                        .signedBy("attacker", "hookAdmin"));
    }

    @HapiTest
    final Stream<DynamicTest> sharedAdminKeyDoesNotAuthorizeHtsDebit() {
        final var contract = "AtomicCryptoTransfer";
        return hapiTest(
                newKeyNamed("shared"),
                cryptoCreate("owner").key("shared").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("attacker").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("receiver").balance(0L),
                uploadInitCode(contract),
                contractCreate(contract).adminKey("shared").gas(2_000_000),
                withOpContext((spec, log) -> {
                    final var transfers = transferList()
                            .withAccountAmounts(
                                    accountAmount(spec.registry().getAccountID("owner"), -10L, false),
                                    accountAmount(spec.registry().getAccountID("receiver"), 10L, false))
                            .build();
                    allRunFor(
                            spec,
                            contractCall(contract, "transferMultipleTokens", transfers, new Tuple[0])
                                    .payingWith("attacker")
                                    .signedBy("attacker")
                                    .refusingEthConversion()
                                    .gas(1_000_000)
                                    .via("unsignedDebit")
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            childRecordsCheck(
                                    "unsignedDebit",
                                    CONTRACT_REVERT_EXECUTED,
                                    recordWith().status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)),
                            getAccountBalance("owner").hasTinyBars(ONE_HUNDRED_HBARS),
                            getAccountBalance("receiver").hasTinyBars(0),
                            // Explicit allowance authorizes the same debit without relying on key equality.
                            cryptoApproveAllowance()
                                    .addCryptoAllowance("owner", contract, 10L)
                                    .signedBy(GENESIS, "shared"),
                            contractCall(contract, "transferMultipleTokens", transfers, new Tuple[0])
                                    .payingWith("attacker")
                                    .signedBy("attacker")
                                    .refusingEthConversion()
                                    .gas(1_000_000)
                                    .via("approvedDebit"),
                            childRecordsCheck(
                                    "approvedDebit", SUCCESS, recordWith().status(SUCCESS)),
                            getAccountBalance("receiver").hasTinyBars(10));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> sharedAdminKeyDoesNotAuthorizeTokenSupply() {
        final var contract = "ServiceContract";
        return hapiTest(
                newKeyNamed("shared"),
                cryptoCreate("attacker").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("treasury"),
                tokenCreate("token")
                        .treasury("treasury")
                        .initialSupply(10)
                        .adminKey("shared")
                        .supplyKey("shared"),
                uploadInitCode(contract),
                contractCreate(contract).adminKey("shared"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCall(
                                        contract,
                                        "tokenMint",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        BigInteger.ONE)
                                .payingWith("attacker")
                                .signedBy("attacker")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("unsignedMint")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        childRecordsCheck(
                                "unsignedMint",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                        getTokenInfo("token").hasTotalSupply(10),
                        newKeyNamed("contractSupplyKey").shape(CONTRACT.signedWith(contract)),
                        tokenUpdate("token").supplyKey("contractSupplyKey").signedBy(GENESIS, "shared"),
                        contractCall(
                                        contract,
                                        "tokenMint",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        BigInteger.ONE)
                                .payingWith("attacker")
                                .signedBy("attacker")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("authorizedMint"),
                        childRecordsCheck(
                                "authorizedMint", SUCCESS, recordWith().status(SUCCESS)),
                        getTokenInfo("token").hasTotalSupply(11))));
    }

    @HapiTest
    final Stream<DynamicTest> sharedAdminKeyDoesNotAuthorizeTokenWipe() {
        final var contract = "WipeTokenAccount";
        return hapiTest(
                newKeyNamed("shared"),
                cryptoCreate("caller").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("treasury"),
                cryptoCreate("holder").maxAutomaticTokenAssociations(10),
                tokenCreate("token")
                        .treasury("treasury")
                        .initialSupply(10)
                        .adminKey("shared")
                        .wipeKey("shared"),
                cryptoTransfer(moving(5, "token").between("treasury", "holder")),
                uploadInitCode(contract),
                contractCreate(contract).adminKey("shared"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCall(
                                        contract,
                                        "wipeFungibleToken",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        idAsHeadlongAddress(spec.registry().getAccountID("holder")),
                                        5L)
                                .payingWith("caller")
                                .signedBy("caller")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("unsignedWipe")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        childRecordsCheck(
                                "unsignedWipe",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_SIGNATURE)),
                        getAccountBalance("holder").hasTokenBalance("token", 5),
                        // A wipe key naming the contract authorizes the same wipe by execution alone.
                        newKeyNamed("contractWipeKey").shape(CONTRACT.signedWith(contract)),
                        tokenUpdate("token").wipeKey("contractWipeKey").signedBy(GENESIS, "shared"),
                        contractCall(
                                        contract,
                                        "wipeFungibleToken",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        idAsHeadlongAddress(spec.registry().getAccountID("holder")),
                                        5L)
                                .payingWith("caller")
                                .signedBy("caller")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("authorizedWipe"),
                        childRecordsCheck(
                                "authorizedWipe", SUCCESS, recordWith().status(SUCCESS)),
                        getAccountBalance("holder").hasTokenBalance("token", 0))));
    }

    @HapiTest
    final Stream<DynamicTest> sharedAdminKeyDoesNotAuthorizeTokenReject() {
        final var contract = "TokenReject";
        return hapiTest(
                newKeyNamed("shared"),
                cryptoCreate("caller").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("treasury"),
                // The holder's own account key equals the acting contract's key; its tokens must not be
                // rejectable by anyone but the holder, despite the shared key value.
                cryptoCreate("holder").key("shared").maxAutomaticTokenAssociations(10),
                tokenCreate("token").treasury("treasury").initialSupply(10).adminKey("shared"),
                cryptoTransfer(moving(5, "token").between("treasury", "holder")),
                uploadInitCode(contract),
                contractCreate(contract).adminKey("shared"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCall(
                                        contract,
                                        "rejectTokens",
                                        idAsHeadlongAddress(spec.registry().getAccountID("holder")),
                                        new Address[] {
                                            idAsHeadlongAddress(spec.registry().getTokenID("token"))
                                        },
                                        new Address[] {})
                                .payingWith("caller")
                                .signedBy("caller")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("unsignedReject")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        childRecordsCheck(
                                "unsignedReject",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                        // The holder keeps every token; nothing was rejected back to treasury.
                        getAccountBalance("holder").hasTokenBalance("token", 5))));
    }

    @HapiTest
    final Stream<DynamicTest> sharedAdminKeyDoesNotAuthorizeAirdropDebit() {
        final var contract = "Airdrop";
        return hapiTest(
                newKeyNamed("shared"),
                cryptoCreate("joe").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("treasury"),
                // Jane's account key equals the calling contract's key; airdrops of her tokens still
                // require her own authorization, despite the shared key value.
                cryptoCreate("jane").key("shared").maxAutomaticTokenAssociations(10),
                tokenCreate("token").treasury("treasury").initialSupply(10),
                tokenAssociate("joe", "token"),
                cryptoTransfer(moving(5, "token").between("treasury", "jane")),
                uploadInitCode(contract),
                contractCreate(contract).adminKey("shared"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCall(
                                        contract,
                                        "tokenAirdrop",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        idAsHeadlongAddress(spec.registry().getAccountID("jane")),
                                        idAsHeadlongAddress(spec.registry().getAccountID("joe")),
                                        5L)
                                .payingWith("joe")
                                .signedBy("joe")
                                .refusingEthConversion()
                                .gas(1_500_000)
                                .via("unsignedAirdrop")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        childRecordsCheck(
                                "unsignedAirdrop",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                        getAccountBalance("jane").hasTokenBalance("token", 5),
                        getAccountBalance("joe").hasTokenBalance("token", 0))),
                // An owner whose key names the contract authorizes the same airdrop by execution alone.
                newKeyNamed("contractOwnerKey").shape(CONTRACT.signedWith(contract)),
                cryptoCreate("delegatedOwner").key("contractOwnerKey").maxAutomaticTokenAssociations(10),
                cryptoTransfer(moving(5, "token").between("treasury", "delegatedOwner")),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCall(
                                        contract,
                                        "tokenAirdrop",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        idAsHeadlongAddress(spec.registry().getAccountID("delegatedOwner")),
                                        idAsHeadlongAddress(spec.registry().getAccountID("joe")),
                                        5L)
                                .payingWith("joe")
                                .signedBy("joe")
                                .refusingEthConversion()
                                .gas(1_500_000)
                                .via("authorizedAirdrop"),
                        childRecordsCheck(
                                "authorizedAirdrop", SUCCESS, recordWith().status(SUCCESS)),
                        getAccountBalance("delegatedOwner").hasTokenBalance("token", 0),
                        getAccountBalance("joe").hasTokenBalance("token", 5))));
    }

    /**
     * The token's admin key is the same key as the contract's admin key. Joe calls the contract to make
     * the contract itself the token's treasury, which would move Jane's whole treasury balance to it.
     * The contract authorizes its own account as the new treasury just by executing, but a key that merely
     * equals the contract's key must not count as the token admin key having signed, so the update fails.
     * Once the admin key explicitly names the contract, the same call succeeds.
     */
    @HapiTest
    final Stream<DynamicTest> sharedAdminKeyDoesNotAuthorizeTreasuryChange() {
        final var contract = "UpdateTokenInfoContract";
        return hapiTest(
                newKeyNamed("shared"),
                cryptoCreate("joe").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("jane"),
                tokenCreate("token").treasury("jane").initialSupply(10).adminKey("shared"),
                uploadInitCode(contract),
                contractCreate(contract).adminKey("shared").gas(4_000_000),
                // Associate the contract up front so only the signing requirements can fail the update.
                tokenAssociate(contract, "token"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        // Making the contract the new treasury also moves Jane's treasury balance to it.
                        contractCall(
                                        contract,
                                        "updateTokenTreasury",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(contract))))
                                .payingWith("joe")
                                .signedBy("joe")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("unsignedTreasuryChange")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        childRecordsCheck(
                                "unsignedTreasuryChange",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_SIGNATURE)),
                        getTokenInfo("token").hasTreasury("jane"),
                        getAccountBalance("jane").hasTokenBalance("token", 10),
                        getAccountBalance(contract).hasTokenBalance("token", 0),
                        // An admin key with a leg naming the contract authorizes the same change by execution.
                        newKeyNamed("contractAdminKey")
                                .shape(threshOf(1, ED25519, CONTRACT).signedWith(sigs(ON, contract))),
                        tokenUpdate("token")
                                .adminKey("contractAdminKey")
                                .signedBy(GENESIS, "shared", "contractAdminKey"),
                        contractCall(
                                        contract,
                                        "updateTokenTreasury",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getContractId(contract))))
                                .payingWith("joe")
                                .signedBy("joe")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("authorizedTreasuryChange"),
                        childRecordsCheck(
                                "authorizedTreasuryChange",
                                SUCCESS,
                                recordWith().status(SUCCESS)),
                        getTokenInfo("token").hasTreasury(contract),
                        getAccountBalance(contract).hasTokenBalance("token", 10))));
    }

    @HapiTest
    final Stream<DynamicTest> sharedFeeScheduleKeyDoesNotAuthorizeCustomFeeUpdate() {
        final var contract = "UpdateTokenFeeSchedules";
        return hapiTest(
                newKeyNamed("shared"),
                cryptoCreate("joe").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("jane"),
                tokenCreate("token")
                        .treasury("jane")
                        .initialSupply(10)
                        .adminKey("shared")
                        .feeScheduleKey("shared"),
                uploadInitCode(contract),
                contractCreate(contract).adminKey("shared").gas(4_000_000),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        // A fixed hbar fee collected by Joe applies to every later transfer of the token.
                        contractCall(
                                        contract,
                                        "updateFungibleFixedHbarFee",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        1L,
                                        idAsHeadlongAddress(spec.registry().getAccountID("joe")))
                                .payingWith("joe")
                                .signedBy("joe")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("unsignedFeeUpdate")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        childRecordsCheck(
                                "unsignedFeeUpdate",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                        getTokenInfo("token").hasEmptyCustom(),
                        // A fee schedule key naming the contract authorizes the same update by execution alone.
                        newKeyNamed("contractFeeScheduleKey").shape(CONTRACT.signedWith(contract)),
                        tokenUpdate("token")
                                .feeScheduleKey("contractFeeScheduleKey")
                                .signedBy(GENESIS, "shared"),
                        contractCall(
                                        contract,
                                        "updateFungibleFixedHbarFee",
                                        idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                        1L,
                                        idAsHeadlongAddress(spec.registry().getAccountID("joe")))
                                .payingWith("joe")
                                .signedBy("joe")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("authorizedFeeUpdate"),
                        childRecordsCheck(
                                "authorizedFeeUpdate", SUCCESS, recordWith().status(SUCCESS)),
                        getTokenInfo("token").hasCustom(fixedHbarFeeInSchedule(1L, "joe")))));
    }

    @HapiTest
    final Stream<DynamicTest> delegatedFeeScheduleUpdatePreservesCollectorConsent() {
        final var caller = "MixedMintToken";
        final var implementation = "UpdateTokenFeeSchedules";
        return hapiTest(
                cryptoCreate("relayer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("collector").receiverSigRequired(true),
                uploadInitCode(caller, implementation),
                contractCreate(caller).omitAdminKey(),
                contractCreate(implementation).gas(4_000_000),
                newKeyNamed("feeKey").shape(CONTRACT.signedWith(caller)),
                tokenCreate("token").treasury("relayer").feeScheduleKey("feeKey"),
                withOpContext((spec, log) -> {
                    final var target =
                            asHeadlongAddress(asAddress(spec.registry().getContractId(implementation)));
                    final var update = Function.fromJson(
                                    getABIFor(FUNCTION, "updateFungibleFixedHbarFee", implementation))
                            .encodeCallWithArgs(
                                    idAsHeadlongAddress(spec.registry().getTokenID("token")),
                                    1L,
                                    idAsHeadlongAddress(spec.registry().getAccountID("collector")))
                            .array();
                    allRunFor(
                            spec,
                            contractCall(caller, "callCodeToContractWithoutAmount", target, update)
                                    .payingWith("relayer")
                                    .refusingEthConversion()
                                    .gas(1_000_000)
                                    .via("collectorSignatureRequired")
                                    .hasKnownStatus(INSUFFICIENT_GAS),
                            // The CALLCODE fixture runs out of gas constructing its outer revert.
                            // The HTS child carries the actual authorization result.
                            childRecordsCheck(
                                    "collectorSignatureRequired",
                                    INSUFFICIENT_GAS,
                                    recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                            getTokenInfo("token").hasEmptyCustom(),
                            cryptoUpdate("collector").receiverSigRequired(false),
                            contractCall(caller, "callCodeToContractWithoutAmount", target, update)
                                    .payingWith("relayer")
                                    .refusingEthConversion()
                                    .gas(1_000_000),
                            getTokenInfo("token").hasCustom(fixedHbarFeeInSchedule(1L, "collector")));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> sharedAdminKeyDoesNotAuthorizeScheduleDeletion() {
        final var contract = "HIP1215Contract";
        return hapiTest(
                newKeyNamed("shared"),
                cryptoCreate("owner").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("receiver"),
                cryptoCreate("attacker").balance(ONE_HUNDRED_HBARS),
                scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo("owner", "receiver", 1)))
                        .adminKey("shared")
                        .signedBy(GENESIS, "shared"),
                getScheduleInfo("schedule").isNotExecuted().isNotDeleted(),
                uploadInitCode(contract),
                contractCreate(contract).adminKey("shared").gas(4_000_000),
                withOpContext((spec, log) -> {
                    final var scheduleId = spec.registry().getScheduleId("schedule");
                    final var scheduleAddress = idAsHeadlongAddress(AccountID.newBuilder()
                            .setShardNum(scheduleId.getShardNum())
                            .setRealmNum(scheduleId.getRealmNum())
                            .setAccountNum(scheduleId.getScheduleNum())
                            .build());
                    allRunFor(
                            spec,
                            contractCall(contract, "deleteScheduleExample", scheduleAddress)
                                    .payingWith("attacker")
                                    .signedBy("attacker")
                                    .refusingEthConversion()
                                    .gas(1_000_000)
                                    .via("unsignedDelete")
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                            childRecordsCheck(
                                    "unsignedDelete",
                                    CONTRACT_REVERT_EXECUTED,
                                    recordWith().status(INVALID_SIGNATURE)),
                            getScheduleInfo("schedule").isNotDeleted(),
                            contractCall(contract, "deleteScheduleProxyExample", scheduleAddress)
                                    .payingWith("attacker")
                                    .signedBy("attacker", "shared")
                                    .alsoSigningWithFullPrefix("shared")
                                    .refusingEthConversion()
                                    .gas(1_000_000)
                                    .via("signedDelete"),
                            childRecordsCheck(
                                    "signedDelete", SUCCESS, recordWith().status(SUCCESS)),
                            getScheduleInfo("schedule").isDeleted());
                }));
    }

    @LeakyHapiTest(overrides = {"contracts.grandfatherTokenNum"})
    final Stream<DynamicTest> delegatedSupplyChangesRespectTokenCutoff() {
        return hapiTest(
                cryptoCreate("relayer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("treasury"),
                uploadInitCode("ServiceContract", "DelegateContract"),
                contractCreate("ServiceContract"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCreate(
                                        "DelegateContract",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getContractId("ServiceContract"))))
                                .omitAdminKey())),
                newKeyNamed("plainContractKey").shape(CONTRACT.signedWith("DelegateContract")),
                newKeyNamed("delegatableKey").shape(DELEGATE_CONTRACT.signedWith("DelegateContract")),
                tokenCreate("legacyToken")
                        .treasury("treasury")
                        .initialSupply(10)
                        .supplyKey("plainContractKey"),
                tokenCreate("plainToken").treasury("treasury").initialSupply(10).supplyKey("plainContractKey"),
                tokenCreate("delegatableToken")
                        .treasury("treasury")
                        .initialSupply(10)
                        .supplyKey("delegatableKey"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        overriding(
                                "contracts.grandfatherTokenNum",
                                Long.toString(
                                        spec.registry().getTokenID("plainToken").getTokenNum())),
                        contractCall(
                                        "DelegateContract",
                                        "mintDelegateCall",
                                        idAsHeadlongAddress(spec.registry().getTokenID("legacyToken")),
                                        BigInteger.ONE)
                                .payingWith("relayer")
                                .signedBy("relayer")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("legacyMint"),
                        childRecordsCheck("legacyMint", SUCCESS, recordWith().status(SUCCESS)),
                        getTokenInfo("legacyToken").hasTotalSupply(11),
                        contractCall(
                                        "DelegateContract",
                                        "burnDelegateCall",
                                        idAsHeadlongAddress(spec.registry().getTokenID("legacyToken")),
                                        BigInteger.ONE,
                                        new long[0])
                                .payingWith("relayer")
                                .signedBy("relayer")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("legacyBurn"),
                        childRecordsCheck("legacyBurn", SUCCESS, recordWith().status(SUCCESS)),
                        getTokenInfo("legacyToken").hasTotalSupply(10),
                        contractCall(
                                        "DelegateContract",
                                        "mintDelegateCall",
                                        idAsHeadlongAddress(spec.registry().getTokenID("plainToken")),
                                        BigInteger.ONE)
                                .payingWith("relayer")
                                .signedBy("relayer")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("plainDelegate")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        childRecordsCheck(
                                "plainDelegate",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                        getTokenInfo("plainToken").hasTotalSupply(10),
                        contractCall(
                                        "DelegateContract",
                                        "mintDelegateCall",
                                        idAsHeadlongAddress(spec.registry().getTokenID("delegatableToken")),
                                        BigInteger.ONE)
                                .payingWith("relayer")
                                .signedBy("relayer")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("permittedDelegate"),
                        childRecordsCheck(
                                "permittedDelegate", SUCCESS, recordWith().status(SUCCESS)),
                        getTokenInfo("delegatableToken").hasTotalSupply(11))));
    }

    @LeakyHapiTest(overrides = {"contracts.grandfatherTokenNum"})
    final Stream<DynamicTest> delegatedSupplyChangesRequireSelfManagedContract() {
        return hapiTest(
                cryptoCreate("relayer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("treasury"),
                uploadInitCode("ServiceContract", "DelegateContract"),
                contractCreate("ServiceContract"),
                // With an admin key, the contract's account key is not its own contract ID
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCreate(
                                "DelegateContract",
                                asHeadlongAddress(asAddress(spec.registry().getContractId("ServiceContract")))))),
                newKeyNamed("plainContractKey").shape(CONTRACT.signedWith("DelegateContract")),
                tokenCreate("legacyToken")
                        .treasury("treasury")
                        .initialSupply(10)
                        .supplyKey("plainContractKey"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        overriding(
                                "contracts.grandfatherTokenNum",
                                Long.toString(spec.registry()
                                                .getTokenID("legacyToken")
                                                .getTokenNum()
                                        + 1)),
                        contractCall(
                                        "DelegateContract",
                                        "mintDelegateCall",
                                        idAsHeadlongAddress(spec.registry().getTokenID("legacyToken")),
                                        BigInteger.ONE)
                                .payingWith("relayer")
                                .signedBy("relayer")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("adminKeyedMint")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        childRecordsCheck(
                                "adminKeyedMint",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                        getTokenInfo("legacyToken").hasTotalSupply(10))));
    }

    @LeakyHapiTest(overrides = {"contracts.grandfatherTokenNum"})
    final Stream<DynamicTest> delegatedSupplyChangesResolveCreate2Address() {
        final AtomicReference<String> mirrorAddr = new AtomicReference<>();
        final AtomicReference<String> create2Addr = new AtomicReference<>();
        return hapiTest(
                cryptoCreate("relayer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("treasury"),
                uploadInitCode("ServiceContract", "Create2Factory"),
                contractCreate("ServiceContract"),
                // A self-managed factory gives its CREATE2 children their own contract IDs as keys
                contractCreate("Create2Factory").omitAdminKey().refusingEthConversion(),
                withOpContext((spec, log) -> {
                    final var bin = HexFormat.of()
                            .parseHex(getInitcodeOf("DelegateContract", "")
                                    .toStringUtf8()
                                    .strip());
                    // Append the ServiceContract address as the ABI-encoded constructor argument
                    final var initcode = Arrays.copyOf(bin, bin.length + 32);
                    System.arraycopy(
                            asAddress(spec.registry().getContractId("ServiceContract")),
                            0,
                            initcode,
                            bin.length + 12,
                            20);
                    allRunFor(
                            spec,
                            contractCall("Create2Factory", "deploy", initcode, BigInteger.ONE)
                                    .refusingEthConversion()
                                    .gas(4_000_000)
                                    .via("create2Deploy"));
                }),
                captureOneChildCreate2MetaFor("Delegator", "create2Deploy", mirrorAddr, create2Addr),
                withOpContext((spec, log) -> spec.registry()
                        .saveKey(
                                "create2ContractKey",
                                Key.newBuilder()
                                        .setContractID(contractIdFromHexedMirrorAddress(spec, mirrorAddr.get()))
                                        .build())),
                tokenCreate("legacyToken")
                        .treasury("treasury")
                        .initialSupply(10)
                        .supplyKey("create2ContractKey"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        overriding(
                                "contracts.grandfatherTokenNum",
                                Long.toString(spec.registry()
                                                .getTokenID("legacyToken")
                                                .getTokenNum()
                                        + 1)),
                        // The delegator runs at its CREATE2 address, which must resolve to its numeric ID
                        contractCallWithFunctionAbi(
                                        create2Addr.get(),
                                        getABIFor(FUNCTION, "mintDelegateCall", "DelegateContract"),
                                        idAsHeadlongAddress(spec.registry().getTokenID("legacyToken")),
                                        BigInteger.ONE)
                                .payingWith("relayer")
                                .signedBy("relayer")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("create2Mint"),
                        childRecordsCheck("create2Mint", SUCCESS, recordWith().status(SUCCESS)),
                        getTokenInfo("legacyToken").hasTotalSupply(11))));
    }

    @LeakyHapiTest(overrides = {"contracts.grandfatherTokenNum"})
    final Stream<DynamicTest> delegatedCompoundRoleRequirementsKeepStandardRules() {
        final var caller = "MixedMintToken";
        final var implementation = "MakeCalls";
        return hapiTest(
                cryptoCreate("relayer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("holder").maxAutomaticTokenAssociations(1),
                uploadInitCode(caller, implementation),
                contractCreate(caller).omitAdminKey(),
                contractCreate(implementation),
                newKeyNamed("supplyKey"),
                newKeyNamed("selfMetadataKey").shape(CONTRACT.signedWith(caller)),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury("relayer")
                        .initialSupply(0)
                        .supplyKey("supplyKey")
                        .metadataKey("selfMetadataKey"),
                mintToken("nft", List.of(ByteString.copyFromUtf8("first"), ByteString.copyFromUtf8("second"))),
                cryptoTransfer(movingUnique("nft", 2L).between("relayer", "holder"))
                        .payingWith("relayer"),
                withOpContext((spec, log) -> {
                    final var target =
                            asHeadlongAddress(asAddress(spec.registry().getContractId(implementation)));
                    final var nft = idAsHeadlongAddress(spec.registry().getTokenID("nft"));
                    final var updateNfts = new Function("updateNFTsMetadata(address,int64[],bytes)");
                    final var makeCall =
                            Function.fromJson(getABIFor(FUNCTION, "makeCallWithoutAmount", implementation));
                    final var updateTreasurySerial = makeCall.encodeCallWithArgs(
                                    asHeadlongAddress("0x000000000000000000000000000000000000016c"),
                                    updateNfts
                                            .encodeCallWithArgs(nft, new long[] {1L}, "updated".getBytes())
                                            .array())
                            .array();
                    final var updateHeldSerial = makeCall.encodeCallWithArgs(
                                    asHeadlongAddress("0x000000000000000000000000000000000000016c"),
                                    updateNfts
                                            .encodeCallWithArgs(nft, new long[] {2L}, "updated".getBytes())
                                            .array())
                            .array();
                    allRunFor(
                            spec,
                            overriding(
                                    "contracts.grandfatherTokenNum",
                                    Long.toString(
                                            spec.registry().getTokenID("nft").getTokenNum() + 1)),
                            // A treasury-owned serial accepts either the metadata or the supply key. As before
                            // 0.79, the stored self key inside that compound requirement gets the standard rules.
                            contractCall(caller, "callCodeToContractWithoutAmount", target, updateTreasurySerial)
                                    .payingWith("relayer")
                                    .refusingEthConversion()
                                    .gas(1_000_000)
                                    .via("compoundRequirement"),
                            childRecordsCheck(
                                    "compoundRequirement",
                                    SUCCESS,
                                    recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                            getTokenNftInfo("nft", 1L).hasMetadata(ByteString.copyFromUtf8("first")),
                            // A serial held outside the treasury requires only the metadata key, as a whole
                            // requirement.
                            contractCall(caller, "callCodeToContractWithoutAmount", target, updateHeldSerial)
                                    .payingWith("relayer")
                                    .refusingEthConversion()
                                    .gas(1_000_000)
                                    .via("wholeRequirement"),
                            childRecordsCheck(
                                    "wholeRequirement", SUCCESS, recordWith().status(SUCCESS)),
                            getTokenNftInfo("nft", 2L).hasMetadata(ByteString.copyFromUtf8("updated")));
                }));
    }
}
