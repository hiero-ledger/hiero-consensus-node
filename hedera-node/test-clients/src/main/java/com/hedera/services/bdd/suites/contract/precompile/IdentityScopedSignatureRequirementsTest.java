// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.transferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import java.math.BigInteger;
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

    @HapiTest
    final Stream<DynamicTest> explicitSelfContractKeyStillRequiresDelegatePermission() {
        return hapiTest(
                cryptoCreate("attacker").balance(ONE_HUNDRED_HBARS),
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
                tokenCreate("plainToken").treasury("treasury").initialSupply(10).supplyKey("plainContractKey"),
                tokenCreate("delegatableToken")
                        .treasury("treasury")
                        .initialSupply(10)
                        .supplyKey("delegatableKey"),
                withOpContext((spec, log) -> allRunFor(
                        spec,
                        contractCall(
                                        "DelegateContract",
                                        "mintDelegateCall",
                                        idAsHeadlongAddress(spec.registry().getTokenID("plainToken")),
                                        BigInteger.ONE)
                                .payingWith("attacker")
                                .signedBy("attacker")
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
                                .payingWith("attacker")
                                .signedBy("attacker")
                                .refusingEthConversion()
                                .gas(1_000_000)
                                .via("permittedDelegate"),
                        childRecordsCheck(
                                "permittedDelegate", SUCCESS, recordWith().status(SUCCESS)),
                        getTokenInfo("delegatableToken").hasTotalSupply(11))));
    }
}
