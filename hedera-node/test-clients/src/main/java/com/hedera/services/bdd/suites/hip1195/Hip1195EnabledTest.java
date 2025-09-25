// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1195;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountLambdaSStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewContract;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIMPLE_UPDATE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_DELETION_REQUIRES_ZERO_STORAGE_SLOTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class Hip1195EnabledTest {
    @Contract(contract = "PayableConstructor")
    static SpecContract HOOK_CONTRACT;

    @Contract(contract = "SmartContractsFees")
    static SpecContract HOOK_UPDATE_CONTRACT;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(HOOK_CONTRACT.getInfo());
        testLifecycle.doAdhoc(HOOK_UPDATE_CONTRACT.getInfo());
    }

    @HapiTest
    final Stream<DynamicTest> createAndUpdateAccountWithHooksAndValidateFees() {
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("payer").balance(ONE_MILLION_HBARS),
                cryptoCreate("testAccount")
                        .key("adminKey")
                        .balance(1L)
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(125L, HOOK_CONTRACT.name()))
                        .payingWith("payer")
                        .fee(ONE_HUNDRED_HBARS)
                        .via("createTxn"),
                viewAccount("testAccount", account -> {
                    assertEquals(123L, account.firstHookId());
                    assertEquals(3, account.numberHooksInUse());
                }),
                // $1 for each hook and $0.05 for the create itself
                validateChargedUsd("createTxn", 3.05),
                cryptoUpdate("testAccount")
                        .withHooks(
                                accountAllowanceHook(127L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(128L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(129L, HOOK_CONTRACT.name()))
                        .removingHooks(124L)
                        .payingWith("payer")
                        .via("updateTxn"),
                viewAccount("testAccount", account -> {
                    assertEquals(127L, account.firstHookId());
                    assertEquals(5, account.numberHooksInUse());
                }),
                // $1 for each hook and $0.00022 for the update itself
                validateChargedUsd("updateTxn", 4.00022),

                // Finally, do a transfer to validate fees with pre and post hooks transfer
                cryptoTransfer(TokenMovement.movingHbar(10).between("payer", "testAccount"))
                        .withPreHookFor("payer", 125L, 25_000L, "")
                        .withPrePostHookFor("testAccount", 128L, 25_000L, "")
                        .payingWith("payer")
                        .via("xferTxn"),
                // $0.05 for the hook invocation crypto transfer
                validateChargedUsd("xferTxn", 0.05));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarDebitPreOnlyHook() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),

                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarCreditPreOnlyHook() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarDebitPrePostHook() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),

                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPrePostHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPrePostHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarCreditPrePostHook() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPrePostHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPrePostHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenDebitPreOnlyHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                tokenCreate("token")
                        .treasury("testAccount")
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(10, "token").between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> duplicateHookIdsInOneListFailsPrecheck() {
        final var OWNER = "acctDupIds";
        final var H1 = accountAllowanceHook(7L, HOOK_CONTRACT.name());
        final var H2 = accountAllowanceHook(7L, HOOK_CONTRACT.name());

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHook(H1)
                        .withHook(H2)
                        .hasPrecheck(HOOK_ID_REPEATED_IN_CREATION_DETAILS));
    }

    @HapiTest
    final Stream<DynamicTest> deleteHooks() {
        final var OWNER = "acctHeadRun";
        final long A = 1L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER).key("k").balance(1L).withHooks(accountAllowanceHook(A, HOOK_CONTRACT.name())),
                cryptoUpdate(OWNER).removingHooks(A));
    }

    @HapiTest
    final Stream<DynamicTest> deleteHooksAndLinkNewOnes() {
        final var OWNER = "acctHeadRun";
        final long A = 1L, B = 2L, C = 3L, D = 4L, E = 5L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(
                                accountAllowanceHook(A, HOOK_CONTRACT.name()),
                                accountAllowanceHook(B, HOOK_CONTRACT.name()),
                                accountAllowanceHook(C, HOOK_CONTRACT.name())),
                cryptoUpdate(OWNER)
                        .withHooks(
                                accountAllowanceHook(A, HOOK_CONTRACT.name()),
                                accountAllowanceHook(E, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE),
                // Delete A,B (at head) and add D,E. Head should become D (the first in the creation list)
                cryptoUpdate(OWNER)
                        .removingHooks(A, B)
                        .withHooks(
                                accountAllowanceHook(D, HOOK_CONTRACT.name()),
                                accountAllowanceHook(E, HOOK_CONTRACT.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(D, a.firstHookId());
                    // started with 3; minus 2 deletes; plus 2 adds -> 3 again
                    assertEquals(3, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER)
                        .removingHooks(A)
                        .withHooks(accountAllowanceHook(A, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_NOT_FOUND),
                cryptoUpdate(OWNER).removingHooks(D).withHooks(accountAllowanceHook(D, HOOK_CONTRACT.name())));
    }

    @HapiTest
    final Stream<DynamicTest> deleteAllHooks() {
        final var OWNER = "acctHeadRun";
        final long A = 1L, B = 2L, C = 3L, D = 4L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(
                                accountAllowanceHook(A, HOOK_CONTRACT.name()),
                                accountAllowanceHook(B, HOOK_CONTRACT.name()),
                                accountAllowanceHook(C, HOOK_CONTRACT.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(A, a.firstHookId());
                    assertEquals(3, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER).removingHooks(A, B, C),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(0L, a.firstHookId());
                    assertEquals(0, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER)
                        .removingHooks(A)
                        .withHooks(accountAllowanceHook(A, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_NOT_FOUND),
                cryptoUpdate(OWNER).withHooks(accountAllowanceHook(D, HOOK_CONTRACT.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(4L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteHookWithStorage() {
        final var OWNER = "acctHeadRun";
        final Bytes A = Bytes.wrap("a");
        final Bytes B = Bytes.wrap("Bb");
        final Bytes C = Bytes.wrap("cCc");
        final Bytes D = Bytes.fromHex("dddd");
        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(1L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(0, a.numberLambdaStorageSlots());
                }),
                accountLambdaSStore(OWNER, 1L).putSlot(A, B).putSlot(C, D),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(1L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(2, a.numberLambdaStorageSlots());
                }),
                cryptoUpdate(OWNER).removingHooks(1L).hasKnownStatus(HOOK_DELETION_REQUIRES_ZERO_STORAGE_SLOTS),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(1L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(2, a.numberLambdaStorageSlots());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateWithHooksAndValidateFees() {
        return hapiTest(
                cryptoCreate("payer").balance(ONE_MILLION_HBARS),
                uploadInitCode(SIMPLE_UPDATE),
                contractCreate(SIMPLE_UPDATE)
                        .gas(300_000L)
                        .balance(0)
                        .withHooks(
                                accountAllowanceHook(21L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(22L, HOOK_CONTRACT.name()))
                        .payingWith("payer")
                        .via("createContractTxn"),
                viewContract(SIMPLE_UPDATE, (Account c) -> {
                    assertEquals(21L, c.firstHookId(), "firstHookId should be the first id in the list");
                    assertEquals(2, c.numberHooksInUse(), "contract account should track hook count");
                }),
                // $2 for hooks, $0.73 for create
                validateChargedUsd("createContractTxn", 2.73),
                contractUpdate(SIMPLE_UPDATE)
                        .withHooks(
                                accountAllowanceHook(21L, HOOK_UPDATE_CONTRACT.name()),
                                accountAllowanceHook(23L, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE),
                contractUpdate(SIMPLE_UPDATE)
                        .removingHook(21L)
                        .withHooks(
                                accountAllowanceHook(23L, HOOK_UPDATE_CONTRACT.name()),
                                accountAllowanceHook(21L, HOOK_CONTRACT.name()))
                        .payingWith("payer")
                        .via("contractUpdateTxn"),
                viewContract(SIMPLE_UPDATE, (Account c) -> {
                    assertEquals(23L, c.firstHookId());
                    assertEquals(3, c.numberHooksInUse());
                }),
                // $3 for hooks, $0.026 for update
                validateChargedUsd("contractUpdateTxn", 3.026),
                contractUpdate(SIMPLE_UPDATE)
                        .withHooks(accountAllowanceHook(26L, HOOK_UPDATE_CONTRACT.name()))
                        .payingWith("payer")
                        .blankMemo()
                        .via("contractUpdateWithSingleHookTxn"),
                viewContract(SIMPLE_UPDATE, (Account c) -> {
                    assertEquals(26L, c.firstHookId());
                    assertEquals(4, c.numberHooksInUse());
                }),
                // $1 for hook, $0.026 for update
                validateChargedUsd("contractUpdateWithSingleHookTxn", 1.026, 2));
    }
}
